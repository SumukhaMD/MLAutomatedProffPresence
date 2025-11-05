package com.example.proffpresenceapp.ui.geo;

import androidx.annotation.NonNull;

import com.google.firebase.database.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal attendance writer that matches your RTDB rules.
 * Path: attendance/<yyyy-mm-dd>/<uid>/<pushId>
 */
public class AttendanceWriter {

    /** yyyy-mm-dd in device local time */
    private static @NonNull String localDay() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int y = c.get(java.util.Calendar.YEAR);
        int m = c.get(java.util.Calendar.MONTH) + 1; // 0-based
        int d = c.get(java.util.Calendar.DAY_OF_MONTH);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m, d);
    }

    /** On ENTER: create a new row with startTs. */
    public static void writeEnter(@NonNull String uid, @NonNull String geofenceId) {
        String day = localDay();
        long now = System.currentTimeMillis();

        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("attendance")
                .child(day)
                .child(uid)
                .push();

        Map<String, Object> row = new HashMap<>();
        row.put("status", "enter");
        row.put("geofenceId", geofenceId);
        row.put("startTs", now);
        row.put("method", "geofence");

        ref.setValue(row);
        // If rules restrict writes to owner only, make sure the app user is the same uid.
    }

    /**
     * On EXIT: find the *latest* record today for the user and set endTs + durationMs.
     * If none found, we create a simple exit row (so nothing is lost).
     */
    public static void writeExit(@NonNull String uid, @NonNull String geofenceId) {
        String day = localDay();
        long now = System.currentTimeMillis();

        DatabaseReference base = FirebaseDatabase.getInstance()
                .getReference("attendance")
                .child(day)
                .child(uid);

        // Get the last row and update it if it doesn't yet have endTs
        base.orderByChild("startTs").limitToLast(1)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (snap.getChildrenCount() == 0) {
                            // No enter found; write standalone exit record
                            DatabaseReference ref = base.push();
                            Map<String,Object> row = new HashMap<>();
                            row.put("status", "exit");
                            row.put("geofenceId", geofenceId);
                            row.put("endTs", now);
                            row.put("method", "geofence");
                            ref.setValue(row);
                            return;
                        }

                        DataSnapshot last = null;
                        for (DataSnapshot ds : snap.getChildren()) last = ds;
                        if (last == null) return;

                        Long startTs = last.child("startTs").getValue(Long.class);
                        Long endTs   = last.child("endTs").getValue(Long.class);

                        if (startTs != null && endTs == null) {
                            Map<String,Object> upd = new HashMap<>();
                            upd.put("endTs", now);
                            upd.put("status", "exit");
                            upd.put("geofenceId", geofenceId);
                            upd.put("method", "geofence");
                            upd.put("durationMs", Math.max(0, now - startTs));
                            last.getRef().updateChildren(upd);
                        } else {
                            // Already closed or malformed; write a new exit row
                            DatabaseReference ref = base.push();
                            Map<String,Object> row = new HashMap<>();
                            row.put("status", "exit");
                            row.put("geofenceId", geofenceId);
                            row.put("endTs", now);
                            row.put("method", "geofence");
                            ref.setValue(row);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) { /* no-op */ }
                });
    }
}
