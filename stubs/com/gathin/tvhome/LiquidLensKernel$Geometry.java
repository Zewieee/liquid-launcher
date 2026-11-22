package com.gathin.tvhome;

public final class LiquidLensKernel$Geometry {
    public final int[] alpha;
    public final float bevel;
    public final float[] dispersion;
    public final int height;
    public final float lensHeight;
    public final float lensWidth;
    public final float lightX;
    public final float lightY;
    public final float[] offsetX;
    public final float[] offsetY;
    public final float radius;
    public final float[] specular;
    public final int width;

    public LiquidLensKernel$Geometry(int width, int height, float lensWidth, float lensHeight,
                                     float radius, float bevel, float lightX, float lightY) {
        this.width = width;
        this.height = height;
        this.lensWidth = lensWidth;
        this.lensHeight = lensHeight;
        this.radius = radius;
        this.bevel = bevel;
        this.lightX = lightX;
        this.lightY = lightY;
        int n = width * height;
        this.alpha = new int[n];
        this.offsetX = new float[n];
        this.offsetY = new float[n];
        this.dispersion = new float[n];
        this.specular = new float[n];
    }
}
