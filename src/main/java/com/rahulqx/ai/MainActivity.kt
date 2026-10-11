package com.rahulqx.ai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private val REQ_PROJ = 1001
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnStart).setOnClickListener { startFlow() }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
    }
    private fun startFlow() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "আগে overlay permission দাও", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_PROJ && res == RESULT_OK && data != null) {
            val i = Intent(this, ScanService::class.java)
            i.putExtra("code", res)
            i.putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "RAHUL QX AI চালু — বাবেল টেনে রাখো", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

class ScanService : Service() {
    private var proj: MediaProjection? = null
    private var reader: ImageReader? = null
    private var disp: VirtualDisplay? = null
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var tts: TextToSpeech? = null
    private var lastSpeak = 0L
    private var lastFrame = 0L
    private var lastVerdict = ""
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else
                startForeground(1, buildNotif())
        } catch (e: Exception) {
            try { startForeground(1, buildNotif()) } catch (_: Exception) {}
        }
        initTts()
        showBubble()
        setBubble("QX AI\nSTARTING...", 0xF23A2F10.toInt())
        speak("RAHUL QX AI prostut")
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (code != 0 && data != null) {
            try { startScan(code, data) }
            catch (e: Exception) { setBubble("QX AI\nERR: " + (e.message ?: "scan"), 0xF23D1520.toInt()) }
        } else stopSelf()
        return START_NOT_STICKY
    }

    private fun setBubble(t: String, color: Int) {
        handler.post {
            bubble?.text = t
            bubble?.setBackgroundColor(color)
        }
    }

    private fun buildNotif(): Notification {
        val ch = "qx"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(ch, "QX AI", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
        b.setContentTitle("RAHUL QX AI চালু আছে")
            .setContentText("বাবেল ট্যাপ = বন্ধ")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
        return b.build()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r1 = tts?.setLanguage(Locale("bn", "IN"))
                if (r1 == TextToSpeech.LANG_MISSING_DATA || r1 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val r2 = tts?.setLanguage(Locale("hi", "IN"))
                    if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.ENGLISH)
                    }
                }
            }
        }
    }

    private fun speak(t: String) {
        tts?.stop()
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "qx")
    }

    private fun showBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this)
        tv.text = "QX AI\nREADY"
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 12f
        tv.setBackgroundColor(0xF2121830.toInt())
        tv.setPadding(26, 18, 26, 18)
        if (Build.VERSION.SDK_INT >= 21) tv.elevation = 12f
        val type = if (Build.VERSION.SDK_INT >= 26) LayoutParams.TYPE_APPLICATION_OVERLAY else LayoutParams.TYPE_PHONE
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, type,
            LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 50; lp.y = 260
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var dragged = false
        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX; dy = ev.rawY; sx = lp.x; sy = lp.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - dx) > 24 || abs(ev.rawY - dy) > 24) dragged = true
                    lp.x = sx + (ev.rawX - dx).toInt()
                    lp.y = sy + (ev.rawY - dy).toInt()
                    wm?.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) stopSelf(); true }
                else -> false
            }
        }
        bubble = tv
        wm?.addView(tv, lp)
    }

    private fun startScan(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data)
        if (proj == null) {
            setBubble("QX AI\nERR: projection null", 0xF23D1520.toInt())
            return
        }
        setBubble("QX AI\nSCANNING", 0xF2121830.toInt())
        proj?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = 360
        val h = maxOf(200, 360 * dm.heightPixels / maxOf(1, dm.widthPixels))
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        disp = proj?.createVirtualDisplay("qx", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        reader?.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (now - lastFrame >= 3000) {
                lastFrame = now
                val p = img.planes[0]
                val rowW = p.rowStride / p.pixelStride
                val bmp = Bitmap.createBitmap(rowW, img.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(p.buffer)
                img.close()
                handler.post { analyze(bmp) }
            } else img.close()
        }, handler)
        speak("স্ক্যান শুরু হয়েছে। চার্টের স্ক্রিনে নিয়ে যাও।")
    }

    private fun analyze(bmp0: Bitmap) {
        val w = 200
        val h = maxOf(40, bmp0.height * w / maxOf(1, bmp0.width))
        val bmp = Bitmap.createScaledBitmap(bmp0, w, h, false)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yMax = (h * 0.62).toInt()
        val xMin = (w * 0.04).toInt()
        val xMax = (w * 0.96).toInt()
        val mid = (xMin + xMax) / 2
        var lg = 0; var lr = 0; var rg = 0; var rr = 0
        for (col in xMin until xMax) {
            for (row in 0 until yMax) {
                val c = px[row * w + col]
                val R = c shr 16 and 255
                val G = c shr 8 and 255
                val B = c and 255
                val green = G > R + 25 && G > B + 10
                val red = R > G + 25 && R > B + 10
                if (col < mid) { if (green) lg++; if (red) lr++ }
                else { if (green) rg++; if (red) rr++ }
            }
        }
        val tot = lg + lr + rg + rr
        val area = (xMax - xMin) * yMax
        val now = System.currentTimeMillis()
        if (tot.toFloat() / area < 0.02f) {
            bubble?.text = "QX AI\nSEARCHING..."
            bubble?.setBackgroundColor(0xF2121830.toInt())
            if (now - lastSpeak > 12000) {
                speak("আমি কোনো চার্ট দেখতে পাচ্ছি না। চার্টের স্ক্রিনে নিয়ে যাও।")
                lastSpeak = now; lastVerdict = "none"
            }
            return
        }
        val ls = rg - rr
        val verdict: String
        val msg: String
        when {
            ls > 10 -> { verdict = "UP"; msg = "আপ। উপরে যাওয়ার গতি বেশি। পাঁচ মিনিটের জন্য।" }
            ls < -10 -> { verdict = "DOWN"; msg = "ডাউন। নিচে নামার চাপ বেশি। পাঁচ মিনিটের জন্য।" }
            else -> { verdict = "NEUTRAL"; msg = "মিক্সড। এখন ট্রেড না করাই ভালো।" }
        }
        when (verdict) {
            "UP" -> { bubble?.text = "QX AI\nUP"; bubble?.setBackgroundColor(0xF20E3D2C.toInt()) }
            "DOWN" -> { bubble?.text = "QX AI\nDOWN"; bubble?.setBackgroundColor(0xF23D1520.toInt()) }
            else -> { bubble?.text = "QX AI\nWAIT"; bubble?.setBackgroundColor(0xF23A2F10.toInt()) }
        }
        if (verdict != lastVerdict || now - lastSpeak > 20000) {
            speak(msg); lastSpeak = now; lastVerdict = verdict
        }
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { disp?.release(); reader?.close(); proj?.stop() } catch (_: Exception) {}
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
