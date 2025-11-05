package com.example.proffpresenceapp.ui.core;

import androidx.annotation.NonNull;

import com.example.proffpresenceapp.ui.ml.FaceUtils;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FaceEmbedSaver {

    public interface Callback {
        void onSuccess();
        void onError(@NonNull String message);
    }

    /**
     * Push all embeddings, wait for completion, then atomically flip:
     * allowEnroll=false and enrollmentStatus="enrolled".
     */
    public static void saveAndEnroll(@NonNull List<float[]> samples, @NonNull Callback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { cb.onError("Not signed in"); return; }

        String uid = user.getUid();
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        DatabaseReference embRef = root.child("faceEmbeddings").child(uid);

        List<Task<Void>> writes = new ArrayList<>(samples.size());
        for (float[] v : samples) {
            String vec = FaceUtils.floatsToBase64(v);
            if (vec == null) { cb.onError("Encode failed"); return; }

            String key = embRef.push().getKey();
            Map<String,Object> row = new HashMap<>();
            row.put("vec", vec);
            row.put("ts", ServerValue.TIMESTAMP);
            writes.add(embRef.child(key).setValue(row));
        }

        Tasks.whenAllComplete(writes).addOnCompleteListener(all -> {
            for (Task<?> t : all.getResult()) {
                if (!t.isSuccessful()) {
                    Exception e = t.getException();
                    cb.onError("Embed write failed: " + (e != null ? e.getMessage() : "unknown"));
                    return;
                }
            }
            Map<String,Object> flip = new HashMap<>();
            flip.put("allowEnroll", false);
            flip.put("enrollmentStatus", "enrolled");

            root.child("professors").child(uid).updateChildren(flip)
                    .addOnSuccessListener(unused -> cb.onSuccess())
                    .addOnFailureListener(e -> cb.onError("Status update failed: " + e.getMessage()));
        });
    }

    private FaceEmbedSaver() {}
}
