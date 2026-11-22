package local.launcherfix;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.gathin.tvhome.IconMaterialRenderer$Plate;
import com.gathin.tvhome.LiquidLensKernel$Geometry;
import com.gathin.tvhome.Shamsi;
import java.util.Arrays;

/** Bit-exact CPU pixel, blur, lens, and icon material fast paths with reusable buffers. */
public final class FastPixelOps {
    private static final Object SHARPEN_LOCK = new Object();
    private static final Object BLUR_LOCK = new Object();
    private static int[] blurPixels = new int[0];
    private static int[] blurR = new int[0];
    private static int[] blurG = new int[0];
    private static int[] blurB = new int[0];
    private static int[] blurTR = new int[0];
    private static int[] blurTG = new int[0];
    private static int[] blurTB = new int[0];

    private static final float LIGHT_X;
    private static final float LIGHT_Y;
    static {
        float fSqrt = (float) Math.sqrt(1.0000999f);
        if (fSqrt < 1.0E-4f) {
            LIGHT_X = -0.7071f;
            LIGHT_Y = -0.7071f;
        } else {
            LIGHT_X = (-0.65f) / fSqrt;
            LIGHT_Y = (-0.76f) / fSqrt;
        }
    }

    private FastPixelOps() {}

    /** Mild unsharp mask for covers that are enlarged for the popup; keeps source pixels bounded. */
    public static Bitmap sharpen(Bitmap bitmap, float amount) {
        if (bitmap == null || bitmap.isRecycled() || amount <= 0f) return bitmap;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 3 || height < 3) return bitmap;
        synchronized (SHARPEN_LOCK) {
            int[] prev = new int[width], curr = new int[width], next = new int[width], out = new int[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(curr, 0, width, 0, y, width, 1);
                bitmap.getPixels(prev, 0, width, 0, Math.max(0, y - 1), width, 1);
                bitmap.getPixels(next, 0, width, 0, Math.min(height - 1, y + 1), width, 1);
                for (int x = 0; x < width; x++) {
                    int center = curr[x];
                    int sumR = 0, sumG = 0, sumB = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int[] row = dy < 0 ? prev : dy > 0 ? next : curr;
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int p = row[Math.max(0, Math.min(width - 1, x + dx))];
                            sumR += (p >> 16) & 255; sumG += (p >> 8) & 255; sumB += p & 255;
                        }
                    }
                    int r = clampByte(((center >> 16) & 255) + amount * (((center >> 16) & 255) - sumR / 8f));
                    int g = clampByte(((center >> 8) & 255) + amount * (((center >> 8) & 255) - sumG / 8f));
                    int b = clampByte((center & 255) + amount * ((center & 255) - sumB / 8f));
                    out[x] = (center & 0xff000000) | (r << 16) | (g << 8) | b;
                }
                bitmap.setPixels(out, 0, width, 0, y, width, 1);
            }
        }
        return bitmap;
    }

    private static float clampF(float f, float min, float max) {
        return Math.max(min, Math.min(max, f));
    }

    private static int clampByte(float f) {
        return Math.max(0, Math.min(255, Math.round(f)));
    }

    private static int mixByte(int a, int b, float t) {
        return clampByte(((b - a) * t) + a);
    }

    private static int contrastRgb(int pixel, int r, int g, int b) {
        return Math.max(Math.abs(((pixel >> 16) & 255) - r),
            Math.max(Math.abs(((pixel >> 8) & 255) - g), Math.abs((pixel & 255) - b)));
    }

    /** Simultaneous 3-channel box blur (3 passes, radius 4) with zero per-call heap allocations. */
    public static Bitmap blur(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int n = width * height;
        if (width <= 0 || height <= 0 || n <= 0) return bitmap;
        synchronized (BLUR_LOCK) {
            if (RustBlur.tryBlur(bitmap)) return bitmap;
            if (blurPixels.length < n) {
                blurPixels = new int[n];
                blurR = new int[n];
                blurG = new int[n];
                blurB = new int[n];
                blurTR = new int[n];
                blurTG = new int[n];
                blurTB = new int[n];
            }
            int[] pixels = blurPixels;
            int[] r = blurR, g = blurG, b = blurB;
            int[] tr = blurTR, tg = blurTG, tb = blurTB;
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int i = 0; i < n; i++) {
                int p = pixels[i];
                r[i] = (p >> 16) & 255;
                g[i] = (p >> 8) & 255;
                b[i] = p & 255;
            }
            for (int pass = 0; pass < 3; pass++) {
                box3(r, g, b, tr, tg, tb, width, height, 4);
            }
            for (int i = 0; i < n; i++) {
                pixels[i] = 0xFF000000 | (r[i] << 16) | (g[i] << 8) | b[i];
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        }
        return bitmap;
    }

    public static void box3(int[] r, int[] g, int[] b, int[] tr, int[] tg, int[] tb,
                            int width, int height, int radius) {
        int div = (radius * 2) + 1;
        int maxW = width - 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int sumR = 0, sumG = 0, sumB = 0;
            for (int k = -radius; k <= radius; k++) {
                int idx = row + Math.max(0, Math.min(maxW, k));
                sumR += r[idx];
                sumG += g[idx];
                sumB += b[idx];
            }
            for (int x = 0; x < width; x++) {
                int out = row + x;
                tr[out] = sumR / div;
                tg[out] = sumG / div;
                tb[out] = sumB / div;
                int addIdx = row + Math.min(maxW, x + radius + 1);
                int subIdx = row + Math.max(0, x - radius);
                sumR += r[addIdx] - r[subIdx];
                sumG += g[addIdx] - g[subIdx];
                sumB += b[addIdx] - b[subIdx];
            }
        }
        int maxH = height - 1;
        for (int x = 0; x < width; x++) {
            int sumR = 0, sumG = 0, sumB = 0;
            for (int k = -radius; k <= radius; k++) {
                int idx = (Math.max(0, Math.min(maxH, k)) * width) + x;
                sumR += tr[idx];
                sumG += tg[idx];
                sumB += tb[idx];
            }
            for (int y = 0; y < height; y++) {
                int out = (y * width) + x;
                r[out] = sumR / div;
                g[out] = sumG / div;
                b[out] = sumB / div;
                int addIdx = (Math.min(maxH, y + radius + 1) * width) + x;
                int subIdx = (Math.max(0, y - radius) * width) + x;
                sumR += tr[addIdx] - tr[subIdx];
                sumG += tg[addIdx] - tg[subIdx];
                sumB += tb[addIdx] - tb[subIdx];
            }
        }
    }

    /** Glass geometry preserves refraction and clipping without a bright specular rim. */
    public static void fillGeometry(LiquidLensKernel$Geometry g) {
        int i3 = g.width;
        int i4 = g.height;
        float f11 = g.lensWidth;
        float f12 = g.lensHeight;
        float f13 = g.radius;
        float f14 = g.bevel;
        int[] alpha = g.alpha;
        float[] offsetX = g.offsetX;
        float[] offsetY = g.offsetY;
        float[] dispersion = g.dispersion;
        float[] specular = g.specular;

        float f15 = f11 / i3;
        float f16 = f12 / i4;
        float fMax = Math.max(f15, f16) * 1.25f;
        float f17 = f12 * 0.5f;
        float f19 = f11 * 0.5f;
        int i7 = 0;
        for (int i6 = 0; i6 < i4; i6++) {
            float f18 = ((i6 + 0.5f) * f16) - f17;
            float fAbs2 = (Math.abs(f18) - f17) + f13;
            float fMax3 = Math.max(fAbs2, 0.0f);
            float fMax3Sq = fMax3 * fMax3;
            for (int i8 = 0; i8 < i3; i8++, i7++) {
                float f20 = ((i8 + 0.5f) * f15) - f19;
                float fAbs = (Math.abs(f20) - f19) + f13;
                float fMax2 = Math.max(fAbs, 0.0f);
                float fSqrt = (fMax2 == 0.0f && fMax3 == 0.0f)
                    ? 0.0f
                    : (float) Math.sqrt(fMax3Sq + (fMax2 * fMax2));
                float fMin = (Math.min(Math.max(fAbs, fAbs2), 0.0f) + fSqrt) - f13;
                int iClamp = (int) ((clampF(0.5f - (fMin / fMax), 0.0f, 1.0f) * 255.0f) + 0.5f);
                alpha[i7] = iClamp;
                specular[i7] = 0.0f;
                if (iClamp != 0) {
                    float f8, f9;
                    if (fSqrt > 1.0E-4f) {
                        f8 = (fMax2 / fSqrt) * (f20 < 0.0f ? -1.0f : 1.0f);
                        f9 = (fMax3 / fSqrt) * (f18 >= 0.0f ? 1.0f : -1.0f);
                    } else if (fAbs > fAbs2) {
                        f8 = f20 < 0.0f ? -1.0f : 1.0f;
                        f9 = 0.0f;
                    } else {
                        f8 = 0.0f;
                        f9 = f18 < 0.0f ? -1.0f : 1.0f;
                    }
                    float f27 = -fMin;
                    float fClamp3 = 1.0f / ((clampF(f27 / f14, 0.0f, 1.0f) * 4.0f) + 1.0f);
                    float f28 = ((fClamp3 * fClamp3) - 0.04f) / 0.96f;
                    offsetX[i7] = (-f8) * f28;
                    offsetY[i7] = (-f9) * f28;
                    dispersion[i7] = f28 * 0.16f;
                }
            }
        }
    }

    /** Bit-exact lens renderer with single-fetch RGB interpolation when dispersion vanishes. */
    public static void renderLens(Bitmap bitmap, Bitmap bitmap2, float f, float f2,
                                  float f3, float f4, float f5, float f6, float f7, float f8) {
        synchronized (Shamsi.class) {
            if (bitmap == null || bitmap2 == null || bitmap.isRecycled() || bitmap2.isRecycled()
                    || f3 <= 0.0f || f4 <= 0.0f || !bitmap2.isMutable()) {
                return;
            }
            int width = bitmap2.getWidth();
            int height = bitmap2.getHeight();
            if (width <= 0 || height <= 0) return;
            int width2 = bitmap.getWidth();
            int height2 = bitmap.getHeight();
            if (width2 < 2 || height2 < 2) return;

            int generationId = bitmap.getGenerationId();
            if (Shamsi.sourceBitmap != bitmap || Shamsi.sourceGeneration != generationId
                    || Shamsi.sourceW != width2 || Shamsi.sourceH != height2) {
                int count = width2 * height2;
                if (Shamsi.sourcePixels.length < count) {
                    Shamsi.sourcePixels = new int[count];
                }
                bitmap.getPixels(Shamsi.sourcePixels, 0, width2, 0, 0, width2, height2);
                Shamsi.sourceBitmap = bitmap;
                Shamsi.sourceGeneration = generationId;
                Shamsi.sourceW = width2;
                Shamsi.sourceH = height2;
            }
            int outCount = width * height;
            if (Shamsi.resultPixels.length < outCount) {
                Shamsi.resultPixels = new int[outCount];
            }
            float f9 = LIGHT_X;
            float f10 = LIGHT_Y;
            float fMax = Math.max(0.0f, Math.min(f5, Math.min(f3, f4) * 0.5f));
            float fMax2 = Math.max(1.0f, f6);

            LiquidLensKernel$Geometry[] cache = Shamsi.CACHE;
            LiquidLensKernel$Geometry geom = null;
            for (int i3 = 0; i3 < 8; i3++) {
                LiquidLensKernel$Geometry cand = cache[i3];
                if (cand != null && cand.width == width && cand.height == height
                        && Math.abs(cand.lensWidth - f3) < 0.01f
                        && Math.abs(cand.lensHeight - f4) < 0.01f
                        && Math.abs(cand.radius - fMax) < 0.01f
                        && Math.abs(cand.bevel - fMax2) < 0.01f
                        && Math.abs(cand.lightX - f9) < 0.02f
                        && Math.abs(cand.lightY - f10) < 0.02f) {
                    geom = cand;
                    break;
                }
            }
            if (geom == null) {
                geom = new LiquidLensKernel$Geometry(width, height, f3, f4, fMax, fMax2, f9, f10);
                int slot = Shamsi.nextCache;
                cache[slot] = geom;
                Shamsi.nextCache = (slot + 1) % 8;
            }

            if (!RustPixels.lens(Shamsi.sourcePixels, Shamsi.resultPixels, geom, width2, height2,
                    f, f2, f3, f4, f7, f8)) {
                lensJava(Shamsi.sourcePixels, Shamsi.resultPixels, geom, width2, height2,
                    f, f2, f3, f4, f7, f8);
            }
            bitmap2.setPixels(Shamsi.resultPixels, 0, width, 0, 0, width, height);
        }
    }

    static void lensJava(int[] source, int[] result, LiquidLensKernel$Geometry geom, int width2, int height2,
                         float f, float f2, float f3, float f4, float f7, float f8) {
        int width = geom.width, height = geom.height;
            float fClamp = clampF(f8, 0.0f, 1.0f);
            float fMax3 = ((0.6f * fClamp) + 1.0f) * Math.max(0.0f, f7);
            float f12 = 1.0f - (fClamp * 0.35f);
            float f13 = f3 / width;
            float f14 = f4 / height;

            int[] alphaArr = geom.alpha;
            float[] offXArr = geom.offsetX;
            float[] offYArr = geom.offsetY;
            float[] dispArr = geom.dispersion;
            float[] specArr = geom.specular;
            int[] srcPixels = source;
            int[] dstPixels = result;
            float maxX = width2 - 1.001f;
            float maxY = height2 - 1.001f;

            int i5 = 0;
            for (int i6 = 0; i6 < height; i6++) {
                float f15 = ((i6 + 0.5f) * f14) + f2;
                for (int i7 = 0; i7 < width; i7++, i5++) {
                    int i8 = alphaArr[i5];
                    if (i8 == 0) {
                        dstPixels[i5] = 0;
                        continue;
                    }
                    float f16 = ((i7 + 0.5f) * f13) + f;
                    float f17 = offXArr[i5] * fMax3;
                    float f18 = offYArr[i5] * fMax3;
                    float f19 = dispArr[i5];
                    float f20 = 1.0f - f19;
                    float rx = (f17 * f20) + f16;
                    float ry = (f20 * f18) + f15;
                    float gx = f16 + f17;
                    float gy = f15 + f18;
                    float f21 = f19 + 1.0f;
                    float bx = (f17 * f21) + f16;
                    float by = (f18 * f21) + f15;

                    int chR, chG, chB;
                    if (rx == gx && gx == bx && ry == gy && gy == by) {
                        float cx = clampF(gx - 0.5f, 0.0f, maxX);
                        float cy = clampF(gy - 0.5f, 0.0f, maxY);
                        int ix = (int) cx;
                        int iy = (int) cy;
                        float fx = cx - ix;
                        float fy = cy - iy;
                        int base = (iy * width2) + ix;
                        int p00 = srcPixels[base];
                        int p01 = srcPixels[base + 1];
                        int row2 = base + width2;
                        int p10 = srcPixels[row2];
                        int p11 = srcPixels[row2 + 1];

                        int r00 = (p00 >>> 16) & 255, r01 = (p01 >>> 16) & 255;
                        int r10 = (p10 >>> 16) & 255, r11 = (p11 >>> 16) & 255;
                        float rTop = ((r01 - r00) * fx) + r00;
                        chR = (int) ((((((r11 - r10) * fx) + r10) - rTop) * fy) + rTop + 0.5f);

                        int g00 = (p00 >>> 8) & 255, g01 = (p01 >>> 8) & 255;
                        int g10 = (p10 >>> 8) & 255, g11 = (p11 >>> 8) & 255;
                        float gTop = ((g01 - g00) * fx) + g00;
                        chG = (int) ((((((g11 - g10) * fx) + g10) - gTop) * fy) + gTop + 0.5f);

                        int b00 = p00 & 255, b01 = p01 & 255;
                        int b10 = p10 & 255, b11 = p11 & 255;
                        float bTop = ((b01 - b00) * fx) + b00;
                        chB = (int) ((((((b11 - b10) * fx) + b10) - bTop) * fy) + bTop + 0.5f);
                    } else {
                        chR = sampleChannelFast(srcPixels, width2, maxX, maxY, rx, ry, 16);
                        chG = sampleChannelFast(srcPixels, width2, maxX, maxY, gx, gy, 8);
                        chB = sampleChannelFast(srcPixels, width2, maxX, maxY, bx, by, 0);
                    }

                    float f22 = specArr[i5] * f12;
                    int outR = Math.max(0, Math.min(255, (int) ((chR * 0.97f) + 7.65f + f22)));
                    int outG = Math.max(0, Math.min(255, (int) (((chG * 0.97f) + 7.65f) + f22)));
                    int outB = Math.max(0, Math.min(255, (int) ((chB * 0.97f) + 7.65f + f22)));
                    dstPixels[i5] = (i8 << 24) | (outR << 16) | (outG << 8) | outB;
                }
            }
    }

    private static int sampleChannelFast(int[] srcPixels, int width2, float maxX, float maxY,
                                         float x, float y, int shift) {
        float fClamp = clampF(x - 0.5f, 0.0f, maxX);
        float fClamp2 = clampF(y - 0.5f, 0.0f, maxY);
        int i2 = (int) fClamp;
        int i3 = (int) fClamp2;
        float f3 = fClamp - i2;
        float f4 = fClamp2 - i3;
        int i5 = (i3 * width2) + i2;
        int i6 = (srcPixels[i5] >>> shift) & 255;
        int i7 = (srcPixels[i5 + 1] >>> shift) & 255;
        int i8 = i5 + width2;
        int i9 = (srcPixels[i8] >>> shift) & 255;
        int i10 = (srcPixels[i8 + 1] >>> shift) & 255;
        float f5 = ((i7 - i6) * f3) + i6;
        return (int) ((((((i10 - i9) * f3) + i9) - f5) * f4) + f5 + 0.5f);
    }

    /** Bit-exact icon recolouring that hoists row-invariant Math.pow out of the inner pixel loop. */
    public static void recolour(int[] iArr, int i, int i2, int i3, boolean z, boolean z2,
                                IconMaterialRenderer$Plate plate) {
        if (RustPixels.recolour(iArr, i, i2, i3, z, z2, plate)) return;
        recolourJava(iArr, i, i2, i3, z, z2, plate);
    }

    static void recolourJava(int[] iArr, int i, int i2, int i3, boolean z, boolean z2,
                             IconMaterialRenderer$Plate plate) {
        boolean z3 = i3 != 0;
        boolean z4 = plate.found;
        int i4 = 0;
        if (z2 && !z4) {
            for (int p : iArr) {
                if ((p >>> 24) >= 220) {
                    i4++;
                }
            }
        }
        boolean z5 = z2 && !z4 && ((float) i4) > ((float) iArr.length) * 0.65f;
        int iRed = z3 ? Color.red(i3) : 225;
        int iGreen = z3 ? Color.green(i3) : 241;
        int iBlue = z3 ? Color.blue(i3) : 255;
        int i7 = plate.red;
        int i8 = plate.green;
        int i9 = plate.blue;
        float fMax = Math.max(1, Math.max(Math.max(i7, 255 - i7),
            Math.max(Math.max(i8, 255 - i8), Math.max(i9, 255 - i9))));
        int i11 = 0;
        for (int i10 = 0; i10 < i2; i10++) {
            float f = i2 <= 1 ? 0.0f : i10 / (float) (i2 - 1);
            float f5 = 1.0f - f;
            float fPow = z2
                ? (((float) Math.pow(Math.max(0.0f, 1.0f - (Math.abs(f - 0.24f) / 0.16f)), 2.0d)) * 0.15f) + (f5 * 0.24f) + 0.56f
                : 0.0f;
            for (int i13 = 0; i13 < i; i13++, i11++) {
                int i15 = iArr[i11];
                int iAlpha = i15 >>> 24;
                if (iAlpha == 0) continue;
                int iRed2 = (i15 >> 16) & 255;
                int iGreen2 = (i15 >> 8) & 255;
                int iBlue2 = i15 & 255;
                int iContrast = contrastRgb(i15, i7, i8, i9);
                float fMin = z4 ? (iContrast <= 24 ? 0.0f : Math.min(1.0f, iContrast / fMax)) : 1.0f;
                if (z || z2) {
                    int iClamp = iRed2;
                    if (z4) {
                        iAlpha = clampByte(iAlpha * fMin);
                        if (iAlpha == 0) {
                            iArr[i11] = 0;
                        } else if (!z3 && !z2 && fMin > 0.0f) {
                            float f4 = 1.0f - fMin;
                            iClamp = clampByte((iClamp - (i7 * f4)) / fMin);
                            iGreen2 = clampByte((iGreen2 - (i8 * f4)) / fMin);
                            iBlue2 = clampByte((iBlue2 - (i9 * f4)) / fMin);
                        }
                    } else if (z) {
                        iAlpha = clampByte(iAlpha * 0.78f);
                    }
                    if (z2) {
                        float f2 = i <= 1 ? 0.0f : i13 / (float) (i - 1);
                        float f6 = ((1.0f - f2) * 0.17f * f5) + 0.1f;
                        if (z5) {
                            float f7 = iClamp;
                            float f8 = iGreen2;
                            float f9 = iBlue2;
                            float f10 = (0.0722f * f9) + (0.7152f * f8) + (0.2126f * f7);
                            int iMix2, iMix3, iMix;
                            if (z3) {
                                float f11 = ((f10 / 255.0f) * 0.84f) + 0.16f;
                                float f12 = 0.45f * f6;
                                iMix2 = mixByte(clampByte(iRed * f11), 255, f12);
                                iMix3 = mixByte(clampByte(iGreen * f11), 255, f12);
                                iMix = mixByte(clampByte(iBlue * f11), 255, f12);
                            } else {
                                float f13 = f10 * 0.8f;
                                float f14 = f6 * 0.6f;
                                iMix2 = mixByte(clampByte((f7 * 0.2f) + f13), 255, f14);
                                iMix3 = mixByte(clampByte((f8 * 0.2f) + f13), 255, f14);
                                iMix = mixByte(clampByte((f9 * 0.2f) + f13), 255, f14);
                            }
                            iClamp = iMix2;
                            iGreen2 = iMix3;
                            iBlue2 = iMix;
                            iAlpha = clampByte(((f6 * 0.12f) + (f5 * 0.12f) + 0.58f) * iAlpha);
                        } else {
                            iClamp = mixByte(iRed, 255, f6);
                            iGreen2 = mixByte(iGreen, 255, f6);
                            iBlue2 = mixByte(iBlue, 255, f6);
                            iAlpha = clampByte(iAlpha * fPow);
                        }
                    } else if (z3) {
                        iClamp = iRed;
                        iGreen2 = iGreen;
                        iBlue2 = iBlue;
                    }
                    iArr[i11] = (iAlpha << 24) | (iClamp << 16) | (iGreen2 << 8) | iBlue2;
                } else if (z3 && fMin != 0.0f) {
                    if (z4) {
                        iArr[i11] = (iAlpha << 24)
                            | (mixByte(i7, iRed, fMin) << 16)
                            | (mixByte(i8, iGreen, fMin) << 8)
                            | mixByte(i9, iBlue, fMin);
                    } else {
                        float fMax2 = ((Math.max(iRed2, Math.max(iGreen2, iBlue2)) / 255.0f) * 0.82f) + 0.18f;
                        iArr[i11] = (iAlpha << 24)
                            | (clampByte(iRed * fMax2) << 16)
                            | (clampByte(iGreen * fMax2) << 8)
                            | clampByte(iBlue * fMax2);
                    }
                }
            }
        }
    }

    /** Bit-exact sliding-window 48x27 box blur (radius 2) replacing Sky.boxBlur([IZ)[I into caller buffer. */
    public static void skyBoxBlur(int[] src, int[] out, boolean horizontal) {
        if (src == null || out == null) return;
        int len = src.length;
        if (len < 1296 || out.length < 1296) return;
        if (horizontal) {
            for (int y = 0; y < 27; y++) {
                int row = y * 48;
                int p0 = src[row];
                int p1 = src[row + 1];
                int p2 = src[row + 2];
                int sumR = ((p0 >> 16) & 255) * 3 + ((p1 >> 16) & 255) + ((p2 >> 16) & 255);
                int sumG = ((p0 >> 8) & 255) * 3 + ((p1 >> 8) & 255) + ((p2 >> 8) & 255);
                int sumB = (p0 & 255) * 3 + (p1 & 255) + (p2 & 255);
                for (int x = 0; x < 48; x++) {
                    out[row + x] = 0xFF000000 | ((sumR / 5) << 16) | ((sumG / 5) << 8) | (sumB / 5);
                    int subIdx = row + (x - 2 < 0 ? 0 : x - 2);
                    int addIdx = row + (x + 3 > 47 ? 47 : x + 3);
                    int pSub = src[subIdx];
                    int pAdd = src[addIdx];
                    sumR += ((pAdd >> 16) & 255) - ((pSub >> 16) & 255);
                    sumG += ((pAdd >> 8) & 255) - ((pSub >> 8) & 255);
                    sumB += (pAdd & 255) - (pSub & 255);
                }
            }
        } else {
            for (int x = 0; x < 48; x++) {
                int p0 = src[x];
                int p1 = src[48 + x];
                int p2 = src[96 + x];
                int sumR = ((p0 >> 16) & 255) * 3 + ((p1 >> 16) & 255) + ((p2 >> 16) & 255);
                int sumG = ((p0 >> 8) & 255) * 3 + ((p1 >> 8) & 255) + ((p2 >> 8) & 255);
                int sumB = (p0 & 255) * 3 + (p1 & 255) + (p2 & 255);
                for (int y = 0; y < 27; y++) {
                    out[y * 48 + x] = 0xFF000000 | ((sumR / 5) << 16) | ((sumG / 5) << 8) | (sumB / 5);
                    int subY = y - 2 < 0 ? 0 : y - 2;
                    int addY = y + 3 > 26 ? 26 : y + 3;
                    int pSub = src[subY * 48 + x];
                    int pAdd = src[addY * 48 + x];
                    sumR += ((pAdd >> 16) & 255) - ((pSub >> 16) & 255);
                    sumG += ((pAdd >> 8) & 255) - ((pSub >> 8) & 255);
                    sumB += (pAdd & 255) - (pSub & 255);
                }
            }
        }
    }

    /** Bit-exact sliding-window 48x27 box blur (radius 2) replacing Sky.boxBlur([IZ)[I. */
    public static int[] skyBoxBlur(int[] src, boolean horizontal) {
        if (src == null) return null;
        int len = src.length;
        int[] out = new int[len];
        if (len < 1296) return out;
        skyBoxBlur(src, out, horizontal);
        return out;
    }

    /** Release reusable pixel buffers and cached lens geometries under memory pressure. */
    public static void trimMemory(int level) {
        if (level < 20 && level != 10 && level != 15) return;
        synchronized (BLUR_LOCK) {
            RustBlur.trim();
            int[] empty = new int[0];
            blurPixels = empty;
            blurR = empty;
            blurG = empty;
            blurB = empty;
            blurTR = empty;
            blurTG = empty;
            blurTB = empty;
        }
        synchronized (Shamsi.class) {
            Arrays.fill(Shamsi.CACHE, null);
            Shamsi.nextCache = 0;
        }
        WallpaperTheme.trimCache();
    }
}
