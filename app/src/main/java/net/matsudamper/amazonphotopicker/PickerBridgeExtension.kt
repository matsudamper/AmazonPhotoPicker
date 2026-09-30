package net.matsudamper.amazonphotopicker

import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * 長押しした画像の検出・blob の転送・スクロール位置の通知を行う組み込み拡張機能。
 * ネイティブメッセージは拡張機能のコンテンツスクリプトからしか送れないため、ページ側から偽装できない。
 */
class PickerBridgeExtension(private val runtime: GeckoRuntime) {
    fun interface MessageHandler {
        fun onMessage(message: JSONObject): GeckoResult<Any>?
    }

    fun attach(session: GeckoSession, handler: MessageHandler, onReady: () -> Unit) {
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).accept(
            { extension ->
                Log.d(TAG, "拡張機能を読み込み: ${extension?.id}")
                if (extension != null) {
                    session.webExtensionController.setMessageDelegate(
                        extension,
                        createDelegate(handler),
                        NATIVE_APP_ID,
                    )
                }
                onReady()
            },
            { error ->
                Log.e(TAG, "拡張機能のインストールに失敗", error)
                onReady()
            },
        )
    }

    private fun createDelegate(handler: MessageHandler): WebExtension.MessageDelegate {
        return object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender,
            ): GeckoResult<Any>? {
                val json = message as? JSONObject ?: return null
                return handler.onMessage(json)
            }
        }
    }

    private companion object {
        const val TAG = "PickerBridgeExtension"
        const val EXTENSION_ID = "picker-bridge@amazonphotopicker"
        const val EXTENSION_URI = "resource://android/assets/web_extensions/picker_bridge/"
        const val NATIVE_APP_ID = "amazonPhotoPicker"
    }
}
