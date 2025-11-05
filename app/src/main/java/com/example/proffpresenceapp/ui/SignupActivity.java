package com.example.proffpresenceapp.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.proffpresenceapp.R;
import com.google.firebase.auth.FirebaseAuth;

public class SignupActivity extends AppCompatActivity {
    private EditText etEmail, etPassword, etConfirm;
    private FirebaseAuth auth;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_signup);

        auth = FirebaseAuth.getInstance();
        etEmail    = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirm  = findViewById(R.id.etConfirm);

        Button btnCreate    = findViewById(R.id.btnCreate);
        Button btnGotoLogin = findViewById(R.id.btnGotoLogin);

        btnCreate.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            String pass  = etPassword.getText().toString().trim();
            String conf  = etConfirm.getText().toString().trim();

            if (email.isEmpty()) { toast("Enter email"); return; }
            if (pass.length() < 6) { toast("Password must be ≥ 6 characters"); return; }
            if (!pass.equals(conf)) { toast("Passwords do not match"); return; }

            btnCreate.setEnabled(false);
            auth.createUserWithEmailAndPassword(email, pass)
                    .addOnSuccessListener(r -> {
                        // Make teacher visible to admin list
                        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("professors").child(uid)
                                .updateChildren(new java.util.HashMap<String,Object>() {{
                                    put("email", email);
                                    put("allowEnroll", false);
                                    put("enrollmentStatus", "pending");
                                }});
                        toast("Account created. Please log in.");
                        finish();
                    })
                    .addOnFailureListener(e -> { btnCreate.setEnabled(true); toast("Sign up failed: " + e.getMessage()); });
        });

        btnGotoLogin.setOnClickListener(v -> finish());
    }

    private void toast(String s){ Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
