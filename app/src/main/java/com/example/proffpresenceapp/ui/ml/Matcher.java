package com.example.proffpresenceapp.ui.ml;

import java.util.List;

public class Matcher {
    public static float cosine(float[] a, float[] b) {
        float dot=0f; for (int i=0;i<a.length;i++) dot += a[i]*b[i];
        return dot;
    }
    public static float bestCosine(float[] live, List<float[]> gallery) {
        float best=-1f; for (float[] g: gallery) best = Math.max(best, cosine(live, g));
        return best;
    }
}
