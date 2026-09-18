package dev.tommy.foldshell

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.lang.reflect.Proxy

// Dual-panel fold transition simulator. The activity window renders the
// outgoing panel; a Presentation on the secondary display renders the
// arriving panel. One openFraction drives both, so they stay synchronized.
class FoldSimActivity : Activity(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val dm by lazy { getSystemService(DisplayManager::class.java) }
    private val sm by lazy { getSystemService(SensorManager::class.java) }

    private lateinit var main: PanelSimView
    private lateinit var hint: android.widget.TextView
    private lateinit var progress: android.widget.TextView
    private var presentation: Presentation? = null
    private var presView: PanelSimView? = null

    private var onInner = false          // which physical panel hosts us
    private var hingeDeg = -1f
    private var manualHoldUntil = 0L
    private var didSeedFraction = false
    private var targetFraction = 0f      // what the sensor last reported
    private var displayedFraction = 0f   // eased toward target each frame
    private var chasing = false
    private var lastChaseAt = 0L
    private var lastHingeLog = 0L
    private var concurrentOn = false
    private var watchedSensors = listOf<Sensor>()
    private var downX = 0f
    private var downFraction = 0f
    private var appBmp: Bitmap? = null
    private var lockBmp: Bitmap? = null
    private var appBmpBlur: Bitmap? = null
    private var lockBmpBlur: Bitmap? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { handler.post { syncPresentation() } }
        override fun onDisplayRemoved(id: Int) { handler.post { syncPresentation() } }
        override fun onDisplayChanged(id: Int) { handler.post { syncPresentation() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        main = PanelSimView(this)
        hint = android.widget.TextView(this).apply {
            text = "Fold slowly — hinge angle drives the transition (or drag sideways to scrub).\nBlack wedges + blur + dim = surface rotating out of the fixed projection."
            setTextColor(android.graphics.Color.argb(220, 255, 255, 255))
            textSize = 14f
            setPadding(40, 30, 40, 10)
        }
        progress = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.argb(200, 255, 255, 255))
            textSize = 14f
            setPadding(40, 0, 40, 10)
        }
        val root = android.widget.FrameLayout(this).apply {
            addView(main)
            val col = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(hint); addView(progress)
            }
            addView(col)
        }
        setContentView(root)
        dm.registerDisplayListener(displayListener, handler)
        subscribeSensors()
        registerDeviceStateCallback()
        root.setOnTouchListener { _, e -> onScrub(e); true }
        postSetup()
    }

    override fun onDestroy() {
        super.onDestroy()
        dm.unregisterDisplayListener(displayListener)
        watchedSensors.forEach { sm.unregisterListener(this, it) }
        presentation?.dismiss()
        DeviceStateBinder.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) postSetup()
    }

    // --- layout / roles -------------------------------------------------

    private fun postSetup() {
        val w = main.width; val h = main.height
        if (w == 0 || h == 0) { handler.postDelayed({ postSetup() }, 100); return }
        onInner = display?.let { it.mode.physicalWidth > 1500 } ?: (w > 1500)
        main.role = if (onInner) PanelSimView.Role.OUT_INNER else PanelSimView.Role.OUT_COVER
        // cover hinge is on the left edge; inner's rotating left half has the
        // crease on its right edge
        main.hingeLeft = !onInner
        appBmp = SimContent.appBitmap(w, h)
        appBmpBlur = SimContent.blurred(appBmp!!)
        main.bitmap = appBmp; main.blurBitmap = appBmpBlur
        // Seed the rest position once; afterwards the hinge (or scrub) owns
        // progress — device-state callbacks must not slam it back mid-fold.
        if (!didSeedFraction) {
            didSeedFraction = true
            setFractionImmediate(if (onInner) 1f else 0f)
        }
        syncPresentation()
        log("SETUP onInner=$onInner ${w}x$h")
    }

    private fun syncPresentation() {
        val secondary = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (secondary == null) {
            if (presentation != null) { presentation?.dismiss(); presentation = null; presView = null }
            return
        }
        if (presentation != null) return
        try {
            val v = PanelSimView(this)
            v.role = PanelSimView.Role.ARRIVE
            v.hingeLeft = true
            val sw = secondary.mode.physicalWidth; val sh = secondary.mode.physicalHeight
            val bmp = if (sw > 1500) {
                if (appBmp == null || appBmp!!.width != sw) appBmp = SimContent.appBitmap(sw, sh)
                appBmp!!
            } else {
                if (lockBmp == null) lockBmp = SimContent.lockBitmap(sw, sh)
                lockBmp!!
            }
            v.bitmap = bmp
            v.blurBitmap = if (sw > 1500) {
                if (appBmpBlur == null) appBmpBlur = SimContent.blurred(bmp); appBmpBlur
            } else {
                if (lockBmpBlur == null) lockBmpBlur = SimContent.blurred(bmp); lockBmpBlur
            }
            v.openFraction = main.openFraction
            presentation = object : Presentation(this, secondary) {
                override fun onCreate(b: Bundle?) { super.onCreate(b); setContentView(v) }
            }.also { it.show() }
            presView = v
            log("PRES on id=${secondary.displayId} ${sw}x$sh")
        } catch (t: Throwable) { log("PRES fail ${t.cause ?: t}") }
    }

    // --- concurrent device state ----------------------------------------

    private fun ensureConcurrent() {
        if (concurrentOn) return
        val want = if (onInner) DeviceStateBinder.CONCURRENT_INNER else DeviceStateBinder.CONCURRENT_OUTER
        val result = DeviceStateBinder.request(want)
        log("REQ $want $result")
        // requestState persists until canceled; "already been made" also means held
        concurrentOn = result.startsWith("ok") || result.contains("already been made")
    }

    private fun releaseConcurrent() {
        if (!concurrentOn) return
        concurrentOn = false
        log("REQ cancel ${DeviceStateBinder.cancel()}")
    }

    private fun registerDeviceStateCallback() {
        try {
            val mgr = getSystemService("device_state") ?: return
            val cbIf = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(cbIf)) { _, m, args ->
                log("DEVSTATE ${m.name} ${args?.joinToString() ?: ""}")
                if (m.name == "onDeviceStateChanged" && args != null) {
                    val id = Regex("identifier=(\\d+)").find(args[0].toString())
                        ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val want = if (onInner) DeviceStateBinder.CONCURRENT_INNER
                    else DeviceStateBinder.CONCURRENT_OUTER
                    if (id >= 0 && id != want) concurrentOn = false
                }
                handler.post { onDeviceStateMaybeChanged() }
                null
            }
            mgr.javaClass.getMethod("registerCallback", java.util.concurrent.Executor::class.java, cbIf)
                .invoke(mgr, mainExecutor, proxy)
        } catch (t: Throwable) { log("DEVSTATE cb ${t.cause ?: t}") }
    }

    private fun onDeviceStateMaybeChanged() {
        // Physical state may have flipped the default display; re-derive roles.
        handler.postDelayed({ postSetup() }, 150)
    }

    // --- sensors / progress ----------------------------------------------

    private fun subscribeSensors() {
        watchedSensors = sm.getSensorList(Sensor.TYPE_ALL).filter {
            val n = "${it.name} ${it.stringType}".lowercase()
            it.type == Sensor.TYPE_HINGE_ANGLE || it.type == Sensor.TYPE_GYROSCOPE ||
                n.contains("fold") || n.contains("hinge")
        }
        watchedSensors.forEach {
            val ok = sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            log("SENSOR ${it.type} ${it.name} ok=$ok delay=${it.minDelay}")
        }
        if (watchedSensors.isEmpty()) log("SENSOR none")
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_HINGE_ANGLE ||
            e.sensor.stringType == "com.samsung.sensor.folding_angle") {
            val deg = e.values[0]
            val now = System.currentTimeMillis()
            if (deg != hingeDeg || now - lastHingeLog > 400) {
                lastHingeLog = now
                log("HINGE ${e.sensor.name}=$deg")
            }
            if (deg != hingeDeg) {
                hingeDeg = deg
                if (now > manualHoldUntil) {
                    setOpenFraction((deg / 180f).coerceIn(0f, 1f))
                }
            }
        } else if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // Subscribed for diagnostics; if the hinge sensors prove discrete
            // this stream is the fallback continuous-progress source.
        } else if (System.currentTimeMillis() - lastHingeLog > 400) {
            lastHingeLog = System.currentTimeMillis()
            log("VSENSOR ${e.sensor.name} ${e.values.take(3).joinToString()}")
        }
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {}

    private fun setOpenFraction(f: Float) {
        // Sensor entry point: chase the target smoothly so discrete hinge
        // events (0/90/180) still animate instead of snapping.
        targetFraction = f.coerceIn(0f, 1f)
        if (!chasing && kotlin.math.abs(targetFraction - displayedFraction) > 0.001f) {
            chasing = true
            lastChaseAt = 0L
            handler.post(chaseStep)
        }
        applyDerived()
    }

    private fun setFractionImmediate(f: Float) {
        // Manual scrub: the finger is the sensor — no lag.
        targetFraction = f.coerceIn(0f, 1f)
        displayedFraction = targetFraction
        chasing = false
        applyDerived()
    }

    private val chaseStep = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (lastChaseAt == 0L) 16f else (now - lastChaseAt).toFloat()
            lastChaseAt = now
            val k = 1f - kotlin.math.exp(-dt / 140f)
            displayedFraction += (targetFraction - displayedFraction) * k
            if (kotlin.math.abs(targetFraction - displayedFraction) < 0.0015f) {
                displayedFraction = targetFraction
                chasing = false
                applyDerived()
                return
            }
            applyDerived()
            handler.postDelayed(this, 16)
        }
    }

    private fun applyDerived() {
        val f = displayedFraction
        main.openFraction = f
        presView?.openFraction = f
        val role = if (onInner) "inner-out(closing)" else "cover-out(opening)"
        val hingeText = if (hingeDeg >= 0) "${"%.0f".format(hingeDeg)}°" else "—"
        progress.text = "open=${"%.2f".format(f)} hinge=$hingeText " +
            "this=$role other=${if (presView != null) "live" else "off"}"
        // hold concurrent state while between rest positions
        if (targetFraction in 0.03f..0.97f) ensureConcurrent() else releaseConcurrent()
    }

    // --- manual scrub -----------------------------------------------------

    private fun onScrub(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downFraction = main.openFraction }
            MotionEvent.ACTION_MOVE -> {
                val f = (downFraction + (e.x - downX) / main.width).coerceIn(0f, 1f)
                manualHoldUntil = System.currentTimeMillis() + 2500
                setFractionImmediate(f)
            }
        }
    }

    private fun log(s: String) { android.util.Log.i("FoldSim", s) }
}
