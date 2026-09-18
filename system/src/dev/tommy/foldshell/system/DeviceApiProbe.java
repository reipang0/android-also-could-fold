package dev.tommy.foldshell.system;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

/** Read-only reflection probe: lists the device_state-related hidden API surface. */
public final class DeviceApiProbe {
    public static void main(String[] args) {
        String[] candidates = {
                "com.samsung.android.view.SemWindowManager",
                "com.samsung.android.view.SemWindowManager$FoldStateListener",
                "android.hardware.devicestate.DeviceStateInfo",
                "android.hardware.devicestate.DeviceStateManager$DeviceStateCallback",
        };
        for (String name : candidates) {
            try {
                Class<?> cls = Class.forName(name);
                System.out.println("CLASS " + name);
                Method[] methods = cls.getDeclaredMethods();
                Arrays.sort(methods, Comparator.comparing(Method::getName));
                for (Method m : methods)
                    System.out.println("  " + m.getName() + " " + Arrays.toString(m.getParameterTypes())
                            + " -> " + m.getReturnType().getSimpleName());
            } catch (ClassNotFoundException missing) {
                System.out.println("MISSING " + name);
            }
        }
        if (args.length > 0 && args[0].equals("prelight")) selfTest();
        System.out.println("API PROBE DONE");
        System.exit(0);
    }

    /** Exercises the full binder path without a physical fold: register the
     * callback, assert base-state 4, confirm the committed state, then cancel.
     * Briefly powers the inner panel; run only when that is acceptable. */
    private static void selfTest() {
        DeviceStatePrelight prelight = DeviceStatePrelight.create();
        if (prelight == null) { System.out.println("SELFTEST create=null"); return; }
        prelight.selfTestRequest();
    }
}
