package com.example.proffpresenceapp.ui.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RandomForestModel {
    public static class Node {
        public String kind;       // "node" or "leaf"
        public Integer attribute; // index
        public Double threshold;
        public Node left;
        public Node right;
        public Double value;      // for leaf
    }

    public List<Node> trees = new ArrayList<>();

    private double eval(Node n, double[] x) {
        while ("node".equals(n.kind)) {
            n = (x[n.attribute] <= n.threshold) ? n.left : n.right;
        }
        return n.value;
    }
    public double predict(double[] x) {
        if (trees.isEmpty()) return Double.NaN;
        double sum = 0;
        for (Node t: trees) sum += eval(t, x);
        return sum / trees.size();
    }

    /** Build tree from Firebase Map export. */
    @SuppressWarnings("unchecked")
    public static Node fromMap(Map<String,Object> m) {
        Node n = new Node();
        n.kind = (String)m.get("kind");
        if ("leaf".equals(n.kind)) {
            n.value = ((Number)m.get("value")).doubleValue();
            return n;
        }
        n.attribute = ((Number)m.get("attribute")).intValue();
        n.threshold = ((Number)m.get("threshold")).doubleValue();
        n.left  = fromMap((Map<String,Object>) m.get("left"));
        n.right = fromMap((Map<String,Object>) m.get("right"));
        return n;
    }
}