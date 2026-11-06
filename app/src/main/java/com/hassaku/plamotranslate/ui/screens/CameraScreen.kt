package com.hassaku.plamotranslate.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.hassaku.plamotranslate.data.ocr.RecognizedBlock
import com.hassaku.plamotranslate.data.ocr.TextRecognitionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onTextSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    textRecognitionHelper: TextRecognitionHelper? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var recognizedBlocks by remember { mutableStateOf<List<RecognizedBlock>>(emptyList()) }
    var fullText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var ocrError by remember { mutableStateOf<String?>(null) }
    var selectedBlock by remember { mutableStateOf<RecognizedBlock?>(null) }
    var showResultSheet by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var bindKey by remember { mutableIntStateOf(0) }

    val captureFlag = remember { AtomicBoolean(false) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderRef = remember { AtomicReference<ProcessCameraProvider?>(null) }

    // Always release camera when leaving this screen (pager swipe / nav)
    DisposableEffect(Unit) {
        onDispose {
            captureFlag.set(false)
            runCatching { cameraProviderRef.get()?.unbindAll() }
            cameraProviderRef.set(null)
            analyzerExecutor.shutdown()
        }
    }

    if (!hasPermission) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("カメラの権限が必要です", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("権限を許可する")
                }
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        key(lensFacing, bindKey) {
            // Rebind/unbind for this preview instance
            DisposableEffect(lensFacing, bindKey) {
                onDispose {
                    runCatching { cameraProviderRef.get()?.unbindAll() }
                }
            }
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProviderRef.set(cameraProvider)
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .build()

                            imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                if (captureFlag.compareAndSet(true, false)) {
                                    try {
                                        val bitmap = imageProxy.toBitmapSafe()
                                        scope.launch {
                                            isProcessing = true
                                            ocrError = null
                                            try {
                                                val helper = textRecognitionHelper
                                                if (helper == null) {
                                                    ocrError = "OCRエンジンが初期化されていません"
                                                    return@launch
                                                }
                                                val result = withContext(Dispatchers.Default) {
                                                    helper.recognize(bitmap)
                                                }
                                                recognizedBlocks = result.blocks
                                                fullText = result.fullText
                                                selectedBlock = null
                                                showResultSheet = true
                                            } catch (e: Exception) {
                                                Log.e("CameraScreen", "OCR failed", e)
                                                ocrError = "文字認識に失敗しました"
                                            } finally {
                                                isProcessing = false
                                                if (!bitmap.isRecycled) bitmap.recycle()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CameraScreen", "Frame capture failed", e)
                                        captureFlag.set(false)
                                    }
                                }
                                imageProxy.close()
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            Log.e("CameraScreen", "Camera bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("テキストを撮影して選択", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                bindKey++
            }) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "切替", tint = Color.White)
            }
        }

        ocrError?.let { msg ->
            Text(
                msg,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
            )
        }

        FloatingActionButton(
            onClick = { if (!isProcessing) { ocrError = null; captureFlag.set(true) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.PhotoCamera, contentDescription = "撮影")
            }
        }
    }

    if (showResultSheet) {
        ModalBottomSheet(
            onDismissRequest = { showResultSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("検出されたテキスト", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (fullText.isBlank()) {
                    Text("テキストが検出されませんでした。もう一度撮影してください。")
                } else {
                    Card(modifier = Modifier.fillMaxWidth().clickable {
                        onTextSelected(fullText.trim())
                        showResultSheet = false
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("全文を使う", fontWeight = FontWeight.Bold)
                            Text(fullText.take(200) + if (fullText.length > 200) "…" else "")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(recognizedBlocks) { block ->
                            val isSelected = selectedBlock == block
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                                        else Modifier
                                    )
                                    .clickable { selectedBlock = block }
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(block.text, modifier = Modifier.weight(1f))
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val text = selectedBlock?.text ?: fullText
                            if (text.isNotBlank()) {
                                onTextSelected(text.trim())
                                showResultSheet = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedBlock != null || fullText.isNotBlank()
                    ) { Text("選択したテキストを翻訳へ送る") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun ImageProxy.toBitmapSafe(): Bitmap {
    if (format == ImageFormat.JPEG) {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode JPEG")
        return rotate(bitmap, imageInfo.rotationDegrees)
    }
    val nv21 = yuv420888ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val imageBytes = out.toByteArray()
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        ?: throw IllegalStateException("Failed to decode YUV frame")
    return rotate(bitmap, imageInfo.rotationDegrees)
}

private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap && !bitmap.isRecycled) bitmap.recycle()
    return rotated
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val nv21 = ByteArray(width * height * 3 / 2)
    var pos = 0
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    if (yPixelStride == 1 && yRowStride == width) {
        yBuffer.get(nv21, 0, width * height)
        pos = width * height
    } else {
        val yRow = ByteArray(yRowStride)
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(yRow, 0, minOf(yRowStride, yBuffer.remaining()))
            if (yPixelStride == 1) {
                System.arraycopy(yRow, 0, nv21, pos, width)
                pos += width
            } else {
                for (col in 0 until width) nv21[pos++] = yRow[col * yPixelStride]
            }
        }
    }
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    val vRowStride = vPlane.rowStride
    val uRowStride = uPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val uPixelStride = uPlane.pixelStride
    val vRow = ByteArray(vRowStride)
    val uRow = ByteArray(uRowStride)
    for (row in 0 until chromaHeight) {
        vBuffer.position(row * vRowStride)
        uBuffer.position(row * uRowStride)
        vBuffer.get(vRow, 0, minOf(vRowStride, vBuffer.remaining()))
        uBuffer.get(uRow, 0, minOf(uRowStride, uBuffer.remaining()))
        for (col in 0 until chromaWidth) {
            nv21[pos++] = vRow[col * vPixelStride]
            nv21[pos++] = uRow[col * uPixelStride]
        }
    }
    return nv21
}
