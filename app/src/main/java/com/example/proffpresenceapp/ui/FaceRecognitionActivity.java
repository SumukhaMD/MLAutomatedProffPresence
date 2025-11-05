package com.example.proffpresenceapp.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.proffpresenceapp.R;
import com.example.proffpresenceapp.ui.core.AttendanceHelper;
import com.example.proffpresenceapp.ui.liveness.LivenessGuard;
import com.example.proffpresenceapp.ui.ml.FaceEmbeddingProcessor;
import com.example.proffpresenceapp.ui.ml.FaceMatcher;
import com.example.proffpresenceapp.ui.ml.FaceUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaceRecognitionActivity extends AppCompatActivity {

    private PreviewView preview;
    private TextView tvHint;

    private ProcessCameraProvider provider;
    private ExecutorService exec;
    private FaceDetector detector;
    private FaceEmbeddingProcessor embedder;

    private final List<float[]> gallery = new ArrayList<>();
    private final LivenessGuard liveness = new LivenessGuard();
    private boolean livenessPassed = false;

    private int framesSeen = 0;
    private int agreeCount = 0;
    private float lastBestScore = -2f;   // for debug toast

    // === Thresholds to tune ===
    // After L2-normalization and cosine similarity:
    //   - same person is usually 0.65~0.9+
    //   - different person often < 0.35
    private static final float STRONG_THRESHOLD    = 0.60f;  // quick accept if any >= this
    private static final float SECONDARY_THRESHOLD = 0.50f;  // vote threshold
    private static final int   MIN_DECISION_FRAMES = 14;
    private static final int   MIN_AGREE_REQUIRED  = 7;

    private ActivityResultLauncher<String> camPerm;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_face_recognition);

        preview = findViewById(R.id.previewFr);
        tvHint  = findViewById(R.id.tvHint);

        exec = Executors.newSingleThreadExecutor();

        FaceDetectorOptions opts = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build();
        detector = FaceDetection.getClient(opts);

        try {
            embedder = new FaceEmbeddingProcessor(getAssets());
        } catch (Exception e) {
            Toast.makeText(this, "Model load failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        camPerm = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                g -> { if (g) loadGalleryThenStart(); else finish(); });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            camPerm.launch(Manifest.permission.CAMERA);
        } else {
            loadGalleryThenStart();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { if (provider!=null) provider.unbindAll(); } catch (Exception ignored) {}
        try { if (detector!=null) detector.close(); } catch (Exception ignored) {}
        try { if (embedder!=null) embedder.close(); } catch (Exception ignored) {}
        if (exec!=null) exec.shutdown();
    }

    /** Load this user’s embeddings and L2-normalize them. */
    private void loadGalleryThenStart() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }

        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("faceEmbeddings").child(user.getUid());
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                gallery.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    String vec = String.valueOf(c.child("vec").getValue());
                    float[] f = FaceUtils.base64ToFloats(vec);
                    if (f != null) gallery.add(FaceMatcher.l2norm(f));
                }
                if (gallery.isEmpty()) {
                    Toast.makeText(FaceRecognitionActivity.this,
                            "No enrolled face for your account.", Toast.LENGTH_LONG).show();
                    finish(); return;
                }
                startCamera();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Toast.makeText(FaceRecognitionActivity.this,
                        "Failed to load gallery: "+e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        tvHint.setText("Blink, then turn head (liveness).");
        liveness.reset();
        livenessPassed = false;
        framesSeen = 0; agreeCount = 0; lastBestScore = -2f;

        ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            try {
                provider = fut.get();
                provider.unbindAll();

                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());

                ImageAnalysis ia = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                ia.setAnalyzer(exec, this::analyze);

                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, p, ia);
            } catch (Exception e) {
                Toast.makeText(this, "Camera start failed: "+e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(@NonNull ImageProxy image) {
        try {
            if (image.getImage() == null) { image.close(); return; }
            int rot = image.getImageInfo().getRotationDegrees();
            InputImage ii = InputImage.fromMediaImage(image.getImage(), rot);

            detector.process(ii)
                    .addOnSuccessListener(faces -> {
                        if (faces.isEmpty()) { updateHint("No face detected."); return; }
                        Face face = faces.get(0);

                        if (!livenessPassed) {
                            LivenessGuard.Result lr = liveness.update(face);
                            updateHint(lr.hint);
                            if (!lr.passed) return;
                            livenessPassed = true;
                            updateHint("Liveness OK. Hold still for identity…");
                            return;
                        }

                        Bitmap frame = FaceUtils.imageProxyToBitmap(image);
                        if (frame == null) { updateHint("Frame error"); return; }

                        // Use SAFE crop with 20% margin
                        Bitmap crop = FaceUtils.cropAlignResize(
                                frame, face, FaceEmbeddingProcessor.INPUT_SIZE, 0.20f);
                        if (crop == null) { updateHint("Face crop error"); return; }

                        float[][][][] in = FaceUtils.bitmapToInput(crop);
                        float[] probe = embedder.embed(in);
                        FaceMatcher.l2norm(probe);
                        framesSeen++;

                        // Check against gallery
                        boolean ok = FaceMatcher.acceptForUser(
                                probe, gallery, STRONG_THRESHOLD, SECONDARY_THRESHOLD, MIN_AGREE_REQUIRED);

                        // For quick tuning: compute best score (again) for a tiny debug toast
                        float best = -2f;
                        for (float[] g : gallery) {
                            float s = FaceMatcher.cosine(probe, g);
                            if (s > best) best = s;
                        }
                        lastBestScore = best;

                        if (ok) agreeCount++;

                        if (framesSeen >= MIN_DECISION_FRAMES) {
                            finishDecision(agreeCount >= MIN_AGREE_REQUIRED);
                        } else {
                            updateHint("Verifying… (" + agreeCount + "/" + framesSeen + ")");
                        }
                    })
                    .addOnFailureListener(e -> updateHint("Detect failed"))
                    .addOnCompleteListener(t -> image.close());
        } catch (Exception e) {
            image.close();
        }
    }

    private void finishDecision(boolean pass) {
        runOnUiThread(() -> {
            // Small debug toast so you can tune thresholds for your phone/lighting
            Toast.makeText(this, "best cos=" + String.format("%.2f", lastBestScore), Toast.LENGTH_SHORT).show();

            if (!pass) {
                Toast.makeText(this, "Face not recognized. Try again.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                // Only mark for the logged-in user; helper handles open-session check.
                AttendanceHelper.writePresent(
                        FaceRecognitionActivity.this,
                        user.getUid(),
                        "geofence+liveness+fr"
                );
            }
            Toast.makeText(this, "Verified ✓ Attendance marked.", Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void updateHint(String s) {
        runOnUiThread(() -> tvHint.setText(s));
    }
}
