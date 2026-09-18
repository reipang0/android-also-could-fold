package dev.tommy.foldshell.system;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.Surface;
import android.view.SurfaceControl;
import java.lang.reflect.Method;

/** Duo choreography on the powered, non-default panel during a transition.
 * The outgoing panel keeps a fixed rectangular content projection while its
 * physical surface swings away: drawing only the hinge-side cos(theta) slice
 * stretched across the whole panel cancels the perspective foreshortening, so
 * the content appears to hold its original plane instead of following the
 * panel. Where the surface overhangs the projection (the closing inner's
 * far-edge top/bottom triangles) the layer paints black wedges with a blurred
 * boundary, and the whole surface dims as it recedes. progress p is openness:
 * 0 = closed, 1 = flat open. */
final class DuoOutgoingRenderer {
    private static Method tx(String name, Class<?>... types) {
        try { return SurfaceControl.Transaction.class.getMethod(name, types); }
        catch (Throwable error) { return null; }
    }
    private static Method builder(String name, Class<?>... types) {
        try { return SurfaceControl.Builder.class.getMethod(name, types); }
        catch (Throwable error) { return null; }
    }
    private static final Method SET_STACK = tx("setLayerStack", SurfaceControl.class, int.class);
    private static final Method SHOW = tx("show", SurfaceControl.class);
    private static final Method HIDE = tx("hide", SurfaceControl.class);
    private static final Method REMOVE = tx("remove", SurfaceControl.class);
    private static final Method BLUR_REGIONS = tx("setBlurRegions", SurfaceControl.class, float[][].class);
    private static final Method EFFECT = builder("setEffectLayer");
    private static final Method SECURE = builder("setSecure", boolean.class);

    private SurfaceControl content, blurFx;
    private Surface contentSurface;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap snapshot;
    private int stack = -1, width, height;
    private boolean outgoingCover, closed;
    private float lastP = -1;

    boolean attached() { return content != null && !closed; }

    void attach(Bitmap bitmap, int layerStack, boolean cover) throws Exception {
        end();
        snapshot = bitmap; stack = layerStack;
        width = bitmap.getWidth(); height = bitmap.getHeight();
        outgoingCover = cover;
        SurfaceControl.Builder builder = new SurfaceControl.Builder()
                .setName("FoldTransition-Outgoing")
                .setBufferSize(width, height).setFormat(PixelFormat.RGBA_8888).setOpaque(false);
        if (SECURE != null) SECURE.invoke(builder, true);
        content = builder.build();
        contentSurface = new Surface(content);
        if (EFFECT != null) {
            SurfaceControl.Builder fx = new SurfaceControl.Builder()
                    .setName("FoldTransition-OutgoingBlur");
            EFFECT.invoke(fx);
            blurFx = fx.build();
        }
        System.out.println("DUO outgoing stack=" + stack + " " + width + "x" + height
                + " panel=" + (outgoingCover ? "cover" : "inner"));
    }

    void update(float p) throws Exception {
        if (!attached() || snapshot == null) return;
        // A full-panel bitmap redraw is expensive; skip imperceptible steps.
        if (lastP >= 0 && Math.abs(p - lastP) < .004f) return;
        lastP = p;
        Canvas canvas = contentSurface.lockCanvas(null);
        try {
            canvas.drawColor(Color.BLACK);
            // Swing angle of the outgoing surface: 0 at its flat/closed pose,
            // 1 = 180 degrees. The projected slice follows cos of the real angle.
            float theta = outgoingCover ? p : 1f - p;
            float f = Math.max(.06f, (float) Math.cos(theta * Math.PI));
            if (outgoingCover) {
                // Hinge is the left edge; content holds its plane so only the
                // hinge-side slice remains on the foreshortened surface.
                int srcW = Math.max(1, Math.round(width * f));
                canvas.drawBitmap(snapshot, new Rect(0, 0, srcW, height),
                        new Rect(0, 0, width, height), paint);
                drawDim(canvas, dimAt(theta), blackoutAt(p), width, height, false);
            } else {
                int half = width / 2;
                // Left half swings toward the viewer around the crease; the
                // projection keeps the crease-adjacent slice on the surface.
                int srcL = Math.round(half * (1f - f));
                canvas.drawBitmap(snapshot, new Rect(srcL, 0, half, height),
                        new Rect(0, 0, half, height), paint);
                canvas.drawBitmap(snapshot, new Rect(half, 0, width, height),
                        new Rect(half, 0, width, height), paint);
                // The surface overhangs the projection at the far edge; those
                // triangles stay black and dissolve at the blur boundary.
                drawWedges(canvas, half, height, 1f - f);
                drawDim(canvas, dimAt(theta), blackoutAt(p), width, height, true);
            }
        } finally {
            paint.setShader(null); paint.setAlpha(255);
            contentSurface.unlockCanvasAndPost(canvas);
        }
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            SET_STACK.invoke(t, content, stack);
            t.setLayer(content, FoldShell.CANVAS_Z);
            SHOW.invoke(t, content);
            if (blurFx != null && BLUR_REGIONS != null) {
                SET_STACK.invoke(t, blurFx, stack);
                t.setLayer(blurFx, FoldShell.BLUR_Z);
                BLUR_REGIONS.invoke(t, blurFx, blurRegions(p));
                SHOW.invoke(t, blurFx);
            }
            t.apply();
        }
    }

    /** Uniform dimming of the receding surface plus a late blackout from the
     * free edge, so the panel dissolves instead of snapping off. */
    private void drawDim(Canvas canvas, float dim, float blackout, int w, int h, boolean hingeLeft) {
        if (dim > 0) {
            paint.setShader(null); paint.setColor(Color.BLACK); paint.setAlpha(Math.round(235 * dim));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }
        if (blackout <= 0) return;
        int edge = Math.round(w * Math.min(1, blackout));
        if (edge <= 0) return;
        int tail = Math.min(w - edge, Math.round(w * .3f));
        paint.setShader(null); paint.setColor(Color.BLACK); paint.setAlpha(255);
        if (hingeLeft) {
            canvas.drawRect(0, 0, edge, h, paint);
            if (tail > 0) {
                paint.setShader(new LinearGradient(edge, 0, edge + tail, 0,
                        Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRect(edge, 0, edge + tail, h, paint);
            }
        } else {
            canvas.drawRect(w - edge, 0, w, h, paint);
            if (tail > 0) {
                paint.setShader(new LinearGradient(w - edge - tail, 0, w - edge, 0,
                        Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
                canvas.drawRect(w - edge - tail, 0, w - edge, h, paint);
            }
        }
        paint.setShader(null);
    }

    /** Black wedges at the far edge's top and bottom, tapering toward the
     * crease: the parts of the rotated surface that lie outside the fixed
     * content projection. */
    private void drawWedges(Canvas canvas, int half, int h, float reach) {
        paint.setShader(null); paint.setColor(Color.BLACK); paint.setAlpha(235);
        int cols = 24;
        for (int i = 0; i < cols; i++) {
            float x0 = i * half / (float) cols, x1 = (i + 1) * half / (float) cols;
            float wgt = 1f - (i + .5f) / cols;
            int wh = Math.round(h * .45f * reach * wgt);
            if (wh > 0) {
                canvas.drawRect(x0, 0, x1, wh, paint);
                canvas.drawRect(x0, h - wh, x1, h, paint);
            }
        }
        paint.setAlpha(255);
    }

    /** theta is the openness-normalized swing: 0 at the flat pose, 1 fully swung. */
    private float dimAt(float theta) {
        float t = (theta - .3f) / .4f;
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t) * .6f;
    }

    /** Fraction of the panel fading to black from the free edge. The cover
     * dissolves as it passes edge-on; the inner goes dark as it closes. */
    private float blackoutAt(float p) {
        float t = outgoingCover ? (p - .5f) / .22f : (.55f - p) / .25f;
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private float[][] blurRegions(float p) {
        int strips = 24;
        float[][] regions = new float[strips][14];
        for (int i = 0; i < strips; i++) {
            float l = i * width / (float) strips, r = (i + 1) * width / (float) strips;
            float a = blurAt((i + .5f) / strips, p);
            // Samsung BlurRegion format: radius, alpha, rect LTRB, corners, clip LTRB.
            regions[i] = new float[]{Math.round(160 * a), a, l, 0, r, height, 0, 0, 0, 0, l, 0, r, height};
        }
        return regions;
    }

    private float blurAt(float x, float p) {
        if (outgoingCover) {
            // Defocus tracks the receding free edge, deepest past edge-on.
            float focus = 1.25f - Math.min(1, p / .45f) * 1.1f;
            return smooth(focus - .2f, focus + .18f, x);
        }
        // Inner defocus decays with openness, deepest on the swing half (left).
        float defocus = (float) Math.pow(Math.max(0, 1 - p), .65f);
        return defocus * (1 - smooth(.2f, .49f, x));
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    void end() {
        if (content == null && blurFx == null) return;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            if (blurFx != null && HIDE != null) HIDE.invoke(t, blurFx);
            if (content != null && HIDE != null) HIDE.invoke(t, content);
            if (blurFx != null && REMOVE != null) REMOVE.invoke(t, blurFx);
            if (content != null && REMOVE != null) REMOVE.invoke(t, content);
            t.apply();
        } catch (Throwable error) { System.out.println("DUO cleanup " + error); }
        if (blurFx != null) { blurFx.release(); blurFx = null; }
        if (content != null) { content.release(); content = null; }
        if (contentSurface != null) { contentSurface.release(); contentSurface = null; }
        if (snapshot != null && !snapshot.isRecycled()) snapshot.recycle();
        snapshot = null;
    }
}
