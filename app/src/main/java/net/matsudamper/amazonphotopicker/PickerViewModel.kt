package net.matsudamper.amazonphotopicker

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val userAgent: String,
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
    private val downloader = ImageDownloader(application)
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

    fun onImageLongPressed(url: String, pageUrl: String?, userAgent: String) {
        previewJob?.cancel()
        _uiState.update { it.copy(pending = PendingImage(url = url, pageUrl = pageUrl, userAgent = userAgent)) }
        previewJob = viewModelScope.launch {
            val bitmap = try {
                val (bytes, _) = downloader.fetchBytes(url, userAgent, pageUrl)
                withContext(Dispatchers.Default) { decodeSampled(bytes, 1024) }
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
        previewJob?.cancel()
        _uiState.update { it.copy(pending = null) }
    }

    fun confirmPending() {
        val pending = _uiState.value.pending ?: return
        dismissPending()
        download(
            sourceUrl = pending.url,
            candidates = ImageUrlResolver.candidates(pending.url, pending.pageUrl),
            userAgent = pending.userAgent,
            referer = pending.pageUrl,
        )
    }

    /** WebViewのダウンロードボタンなどから直接ダウンロードされた場合 */
    fun onDownloadRequested(url: String, pageUrl: String?, userAgent: String) {
        download(sourceUrl = url, candidates = listOf(url), userAgent = userAgent, referer = pageUrl)
    }

    private fun download(sourceUrl: String, candidates: List<String>, userAgent: String, referer: String?) {
        val sequence = ++downloadSequence
        _uiState.update { it.copy(downloadingCount = it.downloadingCount + 1) }
        viewModelScope.launch {
            try {
                val image = downloader.download(candidates, userAgent, referer)
                if (!MimeTypeMatcher.matches(image.mimeType, acceptedMimeTypes)) {
                    image.file.delete()
                    _uiState.update {
                        it.copy(
                            message = "要求された形式(${acceptedMimeTypes.joinToString()})ではないため選択できません: ${image.mimeType}",
                        )
                    }
                    return@launch
                }
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
            } catch (e: Throwable) {
                _uiState.update { it.copy(message = "画像の取得に失敗しました: ${e.message}") }
            } finally {
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

    private fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
