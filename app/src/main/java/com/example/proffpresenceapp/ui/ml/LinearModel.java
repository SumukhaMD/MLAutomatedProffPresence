package com.example.proffpresenceapp.ui.ml;

public class LinearModel {
    public double[] mean;
    public double[] std;
    public double[] weights;
    public double bias;

    public double predict(double[] x) {
        if (mean == null || std == null || weights == null) return Double.NaN;
        double y = bias;
        for (int i=0;i<weights.length;i++) {
            double z = (std[i] != 0) ? (x[i] - mean[i]) / std[i] : (x[i] - mean[i]);
            y += weights[i] * z;
        }
        return y; // seconds
    }
}