package dev.tommy.foldshell.system;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import java.lang.reflect.Method;

/** Two-panel compositing probe. Powers both panels via a base-state override,
 * then pins a solid-fill SurfaceControl to each panel's own layer stack to see
 * whether a powered but non-default panel composites our layer.
 * cover = red fill, inner = blue fill. */
public final class DualPanelProbe {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("Run as ADB shell");
        int holdSeconds = args.length == 0 ? 8 : Integer.parseInt(args[0]);
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context context = ((Context) at.getMethod("getSystemContext").invoke(thread))
                .createPackageContext("com.android.shell", 0);
        Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = dmg.getMethod("getInstance").invoke(null);
        Method infoFor = dmg.getMethod("getDisplayInfo", int.class);
        int[] ids = (int[]) dmg.getMethod("getDisplayIds").invoke(global);
        System.out.println("DISPLAYS " + java.util.Arrays.toString(ids));

        DeviceStatePrelight prelight = DeviceStatePrelight.create();
        if (prelight == null) { System.out.println("PROBE no binder"); System.exit(1); }
        // CONCURRENT_INNER_DEFAULT: inner default + cover powered.
        prelight.probeAssert(4);
        System.out.println("PROBE asserted=4, waiting for panels");
        Thread.sleep(2500);

        SurfaceControl coverLayer = null, innerLayer = null;
        Surface coverSurface = null, innerSurface = null;
        try {
            for (int id : ids) {
                Object info = infoFor.invoke(global, id);
                if (info == null) continue;
                int w = field(info, "logicalWidth"), h = field(info, "logicalHeight");
                int stack = field(info, "layerStack"), state = field(info, "state");
                Object address = info.getClass().getField("address").get(info);
                long physical = (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
                boolean inner = Math.min(w, h) * 160f / field(info, "logicalDensityDpi") >= 600f;
                System.out.println("PROBE display id=" + id + " " + w + "x" + h + " stack=" + stack
                        + " state=" + state + " physical=" + physical + " inner=" + inner);
                innerLayer = paint(stack, w, h, inner ? Color.BLUE : Color.RED,
                        inner ? "inner" : "cover");
                innerSurface = new Surface(innerLayer);
                drawFill(innerSurface, inner ? Color.BLUE : Color.RED);
                commit(innerLayer, stack);
                System.out.println("PROBE painted " + (inner ? "inner=BLUE" : "cover=RED"));
            }
            // The cover panel is powered under CONCURRENT_INNER but has no logical
            // Display; SurfaceFlinger binds it to layerStack 1. Paint it directly.
            coverLayer = paint(1, 1080, 2520, Color.RED, "cover-stack1");
            coverSurface = new Surface(coverLayer);
            drawFill(coverSurface, Color.RED);
            commit(coverLayer, 1);
            System.out.println("PROBE painted cover=RED stack=1");
            Thread.sleep(holdSeconds * 1000L);
        } finally {
            prelight.probeRelease();
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                Method remove = SurfaceControl.Transaction.class
                        .getMethod("remove", SurfaceControl.class);
                if (coverLayer != null) remove.invoke(t, coverLayer);
                if (innerLayer != null) remove.invoke(t, innerLayer);
                t.apply();
            }
            if (coverLayer != null) coverLayer.release();
            if (innerLayer != null) innerLayer.release();
            if (coverSurface != null) coverSurface.release();
            if (innerSurface != null) innerSurface.release();
        }
        System.out.println("PROBE done");
        System.exit(0);
    }

    private static SurfaceControl paint(int stack, int w, int h, int color, String name) {
        return new SurfaceControl.Builder().setName("DualPanelProbe-" + name)
                .setBufferSize(w, h).setFormat(PixelFormat.RGBA_8888).setOpaque(false).build();
    }
    private static void drawFill(Surface surface, int color) {
        Canvas canvas = surface.lockCanvas(null);
        canvas.drawColor(color);
        surface.unlockCanvasAndPost(canvas);
    }
    private static void commit(SurfaceControl layer, int stack) throws Exception {
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            SurfaceControl.Transaction.class
                    .getMethod("setLayerStack", SurfaceControl.class, int.class)
                    .invoke(t, layer, stack);
            t.setLayer(layer, Integer.MAX_VALUE - 2);
            SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class)
                    .invoke(t, layer);
            t.apply();
        }
    }
    private static int field(Object object, String name) throws Exception {
        return object.getClass().getField(name).getInt(object);
    }
}
