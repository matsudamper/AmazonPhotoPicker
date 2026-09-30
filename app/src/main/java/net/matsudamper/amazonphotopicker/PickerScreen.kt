package net.matsudamper.amazonphotopicker

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import org.mozilla.geckoview.WebResponse
import java.io.File

@Composable
fun PickerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun PickerScreen(
    uiState: PickerUiState,
    isPickerMode: Boolean,
    startUrl: String,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val listener by rememberUpdatedState(uiState.listener)
    val context = LocalContext.current
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }

    val onNavigationChanged: (Boolean, Int) -> Unit = remember {
        { back, newProgress ->
            canGoBack = back
            progress = newProgress
        }
    }
    val controller = remember {
        AmazonPhotoBrowserController(
            context = context,
            listener = object : AmazonPhotoBrowserController.Listener {
                override fun onImageLongPressed(url: String, pageUrl: String?) {
                    listener.onImageLongPressed(url, pageUrl)
                }

                override fun onDownloadResponse(response: WebResponse) {
                    listener.onDownloadResponse(response)
                }

                override fun onImageNotFound() {
                    listener.onImageNotFound()
                }

                override fun onExternalNavigationBlocked() {
                    listener.onExternalNavigationBlocked()
                }

                override fun onNavigationStateChanged(canGoBack: Boolean, progress: Int) {
                    onNavigationChanged(canGoBack, progress)
                }
            },
        )
    }
    DisposableEffect(controller) {
        controller.loadInitial(startUrl)
        onDispose { controller.destroy() }
    }
    LaunchedEffect(desktopMode) {
        controller.setDesktopMode(desktopMode)
    }

    BackHandler(enabled = canGoBack) {
        controller.goBack()
    }

    PickerScreenContent(
        uiState = uiState,
        isPickerMode = isPickerMode,
        progress = progress,
        desktopMode = desktopMode,
        onDesktopModeChange = { desktopMode = it },
        onGoHome = { controller.goHome() },
        onFinish = onFinish,
        onCancel = onCancel,
        browser = { modifier ->
            AndroidView(
                factory = { controller.rootView },
                modifier = modifier,
            )
        },
    )
}

/** GeckoView を差し替えてプレビューできるよう、ブラウザ以外の表示を分ける */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreenContent(
    uiState: PickerUiState,
    isPickerMode: Boolean,
    progress: Int,
    desktopMode: Boolean,
    onDesktopModeChange: (Boolean) -> Unit,
    onGoHome: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    browser: @Composable (Modifier) -> Unit,
) {
    val listener by rememberUpdatedState(uiState.listener)
    var showSelected by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message
        if (message != null) {
            listener.onMessageShown()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Amazon Photos") },
                navigationIcon = {
                    if (isPickerMode) {
                        TextButton(onClick = onCancel) { Text("キャンセル") }
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { showMenu = true }) { Text("⋮") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Amazon Photosトップへ") },
                                onClick = {
                                    showMenu = false
                                    onGoHome()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (desktopMode) "✓ PC版表示" else "PC版表示") },
                                onClick = {
                                    showMenu = false
                                    onDesktopModeChange(!desktopMode)
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { showSelected = true }) {
                        Text("選択中 ${uiState.selectedImages.size}件")
                    }
                    Text(
                        text = if (uiState.selectedImages.isEmpty()) "画像を長押しで選択" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = onFinish,
                        enabled = uiState.selectedImages.isNotEmpty() && uiState.downloadingCount == 0,
                    ) {
                        Text(if (isPickerMode) "完了" else "共有")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.downloadingCount > 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "画像を取得中… (${uiState.downloadingCount})",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            } else if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            browser(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    val pendingImage = uiState.pendingImage
    if (pendingImage != null) {
        PendingImageDialog(
            pending = pendingImage,
            singleSelection = uiState.singleSelection,
            onConfirm = { listener.onConfirmPendingImage() },
            onDismiss = { listener.onDismissPendingImage() },
        )
    }

    if (showSelected) {
        ModalBottomSheet(
            onDismissRequest = { showSelected = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SelectedImagesSheet(
                images = uiState.selectedImages,
                onClear = { listener.onClearSelectedImages() },
            )
        }
    }
}

@Composable
private fun PendingImageDialog(
    pending: PendingImageUiState,
    singleSelection: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("この画像を選択しますか？") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        pending.preview != null -> Image(
                            bitmap = pending.preview.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        pending.previewFailed -> Text("プレビューを取得できませんでした")
                        else -> CircularProgressIndicator()
                    }
                }
                if (singleSelection) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "1枚のみ選択できます。選択中の画像は置き換えられます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("選択") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun SelectedImagesSheet(
    images: List<SelectedImageUiState>,
    onClear: () -> Unit,
) {
    var previewImage by remember { mutableStateOf<SelectedImageUiState?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "選択中の画像 (${images.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, enabled = images.isNotEmpty()) { Text("すべて削除") }
        }
        if (images.isEmpty()) {
            Text(
                "選択中の画像はありません。\nWebページ上の画像を長押しすると選択できます。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(images, key = { it.file.path }) { image ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { previewImage = image },
                    ) {
                        AsyncImage(
                            model = image.file,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { image.listener.onRemove() },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("✕", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    previewImage?.let { image ->
        Dialog(
            onDismissRequest = { previewImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                AsyncImage(
                    model = image.file,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedButton(
                        onClick = { previewImage = null },
                        modifier = Modifier.weight(1f),
                    ) { Text("閉じる", color = Color.White) }
                    Button(
                        onClick = {
                            image.listener.onRemove()
                            previewImage = null
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("削除") }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PickerScreenEmptyPreview() {
    PickerScreenPreviewTemplate(
        uiState = previewUiState(selectedCount = 0, pendingImage = null, downloadingCount = 0),
    )
}

@Preview
@Composable
private fun PickerScreenSelectedPreview() {
    PickerScreenPreviewTemplate(
        uiState = previewUiState(selectedCount = 2, pendingImage = null, downloadingCount = 1),
    )
}

@Preview
@Composable
private fun PickerScreenPendingPreview() {
    PickerScreenPreviewTemplate(
        uiState = previewUiState(
            selectedCount = 1,
            pendingImage = PendingImageUiState(preview = null, previewFailed = true),
            downloadingCount = 0,
        ),
    )
}

@Composable
private fun PickerScreenPreviewTemplate(uiState: PickerUiState) {
    MaterialTheme {
        PickerScreenContent(
            uiState = uiState,
            isPickerMode = true,
            progress = 50,
            desktopMode = false,
            onDesktopModeChange = {},
            onGoHome = {},
            onFinish = {},
            onCancel = {},
            browser = { modifier ->
                Box(
                    modifier = modifier.background(Color.LightGray),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Amazon Photos")
                }
            },
        )
    }
}

private fun previewUiState(
    selectedCount: Int,
    pendingImage: PendingImageUiState?,
    downloadingCount: Int,
): PickerUiState {
    return PickerUiState(
        selectedImages = List(selectedCount) { index ->
            SelectedImageUiState(
                file = File("preview-$index.jpg"),
                listener = object : SelectedImageUiState.Listener {
                    override fun onRemove() = Unit
                },
            )
        },
        pendingImage = pendingImage,
        downloadingCount = downloadingCount,
        message = null,
        singleSelection = false,
        listener = object : PickerUiState.Listener {
            override fun onImageLongPressed(url: String, pageUrl: String?) = Unit
            override fun onDownloadResponse(response: WebResponse) = Unit
            override fun onImageNotFound() = Unit
            override fun onExternalNavigationBlocked() = Unit
            override fun onConfirmPendingImage() = Unit
            override fun onDismissPendingImage() = Unit
            override fun onClearSelectedImages() = Unit
            override fun onMessageShown() = Unit
        },
    )
}
