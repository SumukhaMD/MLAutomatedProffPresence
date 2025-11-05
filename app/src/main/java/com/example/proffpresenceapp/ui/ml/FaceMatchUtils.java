package com.example.proffpresenceapp.ui.ml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/** Cosine-similarity based face matcher. */
public final class FaceMatchUtils {

    private FaceMatchUtils() {}

    /** L2-normalize the vector in-place. */
    public static void normalize(@NonNull float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        double n = Math.sqrt(Math.max(sum, 1e-12));
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / n);
    }

    /** Returns cosine similarity in [-1,1]. 1 means identical direction. */
    public static float cosine(@NonNull float[] a, @NonNull float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double denom = Math.sqrt(Math.max(na,1e-12)) * Math.sqrt(Math.max(nb,1e-12));
        return (float) (dot / Math.max(denom, 1e-12));
    }

    /** Compute the best similarity to any gallery vector, returns max similarity. */
    public static float bestSimilarity(@NonNull float[] probe, @NonNull List<float[]> gallery) {
        float best = -1f;
        for (float[] g : gallery) {
            if (g == null) continue;
            float sim = cosine(probe, g);
            if (sim > best) best = sim;
        }
        return best;
    }

    /**
     * Decide if the probe matches given gallery using cosine & threshold.
     * Typical good thresholds for MobileFaceNet: 0.60–0.85 (tune on your data).
     */
    public static boolean isMatch(@NonNull float[] probe,
                                  @NonNull List<float[]> gallery,
                                  float threshold) {
        if (gallery.isEmpty()) return false;
        normalize(probe);
        for (float[] g : gallery) if (g != null) normalize(g);
        return bestSimilarity(probe, gallery) >= threshold;
    }
}
