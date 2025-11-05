package com.example.proffpresenceapp.ui.ml;

import android.content.Context;
import android.content.SharedPreferences;

public final class LocalStats {
    private static final String PREF = "eta_local_stats";
    private LocalStats(){}

    public static void recordTrip(Context ctx, double distanceKm, long durSecs) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        // avgSpeed7d: exponential moving avg in km/h
        double kmph = distanceKm / Math.max(1e-6, (durSecs/3600.0));
        double prev = Double.longBitsToDouble(p.getLong("speed_avg", Double.doubleToLongBits(18.0)));
        double ema  = 0.7*prev + 0.3*kmph;
        // last3 average (store as rolling)
        double l = Double.longBitsToDouble(p.getLong("last3_sum", Double.doubleToLongBits(0)));
        int n     = p.getInt("last3_n", 0);
        if (n < 3) { l += durSecs; n += 1; } else { l = l*2.0/3.0 + durSecs/3.0; n = 3; }

        p.edit()
                .putLong("speed_avg", Double.doubleToLongBits(ema))
                .putLong("last3_sum", Double.doubleToLongBits(l))
                .putInt("last3_n", n)
                .apply();
    }

    public static double getAvgSpeed7d(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return Double.longBitsToDouble(p.getLong("speed_avg", Double.doubleToLongBits(18.0)));
    }
    public static double getLast3AvgSecs(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        double s = Double.longBitsToDouble(p.getLong("last3_sum", Double.doubleToLongBits(0)));
        int n = p.getInt("last3_n", 0);
        return n == 0 ? 0.0 : (s / n);
    }
}