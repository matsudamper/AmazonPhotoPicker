package net.matsudamper.amazonphotopicker

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import org.mozilla.geckoview.WebResponse
import java.io.File

@Immutable
data class PickerUiState(
    val selectedImages: List<SelectedImageUiState>,
    val pendingImage: PendingImageUiState?,
    val downloadingCount: Int,
    val message: String?,
    val singleSelection: Boolean,
    val listener: Listener,
) {
    @Immutable
    interface Listener {
        fun onImageLongPressed(url: String, pageUrl: String?)
        fun onDownloadResponse(response: WebResponse)
        fun onImageNotFound()
        fun onExternalNavigationBlocked()
        fun onConfirmPendingImage()
        fun onDismissPendingImage()
        fun onClearSelectedImages()
        fun onMessageShown()
    }
}

@Immutable
data class SelectedImageUiState(
    val file: File,
    val listener: Listener,
) {
    @Immutable
    interface Listener {
        fun onRemove()
    }
}

@Immutable
data class PendingImageUiState(
    val preview: Bitmap?,
    val previewFailed: Boolean,
)
