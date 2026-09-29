package net.matsudamper.amazonphotopicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

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
        fun onNavigationStateChanged(canGoBack: Boolean, progress: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mobileUserAgent: String
    private val desktopUserAgent: String
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
        webView.addJavascriptInterface(JsBridge(), BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                notifyNavigation()
            }

            override fun onPageFinished(view: WebView, url: String?) {
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

    fun reload() {
        webView.reload()
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
        val script = FETCH_BLOB_SCRIPT.replace("__URL__", JSONObject.quote(url))
        webView.evaluateJavascript(script, null)
    }

    private fun parseJsString(result: String?): String? {
        if (result == null || result == "null") return null
        return runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onBlobData(dataUrl: String?) {
            mainHandler.post {
                if (dataUrl.isNullOrEmpty()) {
                    listener.onImageNotFound()
                } else {
                    listener.onImageLongPressed(dataUrl, webView.url, userAgent)
                }
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
                if (tag === 'IMAGE') return el.href && (el.href.baseVal || el.href) || null;
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

        private val FETCH_BLOB_SCRIPT = """
            (function(url) {
              fetch(url).then(function(r) { return r.blob(); }).then(function(blob) {
                var reader = new FileReader();
                reader.onloadend = function() { $BRIDGE_NAME.onBlobData(reader.result); };
                reader.readAsDataURL(blob);
              }).catch(function() { $BRIDGE_NAME.onBlobData(null); });
            })(__URL__);
        """.trimIndent()
    }
}
