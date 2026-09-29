package net.matsudamper.amazonphotopicker

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PickerFlowTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var server: TestHttpServer

    @Before
    fun setUp() {
        server = TestHttpServer(
            mapOf(
                // Amazon Photosのように画像の上に透明な要素が重なっているページ
                "/overlay.html" to ("text/html" to """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                    <body style="margin:0">
                      <div style="position:relative;width:100vw;height:100vh">
                        <img src="/red.png" style="width:100%;height:100%;object-fit:cover;pointer-events:none">
                        <a href="#" style="position:absolute;inset:0;background:transparent"></a>
                      </div>
                    </body></html>
                """.trimIndent().toByteArray()),
                // background-imageで表示されているページ
                "/background.html" to ("text/html" to """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                    <body style="margin:0">
                      <div style="width:100vw;height:100vh;background-image:url('/blue.png');background-size:cover"></div>
                    </body></html>
                """.trimIndent().toByteArray()),
                "/red.png" to ("image/png" to createPng(Color.RED, 64, 48)),
                "/blue.png" to ("image/png" to createPng(Color.BLUE, 40, 30)),
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun selectSingleImageUnderOverlayAndReturnResult() {
        val scenario = launch(page = "/overlay.html", allowMultiple = false)

        longPressAndSelect()
        composeRule.waitUntilAtLeastOneExists(hasText("選択中 1件"), TIMEOUT)
        screenshot("single_selected")

        composeRule.onNodeWithText("完了").performClick()

        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        val uri = requireNotNull(result.resultData?.data)
        val bitmap = decode(uri)
        assertEquals(64, bitmap.width)
        assertEquals(48, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(10, 10))
    }

    @Test
    fun selectMultipleImagesAndRemoveOne() {
        val scenario = launch(page = "/background.html", allowMultiple = true)

        longPressAndSelect()
        composeRule.waitUntilAtLeastOneExists(hasText("選択中 1件"), TIMEOUT)
        longPressAndSelect()
        composeRule.waitUntilAtLeastOneExists(hasText("選択中 2件"), TIMEOUT)

        // 選択中の画像を確認して1件削除する
        composeRule.onNodeWithText("選択中 2件").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("選択中の画像 (2)"), TIMEOUT)
        screenshot("multiple_selected_sheet")
        composeRule.onAllNodesWithText("✕")[0].performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("選択中の画像 (1)"), TIMEOUT)
        device.pressBack()
        composeRule.waitUntilAtLeastOneExists(hasText("選択中 1件"), TIMEOUT)

        composeRule.onNodeWithText("完了").performClick()

        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        val clipData = requireNotNull(result.resultData?.clipData)
        assertEquals(1, clipData.itemCount)
        val bitmap = decode(clipData.getItemAt(0).uri)
        assertEquals(40, bitmap.width)
        assertEquals(Color.BLUE, bitmap.getPixel(5, 5))
    }

    private fun launch(page: String, allowMultiple: Boolean): ActivityScenario<MainActivity> {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            setClass(context, MainActivity::class.java)
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            putExtra(MainActivity.EXTRA_START_URL, server.baseUrl + page)
        }
        return ActivityScenario.launchActivityForResult(intent)
    }

    /** WebViewを長押しし、確認ダイアログで「選択」を押す。ページ読み込み待ちのためリトライする */
    private fun longPressAndSelect() {
        val webView = requireNotNull(device.wait(Until.findObject(By.clazz("android.webkit.WebView")), TIMEOUT))
        repeat(10) { attempt ->
            val bounds = webView.visibleBounds
            device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 150)
            val shown = runCatching {
                composeRule.waitUntilAtLeastOneExists(hasText("この画像を選択しますか？"), 3_000)
            }.isSuccess
            if (shown) {
                // プレビューの読み込みを待ってからスクリーンショットを撮る
                Thread.sleep(1_000)
                screenshot("confirm_dialog_$attempt")
                composeRule.onNodeWithText("選択").performClick()
                return
            }
            Thread.sleep(1_000)
        }
        throw AssertionError("確認ダイアログが表示されませんでした")
    }

    private fun decode(uri: Uri): Bitmap {
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw AssertionError("decode failed: $uri")
    }

    private fun screenshot(name: String) {
        runCatching {
            device.executeShellCommand("mkdir -p $SCREENSHOT_DIR")
            device.executeShellCommand("screencap -p $SCREENSHOT_DIR/$name.png")
        }
    }

    private fun createPng(color: Int, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    companion object {
        private const val TIMEOUT = 15_000L
        private const val SCREENSHOT_DIR = "/sdcard/Download/picker-screenshots"
    }
}
