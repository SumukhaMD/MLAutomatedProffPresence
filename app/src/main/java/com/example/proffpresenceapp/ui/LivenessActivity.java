package com.example.proffpresenceapp.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.proffpresenceapp.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LivenessActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> camPerm;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) Require signed-in user. If not, go to Login first.
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }

        setContentView(R.layout.activity_liveness); // your layout with PreviewView etc.

        // 2) Request camera permission if missing, then start camera
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
    }

    private void startCamera() {
        // TODO: Your existing CameraX setup & face verification logic here.
        // E.g., bind preview, analyzer, when recognized -> AttendanceHelper.writeAttendance(...), finish()
    }
}
