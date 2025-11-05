package com.example.proffpresenceapp.ui.ml;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.face.Face;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import android.util.Base64;

public class FaceUtils {

    @Nullable
    public static Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) return null;
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return null;

        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();

        int ySize = y.remaining(), uSize = u.remaining(), vSize = v.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        y.get(nv21, 0, ySize);
        v.get(nv21, ySize, vSize);
        u.get(nv21, ySize + vSize, uSize);

        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);
        byte[] jpeg = out.toByteArray();
        Bitmap raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (raw == null) return null;

        int rotation = image.getImageInfo().getRotationDegrees();
        if (rotation == 0) return raw;

        Matrix m = new Matrix();
        m.postRotate(rotation);
        return Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
    }

    /** Expand the face box by margin (e.g. 0.2 = 20%) before resizing to 'size'. */
    @Nullable
    public static Bitmap cropAlignResize(Bitmap src, Face face, int size, float margin) {
        if (src == null || face == null || size <= 0) return null;
        Rect b = face.getBoundingBox();

        int w = b.width(), h = b.height();
        int extraW = (int) (w * margin);
        int extraH = (int) (h * margin);

        Rect ex = new Rect(
                Math.max(0, b.left - extraW),
                Math.max(0, b.top - extraH),
                Math.min(src.getWidth(), b.right + extraW),
                Math.min(src.getHeight(), b.bottom + extraH)
        );
        if (ex.width() <= 0 || ex.height() <= 0) return null;

        Bitmap crop = Bitmap.createBitmap(src, ex.left, ex.top, ex.width(), ex.height());
        return Bitmap.createScaledBitmap(crop, size, size, true);
    }

    /** Legacy signature keeps existing callers working (uses 20% margin). */
    @Nullable
    public static Bitmap cropAlignResize(Bitmap src, Face face, int size) {
        return cropAlignResize(src, face, size, 0.20f);
    }

    /** NHWC [1,H,W,3] floats in range [0..1] (RGB). */
    public static float[][][][] bitmapToInput(Bitmap bmp) {
        int s = bmp.getWidth();
        float[][][][] out = new float[1][s][s][3];
        int[] px = new int[s * s];
        bmp.getPixels(px, 0, s, 0, 0, s, s);
        for (int y = 0; y < s; y++) {
            int row = y * s;
            for (int x = 0; x < s; x++) {
                int c = px[row + x];
                out[0][y][x][0] = ((c >> 16) & 0xff) / 255f;
                out[0][y][x][1] = ((c >> 8) & 0xff) / 255f;
                out[0][y][x][2] = (c & 0xff) / 255f;
            }
        }
        return out;
    }

    /* ---------- base64 helpers for embeddings ---------- */

    @Nullable
    public static String floatsToBase64(@Nullable float[] v) {
        if (v == null) return null;
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP);
    }

    @Nullable
    public static float[] base64ToFloats(@Nullable String s) {
        if (s == null) return null;
        try {
            byte[] bytes = Base64.decode(s, Base64.DEFAULT);
            FloatBuffer fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] out = new float[fb.remaining()];
            fb.get(out);
            return out;
        } catch (IllegalArgumentException bad) {
            try {
                String[] parts = s.split(",");
                float[] out = new float[parts.length];
                for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].trim());
                return out;
            } catch (Exception ignored) { return null; }
        }
    }
}
