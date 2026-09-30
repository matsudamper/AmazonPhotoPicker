package net.matsudamper.amazonphotopicker

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Amazon Photos を表示する GeckoView を保持し、画像の長押しを検出する。
 */
@SuppressLint("ClickableViewAccessibility")
class AmazonPhotoBrowserController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onImageLongPressed(url: String, pageUrl: String?)
        fun onDownloadResponse(response: WebResponse)
        fun onImageNotFound()
        fun onExternalNavigationBlocked()
        fun onNavigationStateChanged(canGoBack: Boolean, progress: Int)
    }

    private class BlobTransfer(val file: File, val output: OutputStream)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime = GeckoRuntimeHolder.get(context)
    private val bridgeExtension = PickerBridgeExtension(runtime)
    private val blobDir = File(context.cacheDir, ImageDownloader.DIR_NAME)
    private val blobTransfers = ConcurrentHashMap<String, BlobTransfer>()
    private val blobWriteExecutor = Executors.newSingleThreadExecutor()
    private var currentUrl: String? = null
    private var canGoBack = false
    private var progress = 0

    /** タッチ位置のスクロール可能な要素が上端にない場合 true */
    @Volatile
    private var scrolledFromTop = true

    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build(),
    )

    private val geckoView = GeckoView(context).apply {
        id = R.id.amazon_photo_browser
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
        addView(geckoView)
        setOnRefreshListener { session.reload() }
        // Amazon Photos はページ内の要素がスクロールするため、ページ側で判定した結果を使う
        setOnChildScrollUpCallback { _, _ -> scrolledFromTop }
    }

    var isDesktopMode: Boolean = false
        private set

    init {
        session.navigationDelegate = createNavigationDelegate()
        session.progressDelegate = createProgressDelegate()
        session.contentDelegate = createContentDelegate()
        session.open(runtime)
        geckoView.setSession(session)
        geckoView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // ページからスクロール位置が届くまでは更新しない側に倒す
                scrolledFromTop = true
            }
            false
        }
    }

    fun loadInitial(url: String) {
        if (currentUrl != null) return
        // コンテンツスクリプトが最初のページから効くよう、拡張機能の準備を待ってから読み込む
        bridgeExtension.attach(session, ::handleBridgeMessage) {
            if (currentUrl == null) session.loadUri(url)
        }
    }

    fun goBack() {
        if (canGoBack) session.goBack()
    }

    fun goHome() {
        session.loadUri(START_URL)
    }

    fun setDesktopMode(enabled: Boolean) {
        if (isDesktopMode == enabled) return
        isDesktopMode = enabled
        session.settings.userAgentMode = if (enabled) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.settings.viewportMode = if (enabled) {
            GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        session.reload()
    }

    fun destroy() {
        geckoView.releaseSession()
        session.close()
        cancelBlobTransfers()
        blobWriteExecutor.shutdown()
    }

    private fun createNavigationDelegate() = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            currentUrl = url
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@AmazonPhotoBrowserController.canGoBack = canGoBack
            notifyNavigation()
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            return when (val decision = NavigationPolicy.decide(request.uri)) {
                NavigationPolicy.Decision.Allow -> GeckoResult.allow()
                is NavigationPolicy.Decision.Redirect -> {
                    session.loadUri(decision.url)
                    GeckoResult.deny()
                }
                NavigationPolicy.Decision.Block -> {
                    // アプリ起動・ストア誘導などはこのアプリ内では開けないため無視する
                    if (request.hasUserGesture) listener.onExternalNavigationBlocked()
                    GeckoResult.deny()
                }
            }
        }

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            // iframe からの遷移でトップレベルのページが置き換わらないよう、代替 URL にも従わない
            return if (NavigationPolicy.decide(request.uri) == NavigationPolicy.Decision.Allow) {
                GeckoResult.allow()
            } else {
                GeckoResult.deny()
            }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // タブを持たないため、新しいウィンドウで開くリンクも同じ画面で開く
            session.loadUri(uri)
            return null
        }
    }

    private fun createProgressDelegate() = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            // ページが切り替わると転送中のスクリプトは止まるため破棄する
            cancelBlobTransfers()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            rootView.isRefreshing = false
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            this@AmazonPhotoBrowserController.progress = progress
            notifyNavigation()
        }
    }

    private fun createContentDelegate() = object : GeckoSession.ContentDelegate {
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            listener.onDownloadResponse(response)
        }
    }

    private fun notifyNavigation() {
        listener.onNavigationStateChanged(canGoBack, progress)
    }

    private fun handleBridgeMessage(message: JSONObject): GeckoResult<Any>? {
        return when (message.optString("type")) {
            "scroll" -> {
                scrolledFromTop = message.optBoolean("scrolledFromTop", true)
                null
            }
            "image" -> {
                val url = message.optString("url").takeIf { !message.isNull("url") && it.isNotBlank() }
                if (url == null) {
                    listener.onImageNotFound()
                } else {
                    listener.onImageLongPressed(url, currentUrl)
                }
                null
            }
            "blobStart" -> startBlob()
            "blobChunk" -> writeBlobChunk(message.optString("token"), message.optString("data"))
            "blobEnd" -> {
                finishBlob(
                    token = message.optString("token"),
                    mimeType = message.optString("mimeType").takeIf { !message.isNull("mimeType") },
                )
                null
            }
            else -> null
        }
    }

    private fun startBlob(): GeckoResult<Any> {
        val token = UUID.randomUUID().toString()
        blobDir.mkdirs()
        val file = File(blobDir, "blob-$token.tmp")
        blobTransfers[token] = BlobTransfer(file, file.outputStream().buffered())
        return GeckoResult.fromValue(JSONObject().put("token", token))
    }

    private fun writeBlobChunk(token: String, base64: String): GeckoResult<Any> {
        val result = GeckoResult<Any>()
        blobWriteExecutor.execute {
            val transfer = blobTransfers[token]
            val ok = transfer != null && runCatching {
                transfer.output.write(Base64.decode(base64, Base64.DEFAULT))
            }.isSuccess
            if (!ok) discardBlob(token)
            result.complete(JSONObject().put("ok", ok))
        }
        return result
    }

    private fun finishBlob(token: String, mimeType: String?) {
        blobWriteExecutor.execute {
            val transfer = blobTransfers.remove(token) ?: return@execute
            runCatching { transfer.output.close() }
            mainHandler.post {
                if (transfer.file.length() == 0L) {
                    transfer.file.delete()
                    listener.onImageNotFound()
                    return@post
                }
                // blob の MIME タイプは判定に使えるよう URL のフラグメントで渡す
                val uri = Uri.fromFile(transfer.file).buildUpon()
                    .apply { if (!mimeType.isNullOrEmpty()) fragment(mimeType) }
                    .build()
                listener.onImageLongPressed(uri.toString(), currentUrl)
            }
        }
    }

    private fun discardBlob(token: String) {
        val transfer = blobTransfers.remove(token) ?: return
        runCatching { transfer.output.close() }
        transfer.file.delete()
    }

    /** 書き込み中のストリームを閉じないよう、書き込みと同じスレッドで破棄する */
    private fun cancelBlobTransfers() {
        blobWriteExecutor.execute {
            blobTransfers.keys.toList().forEach(::discardBlob)
        }
    }

    companion object {
        const val START_URL = "https://www.amazon.co.jp/photos/"
    }
}
