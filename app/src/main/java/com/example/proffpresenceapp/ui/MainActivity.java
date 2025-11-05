package com.example.proffpresenceapp.ui;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.proffpresenceapp.R;
import com.example.proffpresenceapp.ui.core.NotificationHelper;
import com.example.proffpresenceapp.ui.geo.GeofenceReceiver;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
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

public class MainActivity extends AppCompatActivity {

    private static final String GEOFENCE_ID = "campus_main";
    private static final int GEOFENCE_REQ_CODE = 1000;

    private TextView tvHello;
    private Button btnTestDb, btnArm, btnEnrollFace, btnOpenLiveness;

    private GeofencingClient geofencingClient;
    private FusedLocationProviderClient fusedLoc;

    // Defaults; these will be overwritten from RTDB: geofences/campus_main
    private double campusLat = 13.066602;
    private double campusLng = 77.504582;
    private float campusRadius = 100f;

    // permission launchers
    private ActivityResultLauncher<String> notifPermLauncher;
    private ActivityResultLauncher<String> fineLocLauncher;
    private ActivityResultLauncher<String> bgLocLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        setSupportActionBar(toolbar);
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_logout) {
                performLogout();
                return true;
            }
            return false;
        });

        tvHello         = findViewById(R.id.tvHello);
        btnTestDb       = findViewById(R.id.btnTestDb);
        btnArm          = findViewById(R.id.btnArm);
        btnEnrollFace   = findViewById(R.id.btnEnrollFace);
        btnOpenLiveness = findViewById(R.id.btnOpenLiveness);

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            tvHello.setText("Welcome, " + (u.getEmail() == null ? u.getUid() : u.getEmail()));
        }

        geofencingClient = LocationServices.getGeofencingClient(this);
        fusedLoc = LocationServices.getFusedLocationProviderClient(this);

        // Ensure the notifications channel exists (Android 8.0+)
        NotificationHelper.ensureChannel(this);

        setupPermissionLaunchers();
        loadCampusFromDB();
        ensureProfessorNode();

        btnTestDb.setOnClickListener(v -> writeTestPing());
        btnArm.setOnClickListener(v -> ensurePermsThenArm());
        btnEnrollFace.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterFaceActivity.class)));
        btnOpenLiveness.setOnClickListener(v ->
                startActivity(new Intent(this, LivenessActivity.class)));
    }

    /** Make sure the professor row at /professors/<uid> has an email */
    private void ensureProfessorNode() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        DatabaseReference p = FirebaseDatabase.getInstance()
                .getReference("professors").child(u.getUid());
        p.child("email").setValue(u.getEmail());
    }

    /* ---------------- Permissions ---------------- */

    private void setupPermissionLaunchers() {
        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { /* ok if denied; we just don't show notifications */ });

        fineLocLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) maybeAskBackgroundLocation();
                    else Toast.makeText(this, "Location required for geofence", Toast.LENGTH_LONG).show();
                });

        bgLocLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!granted) {
                        Toast.makeText(this,
                                "Background location improves geofence reliability.",
                                Toast.LENGTH_LONG).show();
                    }
                    // continue regardless
                    precheckDistanceThenArm();
                });
    }

    private void ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void ensurePermsThenArm() {
        ensureNotifPermission();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            fineLocLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }

        if (Build.VERSION.SDK_INT >= 29 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            maybeAskBackgroundLocation();
            return;
        }

        precheckDistanceThenArm();
    }

    private void maybeAskBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 29 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            bgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        } else {
            precheckDistanceThenArm();
        }
    }

    /* ---------------- Geofence: distance pre-check + register ---------------- */

    /** Quick meters distance helper */
    private static float distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] out = new float[1];
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, out);
        return out[0];
    }

    /** Get a fresh fix; if inside radius → arm, else show message. */
    @SuppressWarnings("MissingPermission")
    private void precheckDistanceThenArm() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant location permission first", Toast.LENGTH_SHORT).show();
            return;
        }

        fusedLoc.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                null
        ).addOnSuccessListener(loc -> {
            if (loc == null) {
                Toast.makeText(this, "Location unavailable. Try again outdoors.", Toast.LENGTH_LONG).show();
                return;
            }
            float d = distanceMeters(loc.getLatitude(), loc.getLongitude(), campusLat, campusLng);
            if (d > campusRadius) {
                Toast.makeText(this,
                        "You are not inside campus radius (" + Math.round(d) + "m).",
                        Toast.LENGTH_LONG).show();
                return;
            }

            armGeofenceInternal();
            Toast.makeText(this,
                    "Inside campus (" + Math.round(d) + "m). Geofence armed.",
                    Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e ->
                Toast.makeText(this, "Location error: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    /** Build a MUTABLE PendingIntent (required on Android 12+) */
    private PendingIntent geofencePendingIntent() {
        Intent i = new Intent(this, GeofenceReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(this, GEOFENCE_REQ_CODE, i, flags);
    }

    /** Actually register the geofence */
    @SuppressWarnings("MissingPermission")
    private void armGeofenceInternal() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant location permission first", Toast.LENGTH_SHORT).show();
            return;
        }

        Geofence geofence = new Geofence.Builder()
                .setRequestId(GEOFENCE_ID)
                .setCircularRegion(campusLat, campusLng, campusRadius)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER
                        | Geofence.GEOFENCE_TRANSITION_DWELL
                        | Geofence.GEOFENCE_TRANSITION_EXIT)
                .setLoiteringDelay(60_000) // 1 min dwell
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build();

        GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build();

        // Save arm moment (optional – for ETA or debug)
        savePendingTripStart(System.currentTimeMillis(), 0, 0);

        PendingIntent pi = geofencePendingIntent();

        // Remove any old registrations tied to this PI, then add a fresh one.
        geofencingClient.removeGeofences(pi).addOnCompleteListener(t -> {
            try {
                geofencingClient.addGeofences(request, pi)
                        .addOnSuccessListener(unused -> { /* ok */ })
                        .addOnFailureListener(e ->
                                Toast.makeText(this,
                                        "Geofence add failed: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show());
            } catch (SecurityException se) {
                Toast.makeText(this, "No location permission: " + se.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /* ---------------- Firebase helpers ---------------- */

    private void loadCampusFromDB() {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("geofences").child(GEOFENCE_ID);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (snap.exists()) {
                    Object val = snap.getValue();
                    if (val instanceof Map<?, ?>) {
                        Map<?, ?> m = (Map<?, ?>) val;
                        Object a = m.get("lat");
                        Object b = m.get("lng");
                        Object r = m.get("radiusMeters");
                        if (a instanceof Number) campusLat = ((Number) a).doubleValue();
                        if (b instanceof Number) campusLng = ((Number) b).doubleValue();
                        if (r instanceof Number) campusRadius = ((Number) r).floatValue();
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        });
    }

    private void savePendingTripStart(long ts, double lat, double lng) {
        SharedPreferences p = getSharedPreferences("eta_trip", MODE_PRIVATE);
        p.edit().putLong("ts", ts)
                .putString("latlng", lat + "," + lng)
                .apply();
    }

    private void writeTestPing() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;

        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("attendance").child(date).child(u.getUid()).push();

        Map<String, Object> row = new HashMap<>();
        row.put("timestamp", System.currentTimeMillis());
        row.put("status", "debug-ping");
        row.put("method", "setup-test");
        row.put("geofenceId", GEOFENCE_ID);

        ref.setValue(row)
                .addOnSuccessListener(ignored -> tvHello.setText("DB write OK"))
                .addOnFailureListener(e -> tvHello.setText("DB write failed: " + e.getMessage()));
    }

    private void performLogout() {
        FirebaseAuth.getInstance().signOut();
        try {
            com.google.android.gms.auth.api.signin.GoogleSignInOptions gso =
                    new com.google.android.gms.auth.api.signin.GoogleSignInOptions
                            .Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .build();
            com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso).signOut();
        } catch (Exception ignored) { }

        getSharedPreferences("eta_trip", MODE_PRIVATE).edit().clear().apply();

        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
