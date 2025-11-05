package com.example.proffpresenceapp.ui.liveness;

import com.google.mlkit.vision.face.Face;

/**
 * Active liveness check using ML Kit face attributes.
 * Flow: user must (1) BLINK once, then (2) TURN HEAD left or right past a yaw threshold,
 * all within a time window. If too slow/no progress, it resets with a helpful hint.
 */
public class LivenessGuard {

    public static class Result {
        public final boolean passed;
        public final String hint;
        public Result(boolean p, String h){ passed = p; hint = h; }
        public static Result ok(){ return new Result(true, "Liveness passed"); }
        public static Result need(String h){ return new Result(false, h); }
    }

    private enum Stage { NEED_BLINK, NEED_TURN, DONE }

    private Stage stage = Stage.NEED_BLINK;
    private long startMs = System.currentTimeMillis();
    private long lastProgressMs = startMs;

    // ---- Tunables (adjust if needed) ----
    private final long maxSessionMs = 8000;  // 8s total to finish liveness
    private final long maxIdleMs    = 5000;  // reset if no progress for 5s

    // Eye open probabilities from ML Kit (0..1)
    private final float eyeCloseProb = 0.35f;  // <= this is "closed"
    private final float eyeOpenProb  = 0.65f;  // >= this is "open"

    // Head yaw angles (left negative, right positive)
    private final float yawLeftDeg   = -18f;   // turn head to left beyond -18°
    private final float yawRightDeg  =  18f;   // or to right beyond +18°

    // Internal blink tracking
    private boolean sawEyesClosed = false;

    /** Feed each detected face here—call this for every frame you get from ML Kit. */
    public Result update(Face f) {
        long now = System.currentTimeMillis();

        if (now - startMs > maxSessionMs) {
            reset();
            return Result.need("Timeout—blink, then turn head to verify liveness.");
        }
        if (now - lastProgressMs > maxIdleMs) {
            reset();
            return Result.need("No movement—blink, then turn head.");
        }

        switch (stage) {
            case NEED_BLINK: {
                Float l = f.getLeftEyeOpenProbability();
                Float r = f.getRightEyeOpenProbability();
                if (l != null && r != null) {
                    boolean eyesClosed = (l <= eyeCloseProb && r <= eyeCloseProb);
                    boolean eyesOpen   = (l >= eyeOpenProb  && r >= eyeOpenProb);

                    if (eyesClosed) {
                        sawEyesClosed = true;
                    } else if (sawEyesClosed && eyesOpen) {
                        // Completed a blink
                        stage = Stage.NEED_TURN;
                        lastProgressMs = now;
                        return Result.need("Good. Now slowly turn head left or right.");
                    }
                }
                return Result.need("Please blink.");
            }
            case NEED_TURN: {
                float yaw = f.getHeadEulerAngleY(); // ML Kit: left negative, right positive
                if (yaw <= yawLeftDeg || yaw >= yawRightDeg) {
                    stage = Stage.DONE;
                    lastProgressMs = now;
                    return Result.ok();
                }
                return Result.need("Turn head left/right (keep face in view).");
            }
            case DONE:
            default:
                return Result.ok();
        }
    }

    /** Reset the state machine (call before starting a new attempt). */
    public void reset() {
        stage = Stage.NEED_BLINK;
        startMs = System.currentTimeMillis();
        lastProgressMs = startMs;
        sawEyesClosed = false;
    }

    public boolean isPassed() { return stage == Stage.DONE; }
}
