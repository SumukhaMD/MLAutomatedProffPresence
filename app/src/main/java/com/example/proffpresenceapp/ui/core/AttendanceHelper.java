package com.example.proffpresenceapp.ui.core;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Attendance session helper.
 * - onEnter(uid): create/ensure an open session and write "start"
 * - writePresent(ctx, uid, method): upgrade the open row's method after face verification
 * - onExit(uid): write "end" + "durationMs" and close the session
 *
 * Data model:
 * presenceOpen/<uid> = { date, key, start }
 * attendance/<date>/<uid>/<key> = { start, end?, durationMs?, status, method, geofenceId, timestamp }
 */
public final class AttendanceHelper {

    private static final String TAG = "AttendanceHelper";
    private static final String GEOFENCE_ID = "campus_main";

    private AttendanceHelper() {}

    /* ---------------- utils ---------------- */

    private static DatabaseReference db() {
        return FirebaseDatabase.getInstance().getReference();
    }

    /** Local day yyyy-MM-dd (matches the dashboard). */
    private static String localDay() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(new Date());
    }

    private static DatabaseReference presenceRef(String uid) {
        return db().child("presenceOpen").child(uid);
    }

    private static DatabaseReference attendanceDayRef(String date, String uid) {
        return db().child("attendance").child(date).child(uid);
    }

    /* ---------------- API ---------------- */

    /** Start/resume the user's open session for today. Safe to call multiple times. */
    public static void onEnter(@NonNull String uid) {
        final long now = System.currentTimeMillis();
        final String today = localDay();

        presenceRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                // If there's an open session AND same date, do nothing.
                if (snap.exists()) {
                    String date = String.valueOf(snap.child("date").getValue());
                    if (today.equals(date)) {
                        Log.d(TAG, "onEnter: already open for today.");
                        return;
                    }
                }

                // Create new attendance row with 'start'
                DatabaseReference rowRef = attendanceDayRef(today, uid).push();
                Map<String, Object> row = new HashMap<>();
                row.put("start", now);
                row.put("status", "present");
                row.put("method", "geofence"); // liveness/FR can upgrade this later
                row.put("geofenceId", GEOFENCE_ID);
                row.put("timestamp", now);

                rowRef.setValue(row).addOnCompleteListener(t -> {
                    if (t.isSuccessful()) {
                        Map<String, Object> open = new HashMap<>();
                        open.put("date", today);
                        open.put("key", rowRef.getKey());
                        open.put("start", now);
                        presenceRef(uid).setValue(open);
                        Log.d(TAG, "onEnter: session opened " + rowRef.getKey());
                    } else {
                        Log.w(TAG, "onEnter: failed to write start", t.getException());
                    }
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "onEnter cancelled: " + error.getMessage());
            }
        });
    }

    /**
     * After face verification succeeds, call to upgrade the open row's "method".
     * If no open row exists, this is a no-op.
     *
     * @param context only needed if you later want to show a Toast here; currently unused
     * @param uid     current user's uid
     * @param method  e.g. "geofence+liveness+fr"
     */
    public static void writePresent(@Nullable Context context,
                                    @NonNull String uid,
                                    @NonNull String method) {
        final String today = localDay();
        presenceRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) {
                    Log.w(TAG, "writePresent: no open session for uid=" + uid);
                    return;
                }
                String key = String.valueOf(s.child("key").getValue());
                if (key == null || key.isEmpty()) {
                    Log.w(TAG, "writePresent: open session missing key");
                    return;
                }

                Map<String, Object> upd = new HashMap<>();
                upd.put("method", method);
                upd.put("timestamp", System.currentTimeMillis());

                attendanceDayRef(today, uid).child(key).updateChildren(upd)
                        .addOnFailureListener(e ->
                                Log.w(TAG, "writePresent: update failed: " + e.getMessage()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "writePresent cancelled: " + e.getMessage());
            }
        });
    }

    /**
     * Close the open session: write end & durationMs into the same row and clear presenceOpen.
     * Safe to call multiple times.
     */
    public static void onExit(@NonNull String uid) {
        final long end = System.currentTimeMillis();
        final String today = localDay();

        presenceRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) {
                    Log.d(TAG, "onExit: no open session; nothing to do.");
                    return;
                }
                String key = String.valueOf(s.child("key").getValue());
                Long start = s.child("start").getValue(Long.class);
                String date = String.valueOf(s.child("date").getValue());

                if (key == null || start == null) {
                    Log.w(TAG, "onExit: invalid open session");
                    presenceRef(uid).removeValue();
                    return;
                }

                // If the open session was from a previous day, we still close it in that day bucket.
                final String bucketDate = today.equals(date) ? today : date;

                long duration = Math.max(0L, end - start);

                Map<String, Object> upd = new HashMap<>();
                upd.put("end", end);
                upd.put("durationMs", duration);

                attendanceDayRef(bucketDate, uid).child(key).updateChildren(upd)
                        .addOnCompleteListener(t -> {
                            presenceRef(uid).removeValue();
                            if (t.isSuccessful()) {
                                Log.d(TAG, "onExit: closed " + key + " duration=" + duration);
                            } else {
                                Log.w(TAG, "onExit: update failed", t.getException());
                            }
                        });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "onExit cancelled: " + e.getMessage());
            }
        });
    }
}
