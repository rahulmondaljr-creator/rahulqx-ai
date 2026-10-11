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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val REQ_PROJ = 1001
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnStart).setOnClickListener { startFlow() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testVoice() }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
    }
    private fun testVoice() {
        Toast.makeText(this, "Voice test...", Toast.LENGTH_SHORT).show()
        lateinit var t: TextToSpeech
        t = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r1 = t.setLanguage(Locale("bn", "IN"))
                if (r1 == TextToSpeech.LANG_MISSING_DATA || r1 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val r2 = t.setLanguage(Locale("hi", "IN"))
                    if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED)
                        t.setLanguage(Locale.ENGLISH)
                }
                t.speak("পরীক্ষা কণ্ঠস্বর কাজ করছে", TextToSpeech.QUEUE_FLUSH, null, "tv")
            } else Toast.makeText(this, "TTS engine nai", Toast.LENGTH_LONG).show()
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
            i.putExtra("code", res); i.putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "RAHUL QX AI চালু", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

class ScanService : Service() {
    private var proj: MediaProjection? = null
    private var reader: ImageReader? = null
    private var disp: VirtualDisplay? = null
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var tvMarket: TextView? = null
    private var tvVerdict: TextView? = null
    private var tvSub: TextView? = null
    private var tts: TextToSpeech? = null
    private var gotFrame = false
    private var lastFrame = 0L
    private var lastOcr = 0L
    private var lastDeep = 0L
    private var lastSpeak = 0L
    private var curMarket = "--"
    private var curPrice = "--"
    private var lastVerdictTxt = ""
    private var lockMarket = ""
    private var lockUntil = 0L
    private var lockDir = ""
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val animColors = intArrayOf(0xE6121830.toInt(), 0xE60E3D2C.toInt(), 0xE62A1F4D.toInt(), 0xE63A2F10.toInt())
    private var animIdx = 0
    private val scanLabels = arrayOf("ANALYZING SMA...", "ANALYZING EMA...", "ANALYZING BOLLINGER...", "ANALYZING MACD...", "ANALYZING STOCHASTIC...", "ANALYZING ATR...")
    private var scanIdx = 0
    private var scanning = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, buildNotif())
        } catch (e: Exception) { try { startForeground(1, buildNotif()) } catch (_: Exception) {} }
        initTts(); showPanel()
        setPanel("QX AI", "STARTING...", "--", 0xF23A2F10.toInt())
        speak("RAHUL QX AI prostut")
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (code != 0 && data != null) { try { startScan(code, data) } catch (e: Exception) { setPanel("QX AI", "ERR: " + (e.message ?: "scan"), "--", 0xF23D1520.toInt()) } }
        else stopSelf()
        return START_NOT_STICKY
    }

    private fun buildNotif(): Notification {
        val ch = "qx"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(ch, "QX AI", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
        b.setContentTitle("RAHUL QX AI চালু আছে").setContentText("প্যানেলের X চাপলে বন্ধ").setSmallIcon(android.R.drawable.stat_notify_sync)
        return b.build()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r1 = tts?.setLanguage(Locale("bn", "IN"))
                if (r1 == TextToSpeech.LANG_MISSING_DATA || r1 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val r2 = tts?.setLanguage(Locale("hi", "IN"))
                    if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.ENGLISH)
                }
            }
        }
    }
    private fun speak(t: String) { tts?.stop(); tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "qx") }

    private fun bg(c: Int): GradientDrawable { val g = GradientDrawable(); g.setColor(c); g.cornerRadius = 18f; g.setStroke(3, Color.parseColor("#00E5A0")); return g }

    private fun showPanel() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(20, 14, 20, 14)
        root.background = bg(0xF2121830.toInt())
        val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL
        val title = TextView(this); title.text = "⚡ QX AI PRO"; title.setTextColor(0xFF00E5A0.toInt()); title.textSize = 12f; title.setTypeface(null, android.graphics.Typeface.BOLD)
        val x = TextView(this); x.text = "  ✕  "; x.setTextColor(0xFFFF5B6A.toInt()); x.textSize = 13f
        x.setOnClickListener { stopSelf() }
        head.addView(title); head.addView(x)
        tvMarket = TextView(this); tvMarket?.text = "--"; tvMarket?.setTextColor(0xFFFFFFFF.toInt()); tvMarket?.textSize = 12f
        tvVerdict = TextView(this); tvVerdict?.text = "WAIT"; tvVerdict?.setTextColor(0xFF8FA3C0.toInt()); tvVerdict?.textSize = 16f; tvVerdict?.setTypeface(null, android.graphics.Typeface.BOLD)
        tvSub = TextView(this); tvSub?.text = "chart screen e nao"; tvSub?.setTextColor(0xFF8FA3C0.toInt()); tvSub?.textSize = 10f
        root.addView(head); root.addView(tvMarket); root.addView(tvVerdict); root.addView(tvSub)
        val type = if (Build.VERSION.SDK_INT >= 26) LayoutParams.TYPE_APPLICATION_OVERLAY else LayoutParams.TYPE_PHONE
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, type, LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 40; lp.y = 240
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var dragged = false
        root.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX; dy = ev.rawY; sx = lp.x; sy = lp.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> { if (abs(ev.rawX - dx) > 24 || abs(ev.rawY - dy) > 24) dragged = true; lp.x = sx + (ev.rawX - dx).toInt(); lp.y = sy + (ev.rawY - dy).toInt(); wm?.updateViewLayout(v, lp); true }
                else -> false
            }
        }
        panel = root
        wm?.addView(root, lp)
    }

    private fun setPanel(market: String, verdict: String, sub: String, color: Int) {
        handler.post {
            tvMarket?.text = market
            tvVerdict?.text = verdict
            tvSub?.text = sub
            panel?.background = bg(color)
        }
    }

    private fun startScan(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data)
        if (proj == null) { setPanel("QX AI", "ERR: projection null", "--", 0xF23D1520.toInt()); return }
        proj?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = 480
        val h = max(320, 480 * dm.heightPixels / max(1, dm.widthPixels))
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        disp = proj?.createVirtualDisplay("qx", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        schedulePoll()
        handler.postDelayed({ if (!gotFrame) { setPanel(curMarket, "NO FRAMES", "--", 0xF23D1520.toInt()); speak("কোনো ফ্রেম পাইনি") } }, 15000)
        handler.postDelayed({ tick() }, 1000)
        speak("স্ক্যান শুরু।")
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (lockUntil > now) {
            val left = (lockUntil - now) / 1000
            tvSub?.text = lockDir + " active · " + (left / 60) + "m " + (left % 60) + "s left"
        }
        if (scanning) {
            scanIdx = (scanIdx + 1) % scanLabels.size
            animIdx = (animIdx + 1) % animColors.size
            tvVerdict?.text = scanLabels[scanIdx]
            panel?.background = bg(animColors[animIdx])
        }
        handler.postDelayed({ tick() }, 700)
    }

    private fun schedulePoll() {
        handler.postDelayed({
            try {
                val img = reader?.acquireLatestImage()
                if (img != null) {
                    gotFrame = true
                    val now = System.currentTimeMillis()
                    if (now - lastFrame >= 3000) {
                        lastFrame = now
                        val p = img.planes[0]
                        val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(p.buffer)
                        img.close()
                        processFrame(bmp)
                        if (now - lastOcr > 6000) { lastOcr = now; runOcr(bmp) }
                    } else img.close()
                }
            } catch (_: Exception) {}
            if (proj != null) schedulePoll()
        }, 1500)
    }

    private fun runOcr(bmp: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bmp, 0)
            recognizer.process(image).addOnSuccessListener { res ->
                val txt = res.text
                val mp = Regex("([A-Z]{3})\\s?/\\s?([A-Z]{3})").find(txt)
                if (mp != null) curMarket = mp.groupValues[1] + "/" + mp.groupValues[2]
                val pr = Regex("\\d{1,3}\\.\\d{4,5}").find(txt)
                if (pr != null) curPrice = pr.value
            }
        } catch (_: Exception) {}
    }

    private fun processFrame(bmp0: Bitmap) {
        val w = 220
        val h = max(80, bmp0.height * w / max(1, bmp0.width))
        val bmp = Bitmap.createScaledBitmap(bmp0, w, h, false)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yMax = (h * 0.62).toInt(); val xMin = (w * 0.04).toInt(); val xMax = (w * 0.96).toInt()
        val mids = ArrayList<Float>(); val ups = ArrayList<Boolean>(); val heights = ArrayList<Float>()
        var col = xMin
        while (col < xMax) {
            var g = 0; var r = 0; var top = -1; var bot = -1
            var c2 = col
            while (c2 < min(col + 14, xMax)) {
                for (row in 0 until yMax) {
                    val cc = px[row * w + c2]
                    val R = cc shr 16 and 255; val G = cc shr 8 and 255; val B = cc and 255
                    if (G > R + 25 && G > B + 10) { g++; if (top < 0) top = row; bot = row }
                    else if (R > G + 25 && R > B + 10) { r++; if (top < 0) top = row; bot = row }
                }
                if (g + r > 4) c2++ else break
            }
            val cw = c2 - col
            if (cw >= 3 && g + r > 8) {
                mids.add(-((top + bot) / 2f)); ups.add(g > r); heights.add((bot - top).toFloat())
                col = c2 + 1
            } else col++
        }
        val now = System.currentTimeMillis()
        if (mids.size < 8) {
            setPanel(curMarket + "  " + curPrice, "NO CHART", "chart kholo", 0xF2121830.toInt())
            if (now - lastSpeak > 12000) { speak("আমি চার্ট দেখছি না"); lastSpeak = now }
            return
        }
        val n = mids.size
        val series = mids.subList(max(0, n - 50), n)
        if (now - lastDeep < 12000) return
        lastDeep = now
        scanning = true
        handler.postDelayed({
            scanning = false
            val v = computeVerdict(series, ups.subList(max(0, n - 50), n), heights.subList(max(0, n - 50), n))
            applyVerdict(v)
        }, 4200)
    }

    private fun sma(v: List<Float>, p: Int): Float { var s = 0f; val st = max(0, v.size - p); for (i in st until v.size) s += v[i]; return s / (v.size - st) }
    private fun emaAll(v: List<Float>, p: Int): List<Float> {
        val k = 2f / (p + 1); val out = ArrayList<Float>(); var e = v[0]
        for (x in v) { e = x * k + e * (1 - k); out.add(e) }
        return out
    }

    private data class Verdict(val txt: String, val dir: String, val expMin: Int, val color: Int, val speak: String)

    private fun computeVerdict(mids: List<Float>, ups: List<Boolean>, heights: List<Float>): Verdict {
        val last = mids.last()
        val ef = emaAll(mids, 5); val es = emaAll(mids, 20)
        val ema12 = emaAll(mids, 12); val ema26 = emaAll(mids, 26)
        val macd = ArrayList<Float>(); for (i in mids.indices) macd.add(ema12[i] - ema26[i])
        val sig = emaAll(macd, 9)
        val hist = macd.last() - sig.last()
        val st = max(0, mids.size - 20); var mean = 0f
        for (i in st until mids.size) mean += mids[i]; mean /= (mids.size - st)
        var sd = 0f; for (i in st until mids.size) sd += (mids[i] - mean) * (mids[i] - mean)
        sd = kotlin.math.sqrt(sd / (mids.size - st))
        val w = min(14, mids.size); var lo = mids.last(); var hi = mids.last()
        for (i in mids.size - w until mids.size) { lo = min(lo, mids[i]); hi = max(hi, mids[i]) }
        val stoch = if (hi > lo) (last - lo) / (hi - lo) * 100 else 50f
        var upc = 0; val w8 = min(8, ups.size)
        for (i in ups.size - w8 until ups.size) if (ups[i]) upc++
        var atr = 0f; for (hh in heights) atr += hh; atr /= max(1, heights.size)
        var score = 0; var dir = "UP"
        if (ef.last() > es.last()) score += 2 else score -= 2
        if (hist > 0) score += 2 else score -= 2
        if (macd.size > 1 && (macd.last() - macd[macd.size - 2]) > 0) score += 1 else score -= 1
        if (last > mean) score += 1 else score -= 1
        if (stoch < 30) score += 1; if (stoch > 70) score -= 1
        if (upc >= w8 - 2) score += 1; if (upc <= 2) score -= 1
        if (score >= 0) dir = "UP" else dir = "DOWN"
        val a = abs(score)
        return when {
            a >= 6 -> Verdict("STRONG " + dir, dir, 15, 0xF20E3D2C.toInt(), if (dir == "UP") "স্ট্রং আপ। পোনেরো মিনিটের জন্য।" else "স্ট্রং ডাউন। পোনেরো মিনিটের জন্য।")
            a >= 4 -> Verdict(dir, dir, 5, 0xF2121830.toInt(), if (dir == "UP") "আপ। পাঁচ মিনিটের জন্য।" else "ডাউন। পাঁচ মিনিটের জন্য।")
            a >= 3 -> Verdict("WEAK " + dir, dir, 1, 0xF23A2F10.toInt(), if (dir == "UP") "দুর্বল আপ। এক মিনিটের জন্য।" else "দুর্বল ডাউন। এক মিনিটের জন্য।")
            else -> Verdict("WAIT", "WAIT", 0, 0xF2121830.toInt(), "মিক্সড। অপেক্ষা করো।")
        }
    }

    private fun applyVerdict(v: Verdict) {
        val now = System.currentTimeMillis()
        val mkt = curMarket
        if (lockUntil > now && lockMarket == mkt) {
            setPanel(mkt + "  " + curPrice, lockDir + " ACTIVE", "signal cholle notun hobe", 0xF23A2F10.toInt())
            return
        }
        if (v.dir == "WAIT") {
            setPanel(mkt + "  " + curPrice, "WAIT / MIXED", "SMA MACD milseni", 0xF2121830.toInt())
            return
        }
        lockMarket = mkt; lockDir = v.dir; lockUntil = now + v.expMin * 60000L + 15000
        setPanel(mkt + "  " + curPrice, v.txt, v.expMin.toString() + " min signal", v.color)
        if (v.txt != lastVerdictTxt || now - lastSpeak > 25000) {
            speak(mkt.replace("/", " ") + "। " + v.speak)
            lastSpeak = now; lastVerdictTxt = v.txt
        }
    }

    override fun onDestroy() {
        try { recognizer.close() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { disp?.release(); reader?.close(); proj?.stop() } catch (_: Exception) {}
        try { panel?.let { wm?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
