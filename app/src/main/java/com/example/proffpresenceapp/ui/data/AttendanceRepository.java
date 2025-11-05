package com.example.proffpresenceapp.ui.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Opens / verifies / closes an attendance session. */
public final class AttendanceRepository {

    private static final FirebaseDatabase DB = FirebaseDatabase.getInstance();

    private AttendanceRepository() {}

    public interface Callback {
        void ok();
        void fail(@NonNull String msg);
    }

    private static @Nullable FirebaseUser user() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    /** yyyy-MM-dd (device local). */
    private static String localDayKey(long nowMs) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(nowMs));
    }

    /** Call on geofence ENTER. Creates a session + writes /presenceOpen/<uid>. */
    public static void startSession(@NonNull String geofenceId, @NonNull Callback cb) {
        FirebaseUser u = user();
        if (u == null) { cb.fail("not signed in"); return; }

        long now = System.currentTimeMillis();
        String date = localDayKey(now);

        DatabaseReference sessionsRef = DB.getReference("attendance").child(date).child(u.getUid());
        String sessionId = sessionsRef.push().getKey();
        if (sessionId == null) { cb.fail("push key failed"); return; }

        Map<String,Object> session = new HashMap<>();
        session.put("geofenceId", geofenceId);
        session.put("start", now);
        session.put("status", "present");
        session.put("method", "geofence");

        Map<String,Object> open = new HashMap<>();
        open.put("date", date);
        open.put("sessionId", sessionId);
        open.put("geofenceId", geofenceId);
        open.put("start", now);

        Map<String,Object> fanout = new HashMap<>();
        fanout.put("/attendance/"+date+"/"+u.getUid()+"/"+sessionId, session);
        fanout.put("/presenceOpen/"+u.getUid(), open);

        DB.getReference().updateChildren(fanout).addOnCompleteListener(t -> {
            if (t.isSuccessful()) cb.ok();
            else cb.fail(t.getException() != null ? t.getException().getMessage() : "update failed");
        });
    }

    /** Call right after face verification succeeds (while user is inside). */
    public static void markVerified(@NonNull Callback cb) {
        FirebaseUser u = user();
        if (u == null) { cb.fail("not signed in"); return; }

        DB.getReference("presenceOpen").child(u.getUid()).get().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult()==null || !t.getResult().exists()) {
                cb.fail("no open session"); return;
            }
            String date = String.valueOf(t.getResult().child("date").getValue());
            String sessionId = String.valueOf(t.getResult().child("sessionId").getValue());
            long now = System.currentTimeMillis();

            Map<String,Object> upd = new HashMap<>();
            upd.put("/attendance/"+date+"/"+u.getUid()+"/"+sessionId+"/verifiedAt", now);
            upd.put("/attendance/"+date+"/"+u.getUid()+"/"+sessionId+"/method", "geofence+face");

            DB.getReference().updateChildren(upd).addOnCompleteListener(tt -> {
                if (tt.isSuccessful()) cb.ok(); else cb.fail("verify mark failed");
            });
        });
    }

    /** Call on geofence EXIT. Closes session and writes end + durationMs. */
    public static void endSession(@NonNull Callback cb) {
        FirebaseUser u = user();
        if (u == null) { cb.fail("not signed in"); return; }

        DatabaseReference openRef = DB.getReference("presenceOpen").child(u.getUid());
        openRef.get().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult()==null || !t.getResult().exists()) {
                cb.fail("no open session"); return;
            }
            String date = String.valueOf(t.getResult().child("date").getValue());
            String sessionId = String.valueOf(t.getResult().child("sessionId").getValue());
            Long start = t.getResult().child("start").getValue(Long.class);
            if (date == null || sessionId == null || start == null) { cb.fail("bad open pointer"); return; }

            long end = System.currentTimeMillis();
            long dur = Math.max(0, end - start);

            Map<String,Object> upd = new HashMap<>();
            upd.put("/attendance/"+date+"/"+u.getUid()+"/"+sessionId+"/end", end);
            upd.put("/attendance/"+date+"/"+u.getUid()+"/"+sessionId+"/durationMs", dur);
            upd.put("/attendance/"+date+"/"+u.getUid()+"/"+sessionId+"/status", "completed");
            upd.put("/presenceOpen/"+u.getUid(), null); // clear pointer

            DB.getReference().updateChildren(upd).addOnCompleteListener(tt -> {
                if (tt.isSuccessful()) cb.ok(); else cb.fail("close failed");
            });
        });
    }
}
