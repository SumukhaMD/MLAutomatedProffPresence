package com.example.proffpresenceapp.ui.ml;

import android.content.res.AssetManager;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class FaceEmbeddingProcessor implements AutoCloseable {

    public static final int INPUT_SIZE = 112; // adapt if your model differs

    private Interpreter tflite;

    public FaceEmbeddingProcessor(AssetManager am) throws IOException {
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(2);
        // opts.setUseXNNPACK(true); // optional
        tflite = new Interpreter(loadModel(am, "mobile_face_net.tflite"), opts);
    }

    private MappedByteBuffer loadModel(AssetManager am, String assetPath) throws IOException {
        try (FileInputStream fis = new FileInputStream(am.openFd(assetPath).getFileDescriptor());
             FileChannel fc = fis.getChannel()) {
            long start = am.openFd(assetPath).getStartOffset();
            long len = am.openFd(assetPath).getLength();
            return fc.map(FileChannel.MapMode.READ_ONLY, start, len);
        }
    }

    /** Run inference: input NHWC [1,112,112,3] in [-1,1], output L2-normalized 128D */
    public float[] embed(float[][][][] input) {
        if (tflite == null) return null;

        // Determine output length dynamically (most MobileFaceNet=128)
        int outLen = 128;
        try {
            int[] outShape = tflite.getOutputTensor(0).shape(); // [1,128]
            if (outShape.length == 2) outLen = outShape[1];
        } catch (Exception ignored){}

        float[][] out = new float[1][outLen];
        tflite.run(input, out);
        float[] v = out[0];
        l2normInPlace(v);
        return v;
    }

    public static void l2normInPlace(float[] v) {
        double s=0; for (float x: v) s += x*x;
        s = Math.sqrt(Math.max(s, 1e-12));
        for (int i=0;i<v.length;i++) v[i] /= s;
    }

    @Override public void close() { if (tflite != null) { tflite.close(); tflite = null; } }
}
