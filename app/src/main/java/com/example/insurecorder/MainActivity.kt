package com.example.insurecorder

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



// MainActivity 설정
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen()
        }
        requestBatteryOptimizationException()
    }
    private fun requestBatteryOptimizationException() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val packageName = packageName

        // 현재 앱이 배터리 최적화에서 제외되어 있는지 확인
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

// 메인 화면 UI
@Composable
fun MainScreen() {
    var hasPermission by remember { mutableStateOf(false) }

    RequestPermissions { granted: Boolean ->
        hasPermission = granted
    }

    if (hasPermission) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AudioScreen()
            }
        }
    }
}

// 파일 선택 및 녹음 UI
@Composable
fun AudioScreen() {
    var isAudioSelected by remember { mutableStateOf(false) }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var inputFileName by remember { mutableStateOf("") }

    val context = LocalContext.current

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedAudioUri = uri
            isAudioSelected = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        TextField(
            value = inputFileName,
            onValueChange = { inputFileName = it },
            label = { Text("파일 이름 입력") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            audioPickerLauncher.launch("audio/*")
        }) {
            Text(text = "음원 선택")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                selectedAudioUri?.let { uri ->
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val finalFileName = "${inputFileName}_${timeStamp}.wav"
                    startPlaybackAndRecording(context, uri, finalFileName)
                }
            },
            enabled = isAudioSelected
        ) {
            Text(text = "재생 및 녹음")
        }

        Spacer(modifier = Modifier.height(16.dp))

        selectedAudioUri?.let {
            Text(text = "선택된 음원: ${it.lastPathSegment}")
        }
    }
}

// WAV 재생 및 녹음 시작
fun startPlaybackAndRecording(
    context: Context,
    audioUri: Uri,
    fileName: String,
    onRecordingComplete: (String) -> Unit = {}
) {
    val recorder = RawAudioRecorder(context)
    var recordingStarted = false

    try {
        playWavWithAudioTrack(context, audioUri, onPlaybackStart = {
            recorder.startRecording("record_temp")
            recordingStarted = true
        }, onPlaybackComplete = {
            if (recordingStarted) {
                recorder.stopRecording()

                val rawFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "record_temp.raw")
                val wavFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), fileName)

                if (rawFile.exists() && rawFile.length() > 1024) {
                    convertRawToWav(rawFile, wavFile, 96000, 1)
                    copyWavToDownload(context, wavFile, fileName)
                    println("WAV 파일 저장 완료: ${wavFile.absolutePath}")
                    onRecordingComplete(wavFile.absolutePath)
                } else {
                    println("녹음된 파일이 너무 작습니다.")
                    rawFile.delete()
                }
            }
        })
    } catch (e: Exception) {
        e.printStackTrace()
        println("AudioTrack 초기화 실패: ${e.message}")
    }
}

// AudioTrack으로 WAV 파일 재생
var audioTrack: AudioTrack? = null

fun playWavWithAudioTrack(
    context: Context,
    audioUri: Uri,
    onPlaybackStart: () -> Unit,
    onPlaybackComplete: () -> Unit
) {
    val inputStream = context.contentResolver.openInputStream(audioUri) ?: return
    val wavHeaderSize = 44
    val audioData = inputStream.readBytes().drop(wavHeaderSize).toByteArray()
    inputStream.close()

    Log.i("AudioPlayer", "WAV 파일 크기: ${audioData.size} 바이트")

    val sampleRate = extractSampleRateFromWavHeader(ByteArrayInputStream(audioData))

    // 기존 AudioTrack 해제
    audioTrack?.apply {
        stop()
        release()
    }
    audioTrack = null

    try {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            //AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2  // 버퍼 크기를 두 배로 설정해 안정성 강화

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    //.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.apply {
            setVolume(1.0f)
            play()

            Thread {
                val bytesWritten = write(audioData, 0, audioData.size)
                Log.i("AudioPlayer", "$bytesWritten 바이트 성공적으로 씀.")
                stop()
                release()
                audioTrack = null
                onPlaybackComplete()
            }.start()
            onPlaybackStart()
        }
    } catch (e: Exception) {
        Log.e("AudioTrack", "AudioTrack 초기화 실패: ${e.message}")
    }
}

// WAV 헤더에서 샘플레이트 추출
fun extractSampleRateFromWavHeader(inputStream: java.io.InputStream): Int {
    val buffer = ByteArray(4)
    inputStream.skip(24)  // WAV 헤더에서 24~27 바이트에 위치한 샘플레이트 추출
    val bytesRead = inputStream.read(buffer, 0, 4)  // 샘플레이트 (4바이트) 읽기

    // 4바이트를 모두 읽지 못한 경우 기본 샘플레이트 반환
    if (bytesRead != 4) {
        Log.e("AudioPlayer", "WAV 헤더에서 샘플레이트를 읽지 못했습니다. 기본값(48000Hz) 사용.")
        return 48000
    }

    val headerBuffer = ByteBuffer.wrap(buffer)
    headerBuffer.order(ByteOrder.LITTLE_ENDIAN)  // 리틀 엔디언 설정
    val sampleRate = headerBuffer.int

    Log.i("AudioPlayer", "추출된 샘플레이트: $sampleRate")

    // 샘플레이트 유효성 검사 (8kHz ~ 192kHz 범위)
    return if (sampleRate in 8000..192000) {
        sampleRate
    } else {
        Log.e("AudioPlayer", "샘플레이트 비정상: $sampleRate. 기본값(48000Hz) 사용.")
        48000  // 기본값으로 48kHz 사용
    }
}


// RawAudioRecorder 클래스
class RawAudioRecorder(private val context: Context) {
    //private val sampleRate = 48000
    private val sampleRate = 96000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private lateinit var audioRecord: AudioRecord
    private var isRecording = false
    private var outputStream: OutputStream? = null

    fun startRecording(fileName: String) {
        try {
            audioRecord = AudioRecord(
                android.media.MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            val outputFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "$fileName.raw")
            outputStream = FileOutputStream(outputFile)
            audioRecord.startRecording()
            isRecording = true

            Thread {
                val data = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord.read(data, 0, data.size)
                    if (read > 0) {
                        outputStream?.write(data, 0, read)
                    }
                }
                stopRecording()
            }.start()
        } catch (e: Exception) {
            Log.e("AudioRecord", "AudioRecord 초기화 실패: ${e.message}")
        }
    }


    fun stopRecording() {
        if (isRecording) {
            isRecording = false
            audioRecord.stop()
            audioRecord.release()
            outputStream?.close()
        }
    }
}

// RAW를 WAV로 변환
fun convertRawToWav(rawFile: File, wavFile: File, sampleRate: Int, channels: Int) {
    val rawData = rawFile.readBytes()
    val totalDataLen = rawData.size + 36
    val byteRate = sampleRate * channels * 2

    val header = ByteArray(44)
    ByteBuffer.wrap(header).apply {
        order(ByteOrder.LITTLE_ENDIAN)
        put("RIFF".toByteArray())
        putInt(totalDataLen)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort((channels * 2).toShort())
        putShort(16)
        put("data".toByteArray())
        putInt(rawData.size)
    }

    FileOutputStream(wavFile).use { fos ->
        fos.write(header)
        fos.write(rawData)
    }
}

// WAV 파일을 다운로드 폴더로 복사
fun copyWavToDownload(context: Context, sourceFile: File, fileName: String) {
    val resolver = context.contentResolver
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }

    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

    if (uri == null) {
        Log.e("FileSave", "WAV 파일을 저장할 수 없습니다.")
        return
    }

    try {
        resolver.openOutputStream(uri)?.use { outputStream ->
            sourceFile.inputStream().use { input ->
                input.copyTo(outputStream)
                Log.i("FileSave", "WAV 파일이 Downloads에 저장되었습니다: $fileName")
            }
        } ?: Log.e("FileSave", "OutputStream이 null입니다.")
    } catch (e: Exception) {
        Log.e("FileSave", "파일 복사 중 오류 발생: ${e.message}")
    }
}
// 권한 요청
@Composable
fun RequestPermissions(onResult: (Boolean) -> Unit) {
    val permissions = arrayOf(android.Manifest.permission.RECORD_AUDIO)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        onResult(result.all { it.value })
    }
    LaunchedEffect(Unit) {
        launcher.launch(permissions)
    }
}
