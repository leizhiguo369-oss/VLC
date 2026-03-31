package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MyApplicationTheme {
                var capturedId by remember { mutableStateOf("等待信号...") }
                var displayFreq by remember { mutableStateOf(0f) }
                var bitStream by remember { mutableStateOf("") }
                val history = remember { mutableStateListOf<HistoryItem>() }
                
                VlcScreen(
                    capturedId = capturedId,
                    displayFreq = displayFreq,
                    bitStream = bitStream,
                    history = history,
                    onIdDecoded = { id -> 
                        capturedId = id
                        val decimal = try { Integer.parseInt(id, 2).toString() } catch (e: Exception) { "???" }
                        // 如果记录为空或与上一条 ID 不同，则加入历史（防止重复刷屏）
                        if (history.isEmpty() || history.first().id != decimal) {
                            history.add(0, HistoryItem(
                                id = decimal,
                                binary = id,
                                time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            ))
                        }
                    },
                    onDataUpdate = { f, s -> displayFreq = f; bitStream = s },
                    executor = cameraExecutor
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

data class HistoryItem(val id: String, val binary: String, val time: String)

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun VlcScreen(
    capturedId: String,
    displayFreq: Float,
    bitStream: String,
    history: MutableList<HistoryItem>,
    onIdDecoded: (String) -> Unit,
    onDataUpdate: (Float, String) -> Unit,
    executor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) { if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA) }

    val decimalId = remember(capturedId) {
        try {
            if (capturedId.all { it == '0' || it == '1' }) {
                Integer.parseInt(capturedId, 2).toString()
            } else { "---" }
        } catch (e: Exception) { "---" }
    }

    // 扫描线动画
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "line"
    )

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        // 上部分：带动画的视频预览窗口
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
                .padding(12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        ProcessCameraProvider.getInstance(ctx).addListener({
                            val provider = ProcessCameraProvider.getInstance(ctx).get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            val analysisBuilder = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setTargetResolution(android.util.Size(480, 640))
                            
                            Camera2Interop.Extender(analysisBuilder)
                                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, 0)
                                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, 200_000L)
                                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, 1600)

                            val analyzer = analysisBuilder.build().also {
                                it.setAnalyzer(executor, VlcAnalyzer(onIdDecoded, onDataUpdate))
                            }

                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
                            } catch (e: Exception) { Log.e("VLC", "Bind Error", e) }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // 动态扫描线
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val lineY = maxHeight * scanProgress
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .offset(y = lineY)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Green, Color.Transparent)
                                )
                            )
                    )
                }
            }
        }

        // 中间：当前检测卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("北京嘉业行科技有限公司", color = Color.Gray.copy(alpha = 0.6f), fontSize = 10.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(decimalId, color = Color.Green, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ID", color = Color.Green.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                }
                Text("二进制: $capturedId", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("频率: ${"%.0f".format(displayFreq)} Hz", color = Color.Yellow, fontSize = 12.sp)
                    Text("比特流: $bitStream", color = Color.Cyan, fontSize = 12.sp)
                }
            }
        }

        // 下部分：识别历史
        Column(modifier = Modifier.weight(0.65f).padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(), 
                verticalAlignment = Alignment.CenterVertically, 
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("识别历史记录", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = { history.clear() }) {
                    Icon(Icons.Default.Delete, "清除", tint = Color.Gray, modifier = Modifier.size(20.dp))
                }
            }
            
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(history) { item ->
                    ListItem(
                        headlineContent = { Text("ID: ${item.id}", color = Color.White) },
                        supportingContent = { Text(item.binary, color = Color.Gray, fontSize = 11.sp) },
                        trailingContent = { Text(item.time, color = Color.DarkGray, fontSize = 12.sp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }
    }
}

class VlcAnalyzer(
    private val onIdDecoded: (String) -> Unit,
    private val onUiUpdate: (Float, String) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastState = -1 
    private val bitBuffer = mutableListOf<Int>()
    private var lastDataEdgeTime = 0L

    override fun analyze(image: ImageProxy) {
        val buffer = image.planes[0].buffer
        val height = image.height
        val width = image.width
        val rowStride = image.planes[0].rowStride
        
        val fullPixels = IntArray(height)
        for (y in 0 until height) {
            buffer.position(y * rowStride + width / 2)
            fullPixels[y] = buffer.get().toInt() and 0xFF
        }

        val nowBase = System.currentTimeMillis()
        val segmentSize = height / 3

        for (s in 0 until 3) {
            val segment = fullPixels.sliceArray(s * segmentSize until (s + 1) * segmentSize)
            val freq = estimateFrequency(segment)
            val currentState = if (freq > 1250) 1 else 0
            
            if (lastState != -1 && currentState != lastState) {
                val virtualNow = nowBase + (s * 33) 
                val diff = virtualNow - lastDataEdgeTime
                
                if (diff > 85) {
                    val bit = if (lastState == 0 && currentState == 1) 1 else 0
                    bitBuffer.add(bit)
                    lastDataEdgeTime = virtualNow
                    
                    if (bitBuffer.size > 100) bitBuffer.removeAt(0)
                    
                    val currentStream = bitBuffer.takeLast(10).joinToString("")
                    onUiUpdate(freq, currentStream)
                    processBits()
                }
            }
            lastState = currentState
        }
        
        if (System.currentTimeMillis() % 250 < 40) {
            onUiUpdate(estimateFrequency(fullPixels.sliceArray(segmentSize until 2*segmentSize)), bitBuffer.takeLast(10).joinToString(""))
        }
        image.close()
    }

    private fun estimateFrequency(pixels: IntArray): Float {
        val avg = pixels.average()
        var crossings = 0
        var state = 0 
        for (i in 1 until pixels.size) {
            if (pixels[i] > avg + 12 && state != 1) { crossings++; state = 1 }
            else if (pixels[i] < avg - 12 && state != -1) { crossings++; state = -1 }
        }
        return (crossings * 65f) 
    }

    private fun processBits() {
        val sync = listOf(0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0)
        if (bitBuffer.size < 32) return

        for (i in 0..(bitBuffer.size - 32)) {
            if (bitBuffer.subList(i, i + sync.size) == sync) {
                val dataPart = bitBuffer.subList(i + sync.size, i + sync.size + 16)
                val crcPart = bitBuffer.subList(i + sync.size + 16, i + sync.size + 20)
                
                if (crcPart == listOf(1, 1, 0, 0)) {
                    val rawId = dataPart.joinToString("")
                    onIdDecoded(rawId)
                    bitBuffer.clear()
                    return
                }
            }
        }
    }
}
