package com.example.proffpresenceapp.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.proffpresenceapp.R;
import com.google.firebase.auth.FirebaseAuth;

public class LoginActivity extends AppCompatActivity {
    private EditText etEmail, etPassword;
    private TextView tvStatus;
    private FirebaseAuth auth;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        tvStatus = findViewById(R.id.tvStatus);

        Button btnLogin  = findViewById(R.id.btnLogin);
        Button btnSignup = findViewById(R.id.btnSignup);
        Button btnReset  = findViewById(R.id.btnReset);

        btnLogin.setOnClickListener(v -> doLogin());
        btnSignup.setOnClickListener(v -> startActivity(new Intent(this, SignupActivity.class)));
        btnReset.setOnClickListener(v -> doReset());

        // If already logged in, go straight to MainActivity
        if (auth.getCurrentUser() != null) {
            startActivity(new Intent(this, com.example.proffpresenceapp.ui.MainActivity.class));
            finish();
        }
    }

    private void doLogin() {
        String email = etEmail.getText().toString().trim();
        String pass  = etPassword.getText().toString().trim();
        if (email.isEmpty() || pass.length() < 6) {
            toast("Enter a valid email and a password (min 6 chars)");
            return;
        }
        tvStatus.setText("Signing in...");
        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    tvStatus.setText("");
                    startActivity(new Intent(this, com.example.proffpresenceapp.ui.MainActivity.class));
                    finish();
                })
                .addOnFailureListener(e -> {
                    tvStatus.setText("");
                    toast("Login failed: " + e.getMessage());
                });
    }

    private void doReset() {
        String email = etEmail.getText().toString().trim();
        if (email.isEmpty()) { toast("Enter your email first"); return; }
        auth.sendPasswordResetEmail(email)
                .addOnSuccessListener(v -> toast("Reset link sent"))
                .addOnFailureListener(e -> toast("Reset failed: " + e.getMessage()));
    }

    private void toast(String s){ Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
