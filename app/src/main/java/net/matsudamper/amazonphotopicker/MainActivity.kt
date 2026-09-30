package net.matsudamper.amazonphotopicker

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider

class MainActivity : ComponentActivity() {
    private val viewModel: PickerViewModel by viewModels()

    private val isPickerMode: Boolean
        get() = intent?.action == Intent.ACTION_GET_CONTENT || intent?.action == Intent.ACTION_PICK

    /**
     * 計装テストのローカルサーバーを開くため、デバッグビルドでのみ開始URLを差し替え可能にする。
     * デバッグ版も配布しており他アプリから起動できるため、任意のページを開けないよう端末内のURLに限る。
     */
    private val startUrl: String
        get() = intent?.getStringExtra(EXTRA_START_URL)
            ?.takeIf { BuildConfig.DEBUG && isLoopbackUrl(it) }
            ?: AmazonPhotoBrowserController.START_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.setRequest(
            singleSelection = isPickerMode && !intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
            acceptedMimeTypes = if (isPickerMode) acceptedMimeTypes(intent) else listOf(),
        )

        setContent {
            PickerTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                PickerScreen(
                    uiState = uiState,
                    isPickerMode = isPickerMode,
                    startUrl = startUrl,
                    onFinish = { finishWith(viewModel.selectedImages()) },
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    private fun acceptedMimeTypes(intent: Intent): List<String> {
        val extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.filterNotNull().orEmpty()
        return extra.ifEmpty { listOfNotNull(intent.type) }
    }

    private fun finishWith(images: List<SelectedImage>) {
        if (images.isEmpty()) return
        val authority = "$packageName.fileprovider"
        val uris = images.map { FileProvider.getUriForFile(this, authority, it.file) }
        val mimeType = images.map { it.mimeType }.distinct().singleOrNull() ?: "image/*"
        val clipData = ClipData(
            "images",
            images.map { it.mimeType }.distinct().toTypedArray(),
            ClipData.Item(uris.first()),
        ).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }

        if (isPickerMode) {
            val result = Intent().apply {
                setDataAndType(uris.first(), mimeType)
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setResult(RESULT_OK, result)
            finish()
        } else {
            // ランチャーから起動された場合は共有する
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }.apply {
                type = mimeType
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, null))
        }
    }

    private fun isLoopbackUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
    }

    companion object {
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")
        const val EXTRA_START_URL = "net.matsudamper.amazonphotopicker.START_URL"
    }
}
