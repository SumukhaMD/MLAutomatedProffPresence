package com.example.proffpresenceapp.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Size;
import android.widget.Button;
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
import com.example.proffpresenceapp.ui.core.NotificationHelper;
import com.example.proffpresenceapp.ui.ml.FaceEmbeddingProcessor;
import com.example.proffpresenceapp.ui.ml.FaceUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enrollment: capture 5 face samples -> save to RTDB -> flip professors/{uid} to enrolled.
 */
public class RegisterFaceActivity extends AppCompatActivity {

    private static final int TARGET_SAMPLES = 5;

    private PreviewView preview;
    private Button btnCapture;
    private TextView tvSteps;

    private final List<float[]> samples = new ArrayList<>(TARGET_SAMPLES);

    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis idleAnalysis;
    private ImageAnalysis oneShotAnalysis;

    private FaceDetector detector;
    private FaceEmbeddingProcessor embedder;

    private boolean saving = false;     // prevents double “save”
    private boolean analyzing = false;  // prevents double-frame per tap

    private ActivityResultLauncher<String> camPerm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register_face);

        preview    = findViewById(R.id.previewReg);
        btnCapture = findViewById(R.id.btnCapture);
        tvSteps    = findViewById(R.id.tvSteps);

        cameraExecutor = Executors.newSingleThreadExecutor();

        detector = FaceDetection.getClient(
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .enableTracking()
                        .build()
        );

        try {
            embedder = new FaceEmbeddingProcessor(getAssets());
        } catch (Exception e) {
            Toast.makeText(this, "Model load failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        camPerm = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) startCamera();
                    else {
                        Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            camPerm.launch(Manifest.permission.CAMERA);
        } else {
            startCamera();
        }

        btnCapture.setOnClickListener(v -> captureOne());
        updateCounter();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (idleAnalysis != null) idleAnalysis.clearAnalyzer(); } catch (Exception ignored) {}
        try { if (oneShotAnalysis != null) oneShotAnalysis.clearAnalyzer(); } catch (Exception ignored) {}
        try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
        try { if (detector != null) detector.close(); } catch (Exception ignored) {}
        try { if (embedder != null) embedder.close(); } catch (Exception ignored) {}
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    private void updateCounter() {
        tvSteps.setText("Captured " + samples.size() + " / " + TARGET_SAMPLES);
    }

    /* ---------------- Camera binding ---------------- */

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            try {
                cameraProvider = fut.get();
                cameraProvider.unbindAll();

                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());

                idleAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                idleAnalysis.setAnalyzer(cameraExecutor, ImageProxy::close);

                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        p,
                        idleAnalysis
                );

            } catch (Exception e) {
                Toast.makeText(this, "Camera start failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /* ------------- One-tap → one frame → one vector ------------- */

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void captureOne() {
        if (saving) return;
        if (samples.size() >= TARGET_SAMPLES) return;
        if (cameraProvider == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        analyzing = false; // reset flag for this capture

        oneShotAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        oneShotAnalysis.setAnalyzer(cameraExecutor, this::analyzeOneFrame);

        cameraProvider.unbindAll();
        Preview p = new Preview.Builder().build();
        p.setSurfaceProvider(preview.getSurfaceProvider());

        cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                p,
                oneShotAnalysis
        );
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeOneFrame(@NonNull ImageProxy image) {
        try {
            if (image.getImage() == null) { image.close(); return; }

            // Debounce: ensure exactly one frame processed per tap
            if (analyzing) { image.close(); return; }
            analyzing = true;

            // Disarm analyzer soon to avoid multiple callbacks
            if (oneShotAnalysis != null) oneShotAnalysis.clearAnalyzer();

            int rot = image.getImageInfo().getRotationDegrees();
            InputImage ii = InputImage.fromMediaImage(image.getImage(), rot);

            detector.process(ii)
                    .addOnSuccessListener(faces -> {
                        if (faces == null || faces.isEmpty()) return;

                        Bitmap frame = FaceUtils.imageProxyToBitmap(image);
                        if (frame == null) {
                            runOnUiThread(() -> Toast.makeText(this, "Frame convert failed", Toast.LENGTH_SHORT).show());
                            return;
                        }

                        Face f = faces.get(0);
                        Bitmap faceBmp = FaceUtils.cropAlignResize(frame, f, FaceEmbeddingProcessor.INPUT_SIZE);
                        if (faceBmp == null) return;

                        float[][][][] in = FaceUtils.bitmapToInput(faceBmp);
                        float[] vec = embedder.embed(in);
                        if (vec == null) return;

                        samples.add(vec);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Sample " + samples.size() + " captured", Toast.LENGTH_SHORT).show();
                            updateCounter();

                            if (samples.size() >= TARGET_SAMPLES) {
                                saving = true;
                                btnCapture.setEnabled(false);
                                try { if (cameraProvider != null) cameraProvider.unbindAll(); } catch (Exception ignored) {}
                                saveAllToFirebase();   // <- STEP 3: save & enroll
                            } else {
                                startCamera(); // go back to idle preview
                            }
                        });
                    })
                    .addOnFailureListener(e ->
                            runOnUiThread(() ->
                                    Toast.makeText(this, "Detect failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()))
                    .addOnCompleteListener(t -> image.close());

        } catch (Exception e) {
            image.close();
        }
    }

    /** STEP 3: push embeddings and atomically flip professors/{uid} to enrolled */
    private void saveAllToFirebase() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        if (uid == null) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_LONG).show();
            saving = false;
            btnCapture.setEnabled(true);
            startCamera();
            return;
        }

        long now = System.currentTimeMillis();
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        DatabaseReference embRef = root.child("faceEmbeddings").child(uid);

        // 1) Push all samples (requires professors/{uid}/allowEnroll === true)
        //    We push sequentially to surface any permission errors early.
        pushNextEmbedding(embRef, 0, now, new Runnable() {
            @Override public void run() {
                // 2) Atomic update on professors/{uid} to satisfy your write rule:
                //    allowEnroll: true -> false, enrollmentStatus: "pending" -> "enrolled"
                DatabaseReference profRef = root.child("professors").child(uid);
                Map<String,Object> upd = new HashMap<>();
                upd.put("allowEnroll", false);
                upd.put("enrollmentStatus", "enrolled");

                profRef.updateChildren(upd)
                        .addOnSuccessListener(unused -> {
                            NotificationHelper.showSimple(
                                    RegisterFaceActivity.this,
                                    "Enrollment complete", "You're enrolled.", 2024);
                            finish();
                        })
                        .addOnFailureListener(e -> {
                            saving = false;
                            btnCapture.setEnabled(true);
                            Toast.makeText(RegisterFaceActivity.this,
                                    "Enroll flip failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            startCamera();
                        });
            }
        });
    }

    /** Push embeddings one-by-one to catch any RTDB permission errors clearly. */
    private void pushNextEmbedding(DatabaseReference embRef, int index, long ts, Runnable onAllDone) {
        if (index >= samples.size()) {
            onAllDone.run();
            return;
        }
        float[] vec = samples.get(index);
        String b64 = FaceUtils.floatsToBase64(vec);

        Map<String,Object> row = new HashMap<>();
        row.put("vec", b64);
        row.put("ts", ts);

        embRef.push().setValue(row)
                .addOnSuccessListener(unused -> pushNextEmbedding(embRef, index + 1, ts, onAllDone))
                .addOnFailureListener(e -> {
                    saving = false;
                    btnCapture.setEnabled(true);
                    Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    startCamera();
                });
    }
}
