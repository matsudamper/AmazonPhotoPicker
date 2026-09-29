package net.matsudamper.amazonphotopicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONTokener
import java.util.UUID

/**
 * Amazon PhotosのWebViewを保持し、画像の長押しを検出する。
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class AmazonPhotoWebViewController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onImageLongPressed(url: String, pageUrl: String?, userAgent: String)
        fun onDownloadRequested(url: String, mimeType: String?, pageUrl: String?, userAgent: String)
        fun onImageNotFound()
        fun onExternalNavigationBlocked()
        fun onNavigationStateChanged(canGoBack: Boolean, progress: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mobileUserAgent: String
    private val desktopUserAgent: String
    private val blobDir = File(context.cacheDir, ImageDownloader.DIR_NAME)
    private val blobTransfers = ConcurrentHashMap<String, BlobTransfer>()
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    var isDesktopMode: Boolean = false
        private set

    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    /** 上端で下に引っ張るとページを再読み込みする */
    val rootView: SwipeRefreshLayout = SwipeRefreshLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(webView)
        setOnRefreshListener { webView.reload() }
        // Amazon Photosはページ内の要素がスクロールするため、WebView自体のスクロール位置だけでは判定できない
        setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) || innerScrolled }
    }

    /** タッチ位置のスクロール可能な要素が上端にない場合true */
    @Volatile
    private var innerScrolled = true

    val userAgent: String get() = webView.settings.userAgentString

    init {
        val defaultUa = WebSettings.getDefaultUserAgent(context)
        // WebView判定されるとログインや表示が制限されることがあるため、通常のChromeに近いUAにする
        mobileUserAgent = defaultUa
            .replace("; wv", "")
            .replace(Regex("""Version/\S+\s"""), "")
        desktopUserAgent = mobileUserAgent
            .replace(Regex("""\(Linux; Android [^)]*\)"""), "(X11; Linux x86_64)")
            .replace(" Mobile", "")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = mobileUserAgent
        }
        // Passkey(WebAuthn)を使えるようにする。ブラウザとして任意のオリジンで認証を行う
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
            )
        }
        webView.addJavascriptInterface(JsBridge(), BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return when (val decision = NavigationPolicy.decide(request.url.toString())) {
                    NavigationPolicy.Decision.Allow -> false
                    is NavigationPolicy.Decision.Redirect -> {
                        view.loadUrl(decision.url)
                        true
                    }
                    NavigationPolicy.Decision.Block -> {
                        // アプリ起動・ストア誘導などはWebViewで開けないため無視する
                        if (request.hasGesture()) listener.onExternalNavigationBlocked()
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                notifyNavigation()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                rootView.isRefreshing = false
                notifyNavigation()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                notifyNavigation()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                notifyNavigation()
            }
        }
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.startsWith("blob:")) {
                fetchBlob(url)
            } else {
                listener.onDownloadRequested(url, mimeType, webView.url, userAgent)
            }
        }
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
                updateInnerScrolled(event.x, event.y)
            }
            false
        }
        webView.setOnLongClickListener {
            handleLongPress(lastTouchX, lastTouchY)
            // trueを返してテキスト選択などのデフォルト動作を抑制する
            true
        }
    }

    fun loadInitial(url: String) {
        if (webView.url == null) {
            webView.loadUrl(url)
        }
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun goHome() {
        webView.loadUrl(START_URL)
    }

    fun setDesktopMode(enabled: Boolean) {
        if (isDesktopMode == enabled) return
        isDesktopMode = enabled
        webView.settings.userAgentString = if (enabled) desktopUserAgent else mobileUserAgent
        webView.reload()
    }

    fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }

    private fun updateInnerScrolled(x: Float, y: Float) {
        // 判定結果が返るまでは更新しない側に倒す
        innerScrolled = true
        val script = INNER_SCROLLED_SCRIPT
            .replace("__X__", x.toString())
            .replace("__Y__", y.toString())
        webView.evaluateJavascript(script) { result -> innerScrolled = result != "false" }
    }

    private fun notifyNavigation() {
        listener.onNavigationStateChanged(webView.canGoBack(), webView.progress)
    }

    private fun handleLongPress(x: Float, y: Float) {
        val hitResult = webView.hitTestResult
        val hitImageUrl = hitResult.extra.takeIf {
            hitResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        }
        val script = FIND_IMAGE_SCRIPT
            .replace("__X__", x.toString())
            .replace("__Y__", y.toString())
        webView.evaluateJavascript(script) { result ->
            val url = parseJsString(result)?.takeIf { it.isNotBlank() } ?: hitImageUrl
            onImageUrlFound(url)
        }
    }

    private fun onImageUrlFound(url: String?) {
        when {
            url == null -> listener.onImageNotFound()
            url.startsWith("blob:") -> fetchBlob(url)
            else -> listener.onImageLongPressed(url, webView.url, userAgent)
        }
    }

    private fun fetchBlob(url: String) {
        // ページ内の任意のスクリプトからブリッジを呼ばれても無視できるよう、要求ごとのトークンで照合する
        val token = UUID.randomUUID().toString()
        blobDir.mkdirs()
        blobTransfers[token] = BlobTransfer(File(blobDir, "blob-$token.tmp"))
        val script = FETCH_BLOB_SCRIPT
            .replace("__URL__", JSONObject.quote(url))
            .replace("__TOKEN__", JSONObject.quote(token))
        webView.evaluateJavascript(script, null)
    }

    private fun parseJsString(result: String?): String? {
        if (result == null || result == "null") return null
        return runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
    }

    private class BlobTransfer(val file: File) {
        val output = file.outputStream().buffered()
    }

    /**
     * blobは巨大になりうるため、data URLに変換せずチャンク単位で受け取りファイルへ書き込む。
     * JavascriptInterfaceはバックグラウンドスレッドで呼ばれる。
     */
    private inner class JsBridge {
        @JavascriptInterface
        fun onBlobChunk(token: String?, base64: String?): Boolean {
            val transfer = token?.let { blobTransfers[it] } ?: return false
            return try {
                transfer.output.write(Base64.decode(base64.orEmpty(), Base64.DEFAULT))
                true
            } catch (_: Throwable) {
                finishBlob(token, success = false)
                false
            }
        }

        @JavascriptInterface
        fun onBlobEnd(token: String?, success: Boolean) {
            if (token != null) finishBlob(token, success)
        }
    }

    private fun finishBlob(token: String, success: Boolean) {
        val transfer = blobTransfers.remove(token) ?: return
        runCatching { transfer.output.close() }
        mainHandler.post {
            if (success && transfer.file.length() > 0) {
                listener.onImageLongPressed(Uri.fromFile(transfer.file).toString(), webView.url, userAgent)
            } else {
                transfer.file.delete()
                listener.onImageNotFound()
            }
        }
    }

    companion object {
        const val START_URL = "https://www.amazon.co.jp/photos/"
        private const val BRIDGE_NAME = "AmazonPhotoPickerBridge"

        /**
         * タッチ位置(物理ピクセル)にある画像URLを探す。
         * pointer-eventsが無効な画像や、要素の下に隠れた画像、背景画像にも対応する。
         */
        private val FIND_IMAGE_SCRIPT = """
            (function(px, py) {
              var vv = window.visualViewport;
              var scale = window.devicePixelRatio * (vv ? vv.scale : 1);
              var x = px / scale + (vv ? vv.offsetLeft : 0);
              var y = py / scale + (vv ? vv.offsetTop : 0);
              function contains(el) {
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0 && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
              }
              function imgUrl(el) {
                if (!el || !el.tagName) return null;
                var tag = el.tagName.toUpperCase();
                if (tag === 'IMG') return el.currentSrc || el.src || null;
                if (tag === 'IMAGE') {
                  var href = el.href && el.href.baseVal || el.getAttribute('href') || el.getAttribute('xlink:href');
                  // SVGのhrefは相対パスのまま返るため絶対URLに解決する
                  return href ? new URL(href, document.baseURI).href : null;
                }
                var bg = window.getComputedStyle(el).backgroundImage;
                if (bg && bg !== 'none') {
                  var m = bg.match(/url\(["']?(.*?)["']?\)/);
                  if (m && m[1]) return m[1];
                }
                return null;
              }
              var stack = document.elementsFromPoint(x, y) || [];
              for (var i = 0; i < stack.length; i++) {
                var u = imgUrl(stack[i]);
                if (u) return u;
              }
              for (var j = 0; j < stack.length && j < 8; j++) {
                var imgs = stack[j].querySelectorAll ? stack[j].querySelectorAll('img') : [];
                for (var k = 0; k < imgs.length; k++) {
                  if (contains(imgs[k])) {
                    var u2 = imgUrl(imgs[k]);
                    if (u2) return u2;
                  }
                }
              }
              return null;
            })(__X__, __Y__);
        """.trimIndent()

        /** タッチ位置から祖先をたどり、上方向にスクロールできる要素があるか調べる */
        private val INNER_SCROLLED_SCRIPT = """
            (function(px, py) {
              var vv = window.visualViewport;
              var scale = window.devicePixelRatio * (vv ? vv.scale : 1);
              var x = px / scale + (vv ? vv.offsetLeft : 0);
              var y = py / scale + (vv ? vv.offsetTop : 0);
              var el = document.elementFromPoint(x, y);
              while (el && el !== document.documentElement) {
                if (el.scrollTop > 0) return true;
                el = el.parentElement;
              }
              var root = document.scrollingElement || document.documentElement;
              return !!(root && root.scrollTop > 0);
            })(__X__, __Y__);
        """.trimIndent()

        private val FETCH_BLOB_SCRIPT = """
            (function(url, token) {
              var CHUNK = 512 * 1024;
              function toBase64(buffer) {
                var bytes = new Uint8Array(buffer);
                var binary = '';
                for (var i = 0; i < bytes.length; i += 0x8000) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                }
                return btoa(binary);
              }
              fetch(url).then(function(r) { return r.blob(); }).then(function(blob) {
                var offset = 0;
                function next() {
                  if (offset >= blob.size) { $BRIDGE_NAME.onBlobEnd(token, true); return; }
                  var end = Math.min(offset + CHUNK, blob.size);
                  return blob.slice(offset, end).arrayBuffer().then(function(buffer) {
                    if (!$BRIDGE_NAME.onBlobChunk(token, toBase64(buffer))) return;
                    offset = end;
                    return next();
                  });
                }
                return next();
              }).catch(function() { $BRIDGE_NAME.onBlobEnd(token, false); });
            })(__URL__, __TOKEN__);
        """.trimIndent()
    }
}
