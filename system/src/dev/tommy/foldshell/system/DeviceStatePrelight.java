package dev.tommy.foldshell.system;

import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Early panel pre-light during a physical fold. Holds a base-state override
 * (4 = CONCURRENT_INNER_DEFAULT while opening, 5 = CONCURRENT_OUTER_DEFAULT
 * while closing) through the device_state binder so the destination panel is
 * already powered when the handoff happens. The hardware provider re-asserts
 * the real state on every sensor report, so the request is re-issued while it
 * is still needed and released once the physical state catches up. Death of
 * this process releases the token and drops the override on its own. */
final class DeviceStatePrelight {
    private static final int CONCURRENT_INNER = 4, CONCURRENT_OUTER = 5;
    private static final long REASSERT_MS = 300, INFO_MS = 150, STALE_MS = 4000;

    private final Object service;
    private final Method requestBase, cancelBase, getInfo;
    private final IBinder token = new Binder();
    private int desired = -1, committed = -1, physical = -1;
    private FoldMotion.Direction lastDirection;
    private long lastEvidence, nextAssert, nextInfo, interactiveLost;
    private volatile boolean dirty;
    private boolean dead, dumped;

    private DeviceStatePrelight(Object service, Method requestBase, Method cancelBase, Method getInfo) {
        this.service = service; this.requestBase = requestBase;
        this.cancelBase = cancelBase; this.getInfo = getInfo;
    }

    static DeviceStatePrelight create() {
        try {
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "device_state");
            if (binder == null) return null;
            Object service = Class.forName("android.hardware.devicestate.IDeviceStateManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> api = Class.forName("android.hardware.devicestate.IDeviceStateManager");
            // The service rejects state requests until the process registers an
            // IDeviceStateManagerCallback; a raw Binder plus a dynamic proxy is
            // enough to satisfy the AIDL contract.
            Class<?> callbackApi = Class.forName("android.hardware.devicestate.IDeviceStateManagerCallback");
            String descriptor = descriptor(callbackApi);
            final DeviceStatePrelight[] self = new DeviceStatePrelight[1];
            Binder callback = new Binder() {
                @Override protected boolean onTransact(int code, android.os.Parcel data,
                        android.os.Parcel reply, int flags) {
                    if (code == Binder.INTERFACE_TRANSACTION && reply != null) {
                        reply.writeString(descriptor);
                        return true;
                    }
                    DeviceStatePrelight owner = self[0];
                    if (owner != null) owner.dirty = true;
                    return true;
                }
            };
            Object callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackApi.getClassLoader(), new Class[]{callbackApi},
                    (proxy, method, args) -> "asBinder".equals(method.getName()) ? callback : null);
            DeviceStatePrelight prelight = new DeviceStatePrelight(service,
                    api.getMethod("requestBaseStateOverride", IBinder.class, int.class, int.class),
                    api.getMethod("cancelBaseStateOverride"), api.getMethod("getDeviceStateInfo"));
            self[0] = prelight;
            Object info = api.getMethod("registerCallback", callbackApi)
                    .invoke(service, callbackProxy);
            prelight.committed = toId(read(info, "currentState"));
            prelight.physical = toId(read(info, "baseState"));
            return prelight;
        } catch (Throwable error) {
            System.out.println("PRELIGHT unavailable " + cause(error));
            return null;
        }
    }

    private static String descriptor(Class<?> callbackApi) {
        for (Class<?> owner : new Class[]{callbackApi, stubOf(callbackApi)}) {
            if (owner == null) continue;
            try {
                Object value = owner.getField("DESCRIPTOR").get(null);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) { }
        }
        return "android.hardware.devicestate.IDeviceStateManagerCallback";
    }

    private static Class<?> stubOf(Class<?> api) {
        try { return Class.forName(api.getName() + "$Stub"); }
        catch (Throwable error) { return null; }
    }

    /** Called from the engine frame loop. Asserting a concurrent state flips the
     * default display, which the engine sees as a panel handoff and resets its
     * motion state; the last real direction is latched so that reset does not
     * release the override mid-fold. */
    void tick(FoldMotion motion, boolean interactive, boolean holdEngage) {
        if (service == null || dead) return;
        long now = SystemClock.elapsedRealtime();
        if (dirty || now >= nextInfo) {
            dirty = false;
            nextInfo = now + INFO_MS;
            if (!refresh()) return;
        }
        // isInteractive flickers while the override flips the default display to
        // a panel that is still powering on; only a sustained loss releases.
        if (!interactive) {
            if (interactiveLost == 0) interactiveLost = now;
            else if (now - interactiveLost > 3000) { release(); lastDirection = null; }
            return;
        }
        interactiveLost = 0;
        if (motion.active() && !motion.releasing() && motion.direction() != null) {
            lastDirection = motion.direction();
            lastEvidence = now;
        }
        // The physical state reached the destination on its own.
        if (lastDirection == FoldMotion.Direction.OPENING && physical >= 3) lastDirection = null;
        if (lastDirection == FoldMotion.Direction.CLOSING && physical <= 0) lastDirection = null;
        int want = lastDirection == FoldMotion.Direction.OPENING && physical >= 0 && physical < 3
                ? CONCURRENT_INNER
                : lastDirection == FoldMotion.Direction.CLOSING && physical >= 1 && physical <= 2
                ? CONCURRENT_OUTER : -1;
        // A fold abandoned mid-way lets the override go after a grace period.
        if (want >= 0 && now - lastEvidence > STALE_MS) { want = -1; lastDirection = null; }
        if (want != desired) {
            release();
            desired = want;
            if (desired >= 0) System.out.println("PRELIGHT engage=" + desired + " physical=" + physical);
        }
        // While held the direction is still latched; the assert simply waits
        // for the outgoing panel's layer so the flip lands without a gap.
        if (desired < 0 || holdEngage) return;
        if (committed != desired && now >= nextAssert) {
            nextAssert = now + REASSERT_MS;
            try { requestBase.invoke(service, token, desired, 0); }
            catch (Throwable error) { dead = true; System.out.println("PRELIGHT request failed " + cause(error)); }
        }
    }

    /** Probe entry: request 4, read back the committed state, release. */
    void selfTestRequest() {
        try {
            requestBase.invoke(service, token, CONCURRENT_INNER, 0);
            Thread.sleep(300);
            if (refresh()) System.out.println("SELFTEST committed=" + committed + " physical=" + physical);
            cancelBase.invoke(service);
            Thread.sleep(150);
            if (refresh()) System.out.println("SELFTEST afterCancel committed=" + committed);
        } catch (Throwable error) {
            System.out.println("SELFTEST failed " + cause(error));
        }
    }

    /** Probe hooks: assert/cancel a base state directly, bypassing tick logic. */
    void probeAssert(int state) throws Exception { requestBase.invoke(service, token, state, 0); }
    void probeRelease() throws Exception { cancelBase.invoke(service); }

    boolean engaged() { return desired >= 0; }
    /** Last sensor-reported physical device state; -1 while unknown or
     * shadowed by our own override. */
    int physical() { return physical; }

    void release() {
        if (desired < 0) return;
        desired = -1;
        try { cancelBase.invoke(service); }
        catch (Throwable error) { System.out.println("PRELIGHT release failed " + cause(error)); }
        System.out.println("PRELIGHT released");
    }

    private boolean refresh() {
        try {
            Object info = getInfo.invoke(service);
            if (!dumped) {
                dumped = true;
                StringBuilder fields = new StringBuilder("PRELIGHT info");
                for (Field f : info.getClass().getFields())
                    fields.append(' ').append(f.getName()).append('=').append(read(info, f));
                System.out.println(fields.toString());
            }
            committed = toId(read(info, "currentState"));
            if (committed < 0) committed = toId(read(info, "committedState"));
            // While a base override is held, baseState reports OUR value; the
            // physical sensor state only reappears when a new report stomps it.
            int base = toId(read(info, "baseState"));
            if (desired < 0 || base != desired) physical = base;
            return true;
        } catch (Throwable error) {
            dead = true;
            System.out.println("PRELIGHT info failed " + cause(error));
            return false;
        }
    }

    private static Throwable cause(Throwable error) {
        Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                ? error.getCause() : error;
        return cause != null ? cause : error;
    }

    private static Object read(Object info, String name) throws Exception {
        try { return info.getClass().getField(name).get(info); }
        catch (NoSuchFieldException missing) { return null; }
    }
    private static Object read(Object info, Field field) {
        try { return field.get(info); } catch (IllegalAccessException denied) { return "?"; }
    }
    private static int toId(Object value) throws Exception {
        if (value == null) return -1;
        if (value instanceof Integer) return (Integer) value;
        return (Integer) value.getClass().getMethod("getIdentifier").invoke(value);
    }
}
