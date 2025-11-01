package org.multipaz.samples.wallet.cmp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraFrame
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.camera.isMirrored
import org.multipaz.compose.cropRotateScaleImage
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.facedetection.DetectedFace
import org.multipaz.facedetection.FaceLandmarkType
import org.multipaz.facedetection.detectFaces
import org.multipaz.facematch.FaceEmbedding
import org.multipaz.facematch.FaceMatchLiteRtModel
import org.multipaz.facematch.getFaceEmbeddings
import org.multipaz.util.Logger
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.TimeSource
import utopiasample.composeapp.generated.resources.Res

private const val TAG = "FaceMathScreen"

@Composable
fun FaceMathScreen(
    showToast: (message: String) -> Unit
) {
    val captureWithPreview = remember { mutableStateOf<Pair<CameraSelection, CameraCaptureResolution>?>(null) }
    val matchLastCapturedSelfie = remember { mutableStateOf<Pair<CameraSelection, CameraCaptureResolution>?>(null) }
    val matchProfileImage = remember { mutableStateOf<Pair<CameraSelection, CameraCaptureResolution>?>(null) }
    val cameraPermissionState = rememberCameraPermissionState()
    val coroutineScope = rememberCoroutineScope()
    var isProcessingFrame by remember { mutableStateOf(false) }
    //The face embedding (captured by camera)is stored in memory as faceInsetsToMatchAgainst
    var faceInsetsToMatchAgainst by remember { mutableStateOf<FaceEmbedding?>(null) }
    var facesData by remember { mutableStateOf<List<Pair<DetectedFace, Float>>?>(null) }
    val transformationMatrix = remember { mutableStateOf(Matrix()) }
    var processingStats by remember { mutableStateOf<FrameProcessingStats?>(null) }
    // the face image was captured
    val lastFaceSampleBitmap = remember { mutableStateOf<ImageBitmap?>(null) }
    val lastFaceSampleRect = remember { mutableStateOf<Rect?>(null) }
    var faceMatchLiteRtModel by remember { mutableStateOf<FaceMatchLiteRtModel?>(null) }
    // Profile image embedding loaded from resources
    var profileImageEmbedding by remember { mutableStateOf<FaceEmbedding?>(null) }
    val profileImageBitmap = remember { mutableStateOf<ImageBitmap?>(null) }

    // Initialize the FaceNet model
    LaunchedEffect(Unit) {
        try {
            val modelData = ByteString(*Res.readBytes("files/facenet_512.tflite"))
            faceMatchLiteRtModel = FaceMatchLiteRtModel(modelData, imageSquareSize = 160, embeddingsArraySize = 512)
            showToast("FaceNet model loaded successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load FaceNet model", e)
            showToast("Failed to load FaceNet model: ${e.message}")
        }
    }

    // Step 1. Capture initial selfie.
    captureWithPreview.value?.let { it ->
        AlertDialog(
            modifier = Modifier.wrapContentSize(),
            title = { Text(text = "Capture initial face") },
            text = {
                Column(
                    Modifier.wrapContentSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Capture initial face for matching. Look straight into the camera."
                    )
                    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                        Camera(
                            modifier = Modifier.fillMaxWidth(),
                            cameraSelection = it.first,
                            captureResolution = it.second,
                            showCameraPreview = true,
                            onFrameCaptured = { incomingVideoFrame ->
                                if (!isProcessingFrame) {
                                    isProcessingFrame = true
                                    val faces = detectFaces(incomingVideoFrame)
                                    transformationMatrix.value = incomingVideoFrame.previewTransformation
                                    
                                    if (faces != null && faces.size == 1) {
                                        val face = faces[0]
                                        val leftEye = face.landmarks.find { it.type == FaceLandmarkType.LEFT_EYE }
                                        val rightEye = face.landmarks.find { it.type == FaceLandmarkType.RIGHT_EYE }
                                        val mouthPosition = face.landmarks.find { it.type == FaceLandmarkType.MOUTH_BOTTOM }
                                        
                                        if (leftEye != null && rightEye != null && mouthPosition != null) {
                                            val faceImage = extractFaceBitmap(incomingVideoFrame, face, 160)
                                            faceMatchLiteRtModel?.let { model ->
                                                val faceEmbedding = getFaceEmbeddings(faceImage, model)
                                                if (faceEmbedding != null) {
                                                    faceInsetsToMatchAgainst = faceEmbedding
                                                    lastFaceSampleBitmap.value = faceImage
                                                    lastFaceSampleRect.value = face.boundingBox
                                                    showToast("Face captured successfully!")
                                                    captureWithPreview.value = null
                                                }
                                            }
                                        }
                                    }
                                    isProcessingFrame = false
                                }
                            }
                        )
                        // Draw face bounding box
                        facesData?.let {
                            DrawFaceBoundingBox(it.first().first.boundingBox, transformationMatrix.value)
                        }
                    }
                }
            },
            onDismissRequest = { captureWithPreview.value = null },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    captureWithPreview.value = null
                }) {
                    Text(text = "Close")
                }
            }
        )
    }

    // Step 2. Match against captured selfie.
    matchLastCapturedSelfie.value?.let { cameraConfig ->
        AlertDialog(
            modifier = Modifier.wrapContentSize(),
            title = { Text(text = "Match the face") },
            text = {
                if (faceInsetsToMatchAgainst == null) {
                    Text(
                        text = "The initial face to match against have to be set first. Close and use Step 1 to set it."
                    )
                } else {
                    Column(
                        Modifier.wrapContentSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                            Camera(
                                modifier = Modifier.fillMaxWidth(),
                                cameraSelection = cameraConfig.first,
                                captureResolution = cameraConfig.second,
                                showCameraPreview = true,
                                onFrameCaptured = { incomingVideoFrame ->
                                    val triple = processFrame(
                                        isProcessingFrame,
                                        matchLastCapturedSelfie.value,
                                        incomingVideoFrame,
                                        transformationMatrix,
                                        faceInsetsToMatchAgainst,
                                        faceMatchLiteRtModel,
                                        facesData,
                                        processingStats,
                                        160
                                    )
                                    facesData = triple.first
                                    isProcessingFrame = triple.second
                                    processingStats = triple.third
                                }
                            )
                            // Draw matching bitmaps in the corner.
                            lastFaceSampleBitmap.value?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = "Face image to match",
                                    modifier = Modifier
                                        .width(IntrinsicSize.Min)
                                        .height(IntrinsicSize.Min)
                                        .align(Alignment.TopEnd)
                                        .then(
                                            if (cameraConfig.first.isMirrored()) {
                                                Modifier.scale(scaleX = -1f, scaleY = 1f)
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                            }
                            // Draw matching results and frame stats.
                            facesData?.let {
                                FaceMatchResults(
                                    it,
                                    transformationMatrix.value,
                                    processingStats = processingStats ?: FrameProcessingStats(0, 0f)
                                )
                            }
                        }
                    }
                }
            },
            onDismissRequest = {
                matchLastCapturedSelfie.value = null
                isProcessingFrame = false
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    matchLastCapturedSelfie.value = null
                }) {
                    Text(text = "Close")
                }
            }
        )
    }

    // Step 3. Match against profile.png image from resources.
    matchProfileImage.value?.let { cameraConfig ->
        AlertDialog(
            modifier = Modifier.wrapContentSize(),
            title = { Text(text = "Match face with profile.png") },
            text = {
                if (profileImageEmbedding == null) {
                    Text(
                        text = "Loading profile image..."
                    )
                } else {
                    Column(
                        Modifier.wrapContentSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                            Camera(
                                modifier = Modifier.fillMaxWidth(),
                                cameraSelection = cameraConfig.first,
                                captureResolution = cameraConfig.second,
                                showCameraPreview = true,
                                onFrameCaptured = { incomingVideoFrame ->
                                    val triple = processFrame(
                                        isProcessingFrame,
                                        matchProfileImage.value,
                                        incomingVideoFrame,
                                        transformationMatrix,
                                        profileImageEmbedding,
                                        faceMatchLiteRtModel,
                                        facesData,
                                        processingStats,
                                        160
                                    )
                                    facesData = triple.first
                                    isProcessingFrame = triple.second
                                    processingStats = triple.third
                                }
                            )
                            // Draw profile image in the corner.
                            profileImageBitmap.value?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = "Profile image to match",
                                    modifier = Modifier
                                        .width(IntrinsicSize.Min)
                                        .height(IntrinsicSize.Min)
                                        .align(Alignment.TopEnd)
                                        .then(
                                            if (cameraConfig.first.isMirrored()) {
                                                Modifier.scale(scaleX = -1f, scaleY = 1f)
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                            }
                            // Draw matching results and frame stats.
                            facesData?.let {
                                FaceMatchResults(
                                    it,
                                    transformationMatrix.value,
                                    processingStats = processingStats ?: FrameProcessingStats(0, 0f)
                                )
                            }
                        }
                    }
                }
            },
            onDismissRequest = {
                matchProfileImage.value = null
                isProcessingFrame = false
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    matchProfileImage.value = null
                }) {
                    Text(text = "Close")
                }
            }
        )
    }

    // Load profile image when matchProfileImage is set
    LaunchedEffect(matchProfileImage.value) {
        if (matchProfileImage.value != null && profileImageEmbedding == null && faceMatchLiteRtModel != null) {
            try {
                val imageBytes = Res.readBytes("drawable/profile.png")
                val imageBitmap = loadImageBitmapFromBytes(imageBytes)
                if (imageBitmap != null) {
                    profileImageBitmap.value = imageBitmap
                    // Note: getFaceEmbeddings expects 160x160 images, but will handle resizing internally if needed
                    // For profile.png, we'll try to get embeddings directly - the model might handle resizing
                    val embedding = getFaceEmbeddings(imageBitmap, faceMatchLiteRtModel!!)
                    if (embedding != null) {
                        profileImageEmbedding = embedding
                        showToast("Profile image loaded successfully!")
                    } else {
                        showToast("Failed to extract face embedding from profile image")
                    }
                } else {
                    showToast("Failed to load profile image")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load profile image", e)
                showToast("Failed to load profile image: ${e.message}")
            }
        }
    }

    if (!cameraPermissionState.isGranted) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        cameraPermissionState.launchPermissionRequest()
                    }
                }
            ) {
                Text("Request Camera permission")
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                    TextButton(onClick = {
                        captureWithPreview.value =
                            Pair(CameraSelection.DEFAULT_FRONT_CAMERA, CameraCaptureResolution.MEDIUM)
                        isProcessingFrame = false
                    }) { Text("Initial Step: Take and save a selfie for further use (Front Camera, Medium Res)") }
                }
                item {
                    TextButton(onClick = {
                        captureWithPreview.value =
                            Pair(CameraSelection.DEFAULT_BACK_CAMERA, CameraCaptureResolution.MEDIUM)
                        isProcessingFrame = false
                    }) { Text("Initial Step: Take and save a selfie for further use (Back Camera, Medium Res)") }
                }
                item {
                    TextButton(onClick = {
                        matchLastCapturedSelfie.value =
                            Pair(CameraSelection.DEFAULT_FRONT_CAMERA, CameraCaptureResolution.MEDIUM)
                        isProcessingFrame = false
                    }) { Text("Take a selfie and match with previously taken one (Front Camera, Medium Res)") }
                }
                item {
                    TextButton(onClick = {
                        matchLastCapturedSelfie.value =
                            Pair(CameraSelection.DEFAULT_BACK_CAMERA, CameraCaptureResolution.MEDIUM)
                        isProcessingFrame = false
                    }) { Text("Take a selfie and match with previously taken one (Back Camera, Medium Res)") }
                }
                item {
                    TextButton(onClick = {
                        // Reset profile embedding to force reload
                        profileImageEmbedding = null
                        profileImageBitmap.value = null
                        matchProfileImage.value =
                            Pair(CameraSelection.DEFAULT_FRONT_CAMERA, CameraCaptureResolution.MEDIUM)
                        isProcessingFrame = false
                    }) { Text("Match face with profile.png (Front Camera, Medium Res)") }
                }
            }
        }
    }
}

// Expect declaration for platform-specific image loading implementation
expect fun loadImageBitmapFromBytes(bytes: ByteArray): ImageBitmap?

private fun processFrame(
    isProcessingFrame: Boolean,
    value: Pair<CameraSelection, CameraCaptureResolution>?,
    incomingVideoFrame: CameraFrame,
    transformationMatrix: MutableState<Matrix>,
    faceInsetsToMatchAgainst: FaceEmbedding?,
    faceMatchLiteRtModel: FaceMatchLiteRtModel?,
    facesData: List<Pair<DetectedFace, Float>>?,
    processingStats: FrameProcessingStats?,
    imageSize: Int
): Triple<List<Pair<DetectedFace, Float>>?, Boolean, FrameProcessingStats?> {
    var isProcessingFrame1 = isProcessingFrame
    var facesData1 = facesData
    var processingStats1 = processingStats
    if (!isProcessingFrame1 && value != null) {
        isProcessingFrame1 = true
        val overallStartTimeMark = TimeSource.Monotonic.markNow()

        val faces = detectFaces(incomingVideoFrame)
        transformationMatrix.value = incomingVideoFrame.previewTransformation
        val newFacesData = mutableListOf<Pair<DetectedFace, Float>>()

        var totalExtractBitmapDuration = Duration.ZERO
        var totalGetEmbeddingsDuration = Duration.ZERO

        if (faces != null && faceInsetsToMatchAgainst != null && faceMatchLiteRtModel != null) {
            for (face in faces) {
                val extractStartTimeMark = TimeSource.Monotonic.markNow()
                val faceImage = extractFaceBitmap(incomingVideoFrame, face, imageSize)
                totalExtractBitmapDuration += extractStartTimeMark.elapsedNow()

                val embeddingStartTimeMark = TimeSource.Monotonic.markNow()
                val faceInsetsForDetectedFace =
                    getFaceEmbeddings(faceImage, faceMatchLiteRtModel)
                totalGetEmbeddingsDuration += embeddingStartTimeMark.elapsedNow()

                if (faceInsetsForDetectedFace != null) {
                  //  Compare with the faceInsetsToMatchAgainst(previous captured image),faceInsetsForDetectedFace is current face
                    val similarity = faceInsetsToMatchAgainst
                        .calculateSimilarity(faceInsetsForDetectedFace)
                    newFacesData.add(Pair(face, similarity))
                }
            }
            facesData1 = newFacesData
        } else {
            facesData1 = null
        }

        val totalProcessingDuration = overallStartTimeMark.elapsedNow()
        val totalProcessingTimeMs = totalProcessingDuration.inWholeMilliseconds
        val currentFps =
            if (totalProcessingTimeMs > 0) 1000f / totalProcessingTimeMs else 0f

        // Store or update your stats
        processingStats1 = FrameProcessingStats(
            totalProcessingTimeMs = totalProcessingTimeMs,
            fps = currentFps
        )
        isProcessingFrame1 = false
    }
    return Triple(facesData1, isProcessingFrame1, processingStats1)
}

@Composable
private fun DrawFaceBoundingBox(box: Rect, transformationMatrix: Matrix) {
    with(box) {
        Canvas(modifier = Modifier.fillMaxWidth()) {
            val originalTopLeft = topLeft
            val originalTopRight = Offset(right, top)
            val originalBottomLeft = Offset(left, bottom)
            val originalBottomRight = bottomRight

            // Transform all four points.
            val mappedP1 = mapFaceData(originalTopLeft, transformationMatrix)
            val mappedP2 = mapFaceData(originalTopRight, transformationMatrix)
            val mappedP3 = mapFaceData(originalBottomLeft, transformationMatrix)
            val mappedP4 = mapFaceData(originalBottomRight, transformationMatrix)

            // Find min/max X and Y from all mapped points to correctly map the Rect.
            val bbTopLeft = Offset(
                x = minOf(mappedP1.x, mappedP2.x, mappedP3.x, mappedP4.x),
                y = minOf(mappedP1.y, mappedP2.y, mappedP3.y, mappedP4.y)
            )
            val bbBottomRight = Offset(
                x = maxOf(mappedP1.x, mappedP2.x, mappedP3.x, mappedP4.x),
                y = maxOf(mappedP1.y, mappedP2.y, mappedP3.y, mappedP4.y)
            )

            // Draw face frame.
            drawRoundRect(
                color = Color.Blue,
                topLeft = bbTopLeft,
                size = Size(
                    bbBottomRight.x - bbTopLeft.x,
                    bbBottomRight.y - bbTopLeft.y
                ),
                cornerRadius = CornerRadius(25f, 25f),
                style = Stroke(width = 5f)
            )
        }
    }
}

private val textStyle = TextStyle(
    fontSize = 16.sp,
    fontWeight = FontWeight.Bold,
    color = Color.Yellow
)
private val statsStyle = TextStyle(
    fontSize = 16.sp,
    fontWeight = FontWeight.Bold,
    color = Color.Yellow,
    background = Color.Black
)

data class FrameProcessingStats(
    val totalProcessingTimeMs: Long = 0L,
    val fps: Float = 0f
)

@Composable
fun FaceMatchResults(
    facesData: List<Pair<DetectedFace, Float>>,
    transformationMatrix: Matrix,
    processingStats: FrameProcessingStats
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = Modifier.fillMaxWidth()) {
        facesData.forEach { data ->
            val face = data.first
            val similarity = data.second
            var centerPoint = Offset(0f, 0f)

            with(face.boundingBox) {
                val originalTopLeft = topLeft
                val originalTopRight = Offset(right, top)
                val originalBottomLeft = Offset(left, bottom)
                val originalBottomRight = bottomRight

                // Transform all four points.
                val mappedP1 = mapFaceData(originalTopLeft, transformationMatrix)
                val mappedP2 = mapFaceData(originalTopRight, transformationMatrix)
                val mappedP3 = mapFaceData(originalBottomLeft, transformationMatrix)
                val mappedP4 = mapFaceData(originalBottomRight, transformationMatrix)

                // Find min/max X and Y from all mapped points to correctly map the Rect.
                val bbTopLeft = Offset(
                    x = minOf(mappedP1.x, mappedP2.x, mappedP3.x, mappedP4.x),
                    y = minOf(mappedP1.y, mappedP2.y, mappedP3.y, mappedP4.y)
                )
                val bbBottomRight = Offset(
                    x = maxOf(mappedP1.x, mappedP2.x, mappedP3.x, mappedP4.x),
                    y = maxOf(mappedP1.y, mappedP2.y, mappedP3.y, mappedP4.y)
                )

                centerPoint = Offset(
                    (bbTopLeft.x + bbBottomRight.x) / 2,
                    (bbTopLeft.y + bbBottomRight.y) / 2
                )

                drawRoundRect(
                    color = if (similarity < 0.4) Color.Red else Color.Green,
                    topLeft = bbTopLeft,
                    size = Size(
                        bbBottomRight.x - bbTopLeft.x,
                        bbBottomRight.y - bbTopLeft.y
                    ),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 5f)
                )
            }

            // Draw similarity value in the center.
            with("${(similarity * 100).toInt()}%") {
                val textLayoutResult = textMeasurer.measure(
                    text = this,
                    style = textStyle,
                    constraints = Constraints(maxWidth = this@Canvas.size.width.toInt())
                )
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(
                        x = centerPoint.x - (textLayoutResult.size.width / 2),
                        y = centerPoint.y - (textLayoutResult.size.height / 2)
                    )
                )
            }

            //Draw the processing stats.
            with(
                "fps: ${(processingStats.fps * 100f).roundToInt() / 100f}" +
                        "|time:${processingStats.totalProcessingTimeMs}ms"
            ) {
                val textLayoutResult = textMeasurer.measure(
                    text = this,
                    style = statsStyle,
                    constraints = Constraints(maxWidth = this@Canvas.size.width.toInt())
                )
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(
                        x = (this@Canvas.size.width.toInt() - textLayoutResult.size.width) / 2f,
                        y = 2f
                    )
                )
            }
        }
    }
}

private fun mapFaceData(point: Offset, scale: Matrix): Offset {
    return scale.map(Offset(point.x, point.y))
}

/** Postpone the face image capturing until the face is looking straight into camera. */
private fun goodFaceDetected(faces: List<DetectedFace>?): Boolean {
    if (faces != null && faces.size == 1) {
        val face = faces[0]
        val angleThreshold = 10.0f // degrees

        val isLookingStraightX = face.headEulerAngleX in -angleThreshold..angleThreshold
        val isLookingStraightY = face.headEulerAngleY in -angleThreshold..angleThreshold
        val isLookingStraightZ = face.headEulerAngleZ in -angleThreshold..angleThreshold

        return isLookingStraightX && isLookingStraightY && isLookingStraightZ
    }
    return false
}

/** Cut out the face square, rotate it to level eyes line, scale to the smaller size for face matching tasks. */
private fun extractFaceBitmap(
    frameData: CameraFrame,
    face: DetectedFace,
    targetSize: Int
): ImageBitmap {
    val leftEye = face.landmarks.find { it.type == FaceLandmarkType.LEFT_EYE }
    val rightEye = face.landmarks.find { it.type == FaceLandmarkType.RIGHT_EYE }
    val mouthPosition = face.landmarks.find { it.type == FaceLandmarkType.MOUTH_BOTTOM }

    if (leftEye == null || rightEye == null || mouthPosition == null) {
        Logger.w(TAG, "No face features for bitmap extraction.")
        return frameData.cameraImage.toImageBitmap()
    }

    // Heuristic multiplier to fit the face normalized to the eyes pupilar distance.
    val faceCropFactor = 4f

    // Heuristic multiplier to offset vertically so the face is better centered within the rectangular crop.
    val faceVerticalOffsetFactor = 0.25f

    var faceCenterX = (leftEye.position.x + rightEye.position.x) / 2
    var faceCenterY = (leftEye.position.y + rightEye.position.y) / 2
    val eyeOffsetX = leftEye.position.x - rightEye.position.x
    val eyeOffsetY = leftEye.position.y - rightEye.position.y
    val eyeDistance = sqrt(eyeOffsetX * eyeOffsetX + eyeOffsetY * eyeOffsetY)
    val faceWidth = eyeDistance * faceCropFactor
    val faceVerticalOffset = eyeDistance * faceVerticalOffsetFactor
    if (frameData.isLandscape) {
        /** Required for iOS capable of upside-down face detection. */
        faceCenterY += faceVerticalOffset * (if (leftEye.position.y < mouthPosition.position.y) 1 else -1)
    } else {
        /** Required for iOS capable of upside-down face detection. */
        faceCenterX -= faceVerticalOffset * (if (leftEye.position.x < mouthPosition.position.x) -1 else 1)
    }
    val eyesAngleRad = atan2(eyeOffsetY, eyeOffsetX)
    val eyesAngleDeg = eyesAngleRad * 180.0 / PI // Convert radians to degrees
    val totalRotationDegrees = 180 - eyesAngleDeg

    // Call platform dependent bitmap transformation.
    return cropRotateScaleImage(
        frameData = frameData, // Platform-specific image data.
        cx = faceCenterX.toDouble(), // Point between eyes
        cy = faceCenterY.toDouble(), // Point between eyes
        angleDegrees = totalRotationDegrees, //includes the camera rotation and eyes rotation.
        outputWidthPx = faceWidth.toInt(), // Expected face width for cropping *before* final scaling.
        outputHeightPx = faceWidth.toInt(),// Expected face height for cropping *before* final scaling.
        targetWidthPx = targetSize, // Final square image size (for database saving and face matching tasks).
    )
}
