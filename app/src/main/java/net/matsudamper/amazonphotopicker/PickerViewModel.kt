package net.matsudamper.amazonphotopicker

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class PendingImage(
    val url: String,
    val pageUrl: String?,
    val preview: Bitmap? = null,
    val previewFailed: Boolean = false,
)

data class PickerUiState(
    val selected: List<SelectedImage> = emptyList(),
    val pending: PendingImage? = null,
    val downloadingCount: Int = 0,
    val message: String? = null,
)

class PickerViewModel(application: Application) : AndroidViewModel(application) {
    private val downloader = ImageDownloader(
        context = application,
        webExecutor = GeckoWebExecutor(GeckoRuntimeHolder.get(application)),
    )
    private val _uiState = MutableStateFlow(PickerUiState())
    val uiState: StateFlow<PickerUiState> = _uiState.asStateFlow()

    /** trueなら1枚のみ選択可能 */
    var singleSelection: Boolean = false

    /** 呼び出し元が要求したMIMEタイプ。空なら制限なし */
    var acceptedMimeTypes: List<String> = emptyList()

    /** 単一選択時に、後から確定した画像を優先するための連番 */
    private var downloadSequence = 0

    private var previewJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { downloader.cleanupOldFiles() }
    }

    fun onImageLongPressed(url: String, pageUrl: String?) {
        previewJob?.cancel()
        _uiState.value.pending?.let { downloader.deleteLocalSource(it.url) }
        _uiState.update { it.copy(pending = PendingImage(url = url, pageUrl = pageUrl)) }
        previewJob = viewModelScope.launch {
            val bitmap = try {
                downloader.loadPreview(url, pageUrl, maxSize = 1024)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            _uiState.update { state ->
                val pending = state.pending
                if (pending?.url != url) return@update state
                state.copy(pending = pending.copy(preview = bitmap, previewFailed = bitmap == null))
            }
        }
    }

    fun dismissPending() {
        _uiState.value.pending?.let { downloader.deleteLocalSource(it.url) }
        clearPending()
    }

    private fun clearPending() {
        previewJob?.cancel()
        _uiState.update { it.copy(pending = null) }
    }

    fun confirmPending() {
        val pending = _uiState.value.pending ?: return
        clearPending()
        download(sourceUrl = pending.url) {
            downloader.download(
                candidates = ImageUrlResolver.candidates(pending.url, pending.pageUrl),
                referer = pending.pageUrl,
                acceptedMimeTypes = acceptedMimeTypes,
            )
        }
    }

    /** ページのダウンロードボタンなどから直接ダウンロードされた場合 */
    fun onDownloadResponse(response: WebResponse) {
        val body = response.body ?: return
        download(sourceUrl = response.uri) {
            downloader.saveResponse(body, response.contentType(), acceptedMimeTypes)
        }
    }

    private fun download(sourceUrl: String, obtainImage: suspend () -> DownloadedImage) {
        val sequence = ++downloadSequence
        _uiState.update { it.copy(downloadingCount = it.downloadingCount + 1) }
        viewModelScope.launch {
            try {
                val image = obtainImage()
                if (singleSelection && sequence != downloadSequence) {
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
                _uiState.update {
                    it.copy(message = "要求された形式(${acceptedMimeTypes.joinToString()})ではないため選択できません: ${e.mimeType}")
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(message = "画像の取得に失敗しました: ${e.message}") }
            } finally {
                downloader.deleteLocalSource(sourceUrl)
                _uiState.update { it.copy(downloadingCount = it.downloadingCount - 1) }
            }
        }
    }

    private fun addSelected(image: SelectedImage) {
        _uiState.update { state ->
            val newList = if (singleSelection) {
                state.selected.forEach { it.file.delete() }
                listOf(image)
            } else {
                state.selected + image
            }
            state.copy(selected = newList, message = "選択しました (${newList.size}件)")
        }
    }

    fun remove(id: String) {
        _uiState.update { state ->
            state.selected.find { it.id == id }?.file?.delete()
            state.copy(selected = state.selected.filterNot { it.id == id })
        }
    }

    fun clear() {
        _uiState.update { state ->
            state.selected.forEach { it.file.delete() }
            state.copy(selected = emptyList())
        }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
