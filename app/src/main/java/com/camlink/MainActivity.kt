package com.camlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.*
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.*
import kotlin.concurrent.thread

// ---- AYARLAR ----
const val PORT = 5000
const val W = 1920
const val H = 1080
const val FPS = 30
const val BITRATE = 40_000_000   // 40 Mbit/s: gözle kayıpsız. WiFi takılırsa 20_000_000 yap.

class MainActivity : Activity() {
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var encThread: Thread? = null
    @Volatile private var client: Socket? = null
    @Volatile private var config: ByteArray? = null
    @Volatile private var running = false
    private var useFront = false
    private lateinit var status: TextView
    private lateinit var camThread: HandlerThread
    private lateinit var camHandler: Handler

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camThread = HandlerThread("cam").also { it.start() }
        camHandler = Handler(camThread.looper)

        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        status = TextView(this).apply { textSize = 18f }
        val bStart = Button(this).apply {
            text = "Başlat / Durdur"
            setOnClickListener { if (running) stopAll() else ensureStart() }
        }
        val bSwap = Button(this).apply {
            text = "Ön / Arka kamera"
            setOnClickListener { val was = running; stopAll(); useFront = !useFront; if (was) ensureStart() }
        }
        l.addView(status); l.addView(bStart); l.addView(bSwap)
        setContentView(l)
        ui("Hazır. Başlat'a bas.\nWiFi IP: ${localIps()}\nPort: $PORT")
        startServer()
    }

    private fun ui(msg: String) = runOnUiThread { status.text = msg }

    private fun localIps(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it is Inet4Address }.joinToString { it.hostAddress ?: "" }
    } catch (e: Exception) { "?" }

    private fun ensureStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startAll()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startAll() else ui("Kamera izni gerekli")
    }

    // PC bağlantısı için TCP sunucusu (WiFi ve USB/adb forward ikisi de buraya bağlanır)
    private fun startServer() = thread {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            while (!ss.isClosed) {
                val s = ss.accept()
                s.tcpNoDelay = true
                client?.let { try { it.close() } catch (_: Exception) {} }
                try { config?.let { s.getOutputStream().write(it) } } catch (_: Exception) {}
                client = s
                try {
                    codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                } catch (_: Exception) {}
                ui("PC bağlandı: ${s.inetAddress.hostAddress}")
            }
        } catch (e: Exception) { ui("Sunucu hatası: $e") }
    }

    private fun send(d: ByteArray) {
        val s = client ?: return
        try { s.getOutputStream().write(d) }
        catch (e: Exception) { try { s.close() } catch (_: Exception) {}; client = null; ui("PC bağlantısı koptu") }
    }

    @SuppressLint("MissingPermission")
    private fun startAll() {
        if (running) return
        try {
            running = true
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val want = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
            } ?: mgr.cameraIdList[0]

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface: Surface = c.createInputSurface()
            c.start()
            codec = c

            encThread = thread {
                val info = MediaCodec.BufferInfo()
                while (running) {
                    val i = try { c.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) { break }
                    if (i >= 0) {
                        val buf = c.getOutputBuffer(i)!!
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(data)
                        c.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = data
                        send(data)
                    }
                }
            }

            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    camera = cd
                    val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    @Suppress("DEPRECATION")
                    cd.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            s.setRepeatingRequest(req.build(), null, camHandler)
                            ui("Yayında\nWiFi IP: ${localIps()}  Port: $PORT")
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { ui("Oturum kurulamadı") }
                    }, camHandler)
                }
                override fun onDisconnected(cd: CameraDevice) { cd.close() }
                override fun onError(cd: CameraDevice, e: Int) { cd.close(); ui("Kamera hatası: $e") }
            }, camHandler)
        } catch (e: Exception) { ui("Hata: $e"); stopAll() }
    }

    private fun stopAll() {
        running = false
        try { encThread?.join(500) } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        session = null; camera = null; codec = null; config = null
        ui("Durdu.\nWiFi IP: ${localIps()}  Port: $PORT")
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
}
