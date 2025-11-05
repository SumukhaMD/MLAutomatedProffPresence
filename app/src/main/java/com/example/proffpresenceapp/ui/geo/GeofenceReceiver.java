package com.example.proffpresenceapp.ui.geo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.example.proffpresenceapp.ui.core.AttendanceHelper;
import com.example.proffpresenceapp.ui.core.NotificationHelper;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

public class GeofenceReceiver extends BroadcastReceiver {

    private static final String TAG = "GeofenceReceiver";

    // Keep these in sync with your MainActivity / DB geofence
    private static final String GEOFENCE_ID = "campus_main";
    // Fallbacks in case DB not loaded here; you can pass these via intent extras if you prefer
    private static final double CAMPUS_LAT = 13.066602;
    private static final double CAMPUS_LNG = 77.504582;
    private static final float  CAMPUS_RADIUS_M = 100f;
    // A small margin helps with GPS noise
    private static final float  RADIUS_MARGIN_M = 20f;

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null) { Log.w(TAG, "Null GeofencingEvent"); return; }
        if (event.hasError()) { Log.w(TAG, "Geofence error: " + event.getErrorCode()); return; }

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { Log.w(TAG, "No signed-in user; ignoring"); return; }

        int transition = event.getGeofenceTransition();
        List<Geofence> list = event.getTriggeringGeofences();
        String which = (list != null && !list.isEmpty()) ? list.get(0).getRequestId() : GEOFENCE_ID;

        switch (transition) {
            case Geofence.GEOFENCE_TRANSITION_ENTER:
            case Geofence.GEOFENCE_TRANSITION_DWELL:
                // Before doing anything, verify we're *actually* inside now.
                confirmInsideThenRun(context, inside -> {
                    if (!inside) {
                        Log.i(TAG, "ENTER/DWELL ignored: not inside radius now");
                        return;
                    }
                    // Open a session (start time will be shown in dashboard)
                    AttendanceHelper.onEnter(u.getUid());
                    // Ask the user to verify face (tap to open camera)
                    NotificationHelper.notifyFaceVerification(context);
                    Log.i(TAG, "ENTER/DWELL processed for " + which);
                });
                break;

            case Geofence.GEOFENCE_TRANSITION_EXIT:
                AttendanceHelper.onExit(u.getUid()); // closes the open session → end & duration appear
                NotificationHelper.showSimple(
                        context, "Left campus", "You exited " + which, 1002);
                Log.i(TAG, "EXIT processed for " + which);
                break;

            default:
                Log.w(TAG, "Unknown transition: " + transition);
        }
    }

    /** Get a fresh location and decide if we're inside radius right now. */
    private interface InsideCallback { void done(boolean inside); }

    @SuppressLint("MissingPermission")
    private void confirmInsideThenRun(Context ctx, InsideCallback cb) {
        // Must have fine location to resolve here
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "Location permission missing; ignoring geofence.", Toast.LENGTH_SHORT).show();
            cb.done(false);
            return;
        }
        FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(ctx);
        fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc == null) { cb.done(false); return; }
                    float d = distanceMeters(loc.getLatitude(), loc.getLongitude(), CAMPUS_LAT, CAMPUS_LNG);
                    boolean inside = d <= (CAMPUS_RADIUS_M + RADIUS_MARGIN_M);
                    cb.done(inside);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "getCurrentLocation failed: " + e.getMessage());
                    cb.done(false);
                });
    }

    private static float distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] out = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, out);
        return out[0];
    }
}
