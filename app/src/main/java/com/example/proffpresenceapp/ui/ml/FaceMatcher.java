package com.example.proffpresenceapp.ui.ml;

import android.util.Log;

import java.util.List;

public final class FaceMatcher {
    private static final String TAG = "FaceMatcher";

    private FaceMatcher() {}

    /** L2-normalize (in-place). Returns the same array for chaining. */
    public static float[] l2norm(float[] v) {
        if (v == null || v.length == 0) return v;
        double s = 0;
        for (float x : v) s += (double) x * x;
        s = Math.sqrt(Math.max(s, 1e-12));
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / s);
        return v;
    }

    /** Cosine similarity of two L2-normalized vectors ([-1..1], higher = more similar). */
    public static float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float s = 0f;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    /**
     * Decide if a live probe belongs to THIS user’s gallery.
     *
     * @param probe L2-normalized live vector
     * @param gallery list of L2-normalized enrolled vectors for the *current* user
     * @param strong primary threshold (quick accept if score >= strong)
     * @param secondary relaxed threshold used with vote counting
     * @param minAgree at least this many gallery vectors must exceed secondary
     */
    public static boolean acceptForUser(
            float[] probe,
            List<float[]> gallery,
            float strong,
            float secondary,
            int minAgree
    ) {
        if (probe == null || gallery == null || gallery.isEmpty()) return false;

        int agree = 0;
        float best = -2f;
        for (float[] g : gallery) {
            float s = cosine(probe, g);
            if (s > best) best = s;
            if (s >= secondary) agree++;
            if (s >= strong) {
                Log.d(TAG, "Accept (strong) best=" + best + " agree=" + agree);
                return true;
            }
        }
        Log.d(TAG, "Vote best=" + best + " agree=" + agree + "/" + gallery.size());
        return agree >= minAgree;
    }
}
