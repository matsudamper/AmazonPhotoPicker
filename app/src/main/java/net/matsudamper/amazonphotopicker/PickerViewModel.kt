package net.matsudamper.amazonphotopicker

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class SelectedImage(
    val id: String,
    val file: File,
    val mimeType: String,
    val sourceUrl: String,
)

class PickerViewModel(application: Application) : AndroidViewModel(application) {
    private data class PendingImage(
        val url: String,
        val pageUrl: String?,
        val preview: Bitmap?,
        val previewFailed: Boolean,
    )

    private data class ViewModelState(
        val selected: List<SelectedImage>,
        val pending: PendingImage?,
        val downloadingCount: Int,
        val message: String?,
        val singleSelection: Boolean,
        val acceptedMimeTypes: List<String>,
    )

    private val downloader = ImageDownloader(
        context = application,
        webExecutor = GeckoWebExecutor(GeckoRuntimeHolder.get(application)),
    )

    private val viewModelState = MutableStateFlow(
        ViewModelState(
            selected = listOf(),
            pending = null,
            downloadingCount = 0,
            message = null,
            singleSelection = false,
            acceptedMimeTypes = listOf(),
        ),
    )

    private val listener = object : PickerUiState.Listener {
        override fun onImageLongPressed(url: String, pageUrl: String?) {
            startPending(url, pageUrl)
        }

        override fun onDownloadResponse(response: WebResponse) {
            val body = response.body ?: return
            val acceptedMimeTypes = viewModelState.value.acceptedMimeTypes
            download(sourceUrl = response.uri) {
                downloader.saveResponse(body, response.contentType(), acceptedMimeTypes)
            }
        }

        override fun onImageNotFound() {
            showMessage("長押しした位置に画像が見つかりませんでした")
        }

        override fun onExternalNavigationBlocked() {
            showMessage("アプリへの移動はこのアプリ内では開けません")
        }

        override fun onConfirmPendingImage() {
            val pending = viewModelState.value.pending ?: return
            val acceptedMimeTypes = viewModelState.value.acceptedMimeTypes
            clearPending()
            download(sourceUrl = pending.url) {
                downloader.download(
                    candidates = ImageUrlResolver.candidates(pending.url, pending.pageUrl),
                    referer = pending.pageUrl,
                    acceptedMimeTypes = acceptedMimeTypes,
                )
            }
        }

        override fun onDismissPendingImage() {
            viewModelState.value.pending?.let { downloader.deleteLocalSource(it.url) }
            clearPending()
        }

        override fun onClearSelectedImages() {
            viewModelState.update { state ->
                state.selected.forEach { it.file.delete() }
                state.copy(selected = listOf())
            }
        }

        override fun onMessageShown() {
            viewModelState.update { it.copy(message = null) }
        }
    }

    val uiState: StateFlow<PickerUiState> = viewModelState
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, viewModelState.value.toUiState())

    /** 単一選択時に、後から確定した画像を優先するための連番 */
    private var downloadSequence = 0

    private var previewJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { downloader.cleanupOldFiles() }
    }

    /**
     * 呼び出し元の要求を設定する。
     * @param acceptedMimeTypes 空なら制限なし
     */
    fun setRequest(singleSelection: Boolean, acceptedMimeTypes: List<String>) {
        viewModelState.update {
            it.copy(singleSelection = singleSelection, acceptedMimeTypes = acceptedMimeTypes)
        }
    }

    fun selectedImages(): List<SelectedImage> = viewModelState.value.selected

    private fun ViewModelState.toUiState(): PickerUiState {
        return PickerUiState(
            selectedImages = selected.map { image ->
                SelectedImageUiState(
                    file = image.file,
                    listener = object : SelectedImageUiState.Listener {
                        override fun onRemove() {
                            remove(image.id)
                        }
                    },
                )
            },
            pendingImage = pending?.let {
                PendingImageUiState(preview = it.preview, previewFailed = it.previewFailed)
            },
            downloadingCount = downloadingCount,
            message = message,
            singleSelection = singleSelection,
            listener = listener,
        )
    }

    private fun startPending(url: String, pageUrl: String?) {
        previewJob?.cancel()
        viewModelState.value.pending?.let { downloader.deleteLocalSource(it.url) }
        viewModelState.update {
            it.copy(pending = PendingImage(url = url, pageUrl = pageUrl, preview = null, previewFailed = false))
        }
        previewJob = viewModelScope.launch {
            val bitmap = try {
                downloader.loadPreview(url, pageUrl, maxSize = 1024)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            viewModelState.update { state ->
                val pending = state.pending
                if (pending?.url != url) return@update state
                state.copy(pending = pending.copy(preview = bitmap, previewFailed = bitmap == null))
            }
        }
    }

    private fun clearPending() {
        previewJob?.cancel()
        viewModelState.update { it.copy(pending = null) }
    }

    private fun download(sourceUrl: String, obtainImage: suspend () -> DownloadedImage) {
        val sequence = ++downloadSequence
        viewModelState.update { it.copy(downloadingCount = it.downloadingCount + 1) }
        viewModelScope.launch {
            try {
                val image = obtainImage()
                if (viewModelState.value.singleSelection && sequence != downloadSequence) {
                    // より新しい画像が確定されているため、この結果は破棄する
                    image.file.delete()
                    return@launch
                }
                addSelected(
                    SelectedImage(
                        id = UUID.randomUUID().toString(),
                        file = image.file,
                        mimeType = image.mimeType,
                        sourceUrl = sourceUrl,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedMimeTypeException) {
                val accepted = viewModelState.value.acceptedMimeTypes.joinToString()
                showMessage("要求された形式($accepted)ではないため選択できません: ${e.mimeType}")
            } catch (e: Throwable) {
                showMessage("画像の取得に失敗しました: ${e.message}")
            } finally {
                downloader.deleteLocalSource(sourceUrl)
                viewModelState.update { it.copy(downloadingCount = it.downloadingCount - 1) }
            }
        }
    }

    private fun addSelected(image: SelectedImage) {
        viewModelState.update { state ->
            val newList = if (state.singleSelection) {
                state.selected.forEach { it.file.delete() }
                listOf(image)
            } else {
                state.selected + image
            }
            state.copy(selected = newList, message = "選択しました (${newList.size}件)")
        }
    }

    private fun remove(id: String) {
        viewModelState.update { state ->
            state.selected.find { it.id == id }?.file?.delete()
            state.copy(selected = state.selected.filterNot { it.id == id })
        }
    }

    private fun showMessage(message: String) {
        viewModelState.update { it.copy(message = message) }
    }
}
