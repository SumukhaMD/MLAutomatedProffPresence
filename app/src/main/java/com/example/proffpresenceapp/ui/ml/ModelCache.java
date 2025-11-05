package com.example.proffpresenceapp.ui.ml;

public final class ModelCache {
    private ModelCache(){}
    private static LinearModel LINEAR;
    private static RandomForestModel FOREST;

    public static void setLinear(LinearModel m){ LINEAR = m; }
    public static void setForest(RandomForestModel m){ FOREST = m; }
    public static LinearModel getLinear(){ return LINEAR; }
    public static RandomForestModel getForest(){ return FOREST; }
}
