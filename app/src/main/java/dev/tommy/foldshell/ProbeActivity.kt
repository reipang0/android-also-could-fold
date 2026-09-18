package dev.tommy.foldshell

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.Proxy

// App-layer capability probe: hinge sensor resolution, whether a normal app
// can reach DeviceStateManager (expected: hidden API, verify), second-panel
// Display exposure under concurrent states, and Presentation rendering.
class ProbeActivity : Activity(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private val lines = ArrayDeque<String>()
    private var presentation: Presentation? = null
    private var dsm: Any? = null
    private var watchedSensors = listOf<Sensor>()
    private var lastHinge = -1f
    private var hingeEvents = 0

    private val dm by lazy { getSystemService(DisplayManager::class.java) }
    private val sm by lazy { getSystemService(SensorManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { log("DISPLAY +$id ${describe(id)}") }
        override fun onDisplayRemoved(id: Int) { log("DISPLAY -$id") }
        override fun onDisplayChanged(id: Int) { log("DISPLAY ~$id ${describe(id)}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusView = TextView(this).apply { textSize = 13f; setPadding(24, 16, 24, 8) }
        logView = TextView(this).apply { textSize = 11f; setPadding(24, 8, 24, 24) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow(
                "REQ 4" to { requestDeviceState(4) },
                "REQ 5" to { requestDeviceState(5) },
                "CANCEL" to { cancelDeviceState() }))
            addView(buttonRow(
                "DISPLAYS" to { enumDisplays() },
                "PRESENT 2ND" to { showPresentation() },
                "HIDE PRES" to { hidePresentation() }))
            addView(buttonRow(
                "SENSORS" to { enumSensors() },
                "STATE" to { dumpDeviceState() },
                "SWEEP" to { sweep() }))
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        dm.registerDisplayListener(displayListener, handler)
        subscribeSensors()
        try {
            dsm = getSystemService("device_state")
            log("DEVSTATE service=$dsm")
            registerDeviceStateCallback()
        } catch (t: Throwable) { log("DEVSTATE service fail $t") }
        log("PROBE ready sdk=${Build.VERSION.SDK_INT}")
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        dm.unregisterDisplayListener(displayListener)
        watchedSensors.forEach { sm.unregisterListener(this, it) }
        cancelDeviceState()
        hidePresentation()
    }

    private fun buttonRow(vararg specs: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this)
        specs.forEach { (label, action) ->
            row.addView(Button(this).apply {
                text = label; textSize = 11f
                setOnClickListener { try { action() } catch (t: Throwable) { log("ERR $label: ${t.cause ?: t}") } }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun subscribeSensors() {
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        watchedSensors = all.filter {
            val n = "${it.name} ${it.stringType}".lowercase()
            it.type == Sensor.TYPE_HINGE_ANGLE || it.type == Sensor.TYPE_GYROSCOPE ||
                it.type == Sensor.TYPE_GRAVITY ||
                n.contains("hinge") || n.contains("fold") || n.contains("posture") || n.contains("flip")
        }
        watchedSensors.forEach { s ->
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, handler)
            log("SENSOR sub type=${s.type} ${s.name} (${s.stringType})")
        }
        if (watchedSensors.isEmpty()) log("SENSOR none found")
    }

    private fun enumSensors() {
        sm.getSensorList(Sensor.TYPE_ALL).forEach { s ->
            log("SENSOR type=${s.type} ${s.name} vendor=${s.vendor}")
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_HINGE_ANGLE -> {
                hingeEvents++
                if (e.values[0] != lastHinge) {
                    lastHinge = e.values[0]
                    log("HINGE ${e.values[0]} (#$hingeEvents)")
                }
            }
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY -> {}
            else -> log("VSENSOR ${e.sensor.name} ${e.values.take(3).joinToString()}")
        }
        render()
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {}

    // --- DeviceStateManager via reflection: verify whether a normal app can
    // reach it (hidden API policy likely blocks; the probe reports the exact
    // failure so we know whether the app's own ADB channel is required).

    private fun registerDeviceStateCallback() {
        val mgr = dsm ?: return
        try {
            val cbIf = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(cbIf)) { _, m, args ->
                log("DEVSTATE cb ${m.name} ${args?.joinToString() ?: ""}"); null
            }
            mgr.javaClass.getMethod("registerCallback", java.util.concurrent.Executor::class.java, cbIf)
                .invoke(mgr, mainExecutor, proxy)
            log("DEVSTATE callback registered")
        } catch (t: Throwable) { log("DEVSTATE cb fail: ${t.cause ?: t}") }
    }

    private var rawBinder: android.os.IBinder? = null
    private val token = android.os.Binder()
    private var overrideCode = -1

    private fun rawBinder(): android.os.IBinder? {
        if (rawBinder != null) return rawBinder
        try {
            rawBinder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "device_state") as android.os.IBinder
            log("BINDER raw desc=${rawBinder?.interfaceDescriptor}")
        } catch (t: Throwable) { log("BINDER fail: ${t.cause ?: t}") }
        return rawBinder
    }

    // Manual AIDL marshal: requestBaseStateOverride(IBinder, int, int).
    // Transaction code is discovered by sweeping (server-side methods differ
    // per build); a wrong code just fails the remote call harmlessly.
    private fun transactOverride(state: Int, code: Int) {
        val b = rawBinder() ?: return
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(b.interfaceDescriptor ?: "android.hardware.devicestate.IDeviceStateManager")
            data.writeStrongBinder(token)
            data.writeInt(state)
            data.writeInt(0)
            val ok = b.transact(code, data, reply, 0)
            try { reply.readException(); log("TX code=$code ok=$ok") }
            catch (re: Exception) { log("TX code=$code ok=$ok remote=${re.message}") }
        } catch (t: Throwable) { log("TX code=$code fail ${t.cause ?: t}") }
        finally { data.recycle(); reply.recycle() }
    }

    private fun transactNoArg(code: Int) {
        val b = rawBinder() ?: return
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(b.interfaceDescriptor ?: "android.hardware.devicestate.IDeviceStateManager")
            val ok = b.transact(code, data, reply, 0)
            try { reply.readException(); log("TX0 code=$code ok=$ok") }
            catch (re: Exception) { log("TX0 code=$code ok=$ok remote=${re.message}") }
        } catch (t: Throwable) { log("TX0 code=$code fail ${t.cause ?: t}") }
        finally { data.recycle(); reply.recycle() }
    }

    private fun sweep() {
        log("SWEEP start")
        Thread {
            for (code in 1..14) {
                val r = Runnable {
                    transactOverride(4, code)
                    handler.postDelayed({ dumpDeviceState() }, 250)
                    handler.postDelayed({ transactNoArg(code); dumpDeviceState() }, 550)
                }
                handler.post(r)
                Thread.sleep(900)
            }
            handler.post { log("SWEEP done") }
        }.start()
    }

    // Discovered on SM-F976N: code 3 = requestState(token, state, flags) —
    // works from a top app without CONTROL_DEVICE_STATE. code 4 =
    // cancelStateRequest(token). Base-state override (codes 5/6) is
    // system/shell only.
    private fun requestDeviceState(state: Int) {
        rawBinder() ?: return
        transactOverride(state, 3)
        handler.postDelayed({ enumDisplays() }, 500)
    }

    private fun cancelDeviceState() {
        val b = rawBinder() ?: return
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(b.interfaceDescriptor ?: "android.hardware.devicestate.IDeviceStateManager")
            data.writeStrongBinder(token)
            b.transact(4, data, reply, 0)
            try { reply.readException(); log("REQ cancel sent") }
            catch (re: Exception) { log("REQ cancel remote=${re.message}") }
        } catch (t: Throwable) { log("REQ cancel: ${t.cause ?: t}") }
        finally { data.recycle(); reply.recycle() }
        handler.postDelayed({ enumDisplays() }, 500)
    }

    private fun dumpDeviceState() {
        val mgr = dsm ?: run { log("DEVSTATE none"); return }
        try {
            val info = mgr.javaClass.getMethod("getDeviceStateInfo").invoke(mgr)
            val base = info.javaClass.getMethod("getBaseState").invoke(info)
            val cur = info.javaClass.getMethod("getCurrentState").invoke(info)
            log("DEVSTATE base=${stateName(base)} current=${stateName(cur)}")
        } catch (t: Throwable) { log("DEVSTATE info: ${t.cause ?: t}") }
    }

    private fun stateName(s: Any?): String {
        if (s == null) return "null"
        val id = s.javaClass.getMethod("getIdentifier").invoke(s)
        val name = s.javaClass.getMethod("getName").invoke(s)
        return "$id:$name"
    }

    // --- Displays / Presentation ---

    private fun describe(id: Int): String {
        val d = dm.getDisplay(id) ?: return "gone"
        return "${d.name} flags=0x${Integer.toHexString(d.flags)} state=${d.state}"
    }

    private fun enumDisplays() {
        val ds = dm.displays
        log("DISPLAYS count=${ds.size}")
        ds.forEach { d ->
            val m = d.mode
            log("  id=${d.displayId} ${d.name} ${m.physicalWidth}x${m.physicalHeight} flags=0x${Integer.toHexString(d.flags)} state=${d.state}")
        }
    }

    private fun showPresentation() {
        val targets = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        if (targets.isEmpty()) { log("PRES no secondary display"); return }
        targets.forEach { d ->
            try {
                presentation?.dismiss()
                presentation = object : Presentation(this, d) {
                    override fun onCreate(b: Bundle?) {
                        super.onCreate(b)
                        setContentView(View(context).apply {
                            background = GradientDrawable().apply { setColor(Color.rgb(220, 40, 40)) }
                        })
                    }
                }.also { it.show() }
                log("PRES shown on id=${d.displayId} ${d.name}")
            } catch (t: Throwable) { log("PRES fail id=${d.displayId}: ${t.cause ?: t}") }
        }
    }

    private fun hidePresentation() {
        presentation?.dismiss(); presentation = null; log("PRES hidden")
    }

    private fun log(s: String) {
        lines.addLast(s)
        while (lines.size > 60) lines.removeFirst()
        render()
        android.util.Log.i("FoldProbe", s)
    }

    private fun render() {
        statusView.text = "hinge=$lastHinge events=$hingeEvents pres=${presentation != null}"
        logView.text = lines.joinToString("\n")
    }
}
