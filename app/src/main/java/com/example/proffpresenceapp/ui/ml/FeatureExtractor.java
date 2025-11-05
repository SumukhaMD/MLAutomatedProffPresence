package com.example.proffpresenceapp.ui.ml;

import android.content.Context;
import java.util.Calendar;

public final class FeatureExtractor {
    private FeatureExtractor() {}

    /** Build feature vector from distance (km) and start timestamp (ms). */
    public static double[] buildFeatures(Context ctx, double distanceKm, long startTs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startTs);
        int hour = c.get(Calendar.HOUR_OF_DAY);           // 0..23
        int dow  = c.get(Calendar.DAY_OF_WEEK) - 1;       // 0..6

        double avgSpeed7d   = LocalStats.getAvgSpeed7d(ctx);    // km/h (fallback 18)
        double last3AvgSecs = LocalStats.getLast3AvgSecs(ctx);  // seconds (fallback 0)

        return new double[] { distanceKm, hour, dow, avgSpeed7d, last3AvgSecs };
    }
}