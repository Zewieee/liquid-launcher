package local.launcherfix;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.view.View;

/** Match ImageView CENTER_CROP without resizing or copying wallpaper bitmaps, plus custom GLSL transition shaders. */
public final class WallpaperTransitionCrop {
    private static int cachedBitmapId;
    private static final float[] cachedPalette = {
        1.0f, 0.52f, 0.18f, // u_ember default
        0.96f, 0.95f, 0.92f // u_ink default
    };

    private WallpaperTransitionCrop() {}

    private static int lastRandomIdx = -1;
    private static final java.util.Random RNG = new java.util.Random();

    public static final String[] ALL_KEYS = {
        "random",
        "pocket-contagion",
        "pocket-outline",
        "ink-splash",
        "voronoi-shatter",
        "smoke",
        "heat-melt",
        "chromatic-bloom",
        "liquid-prism",
        "singularity",
        "hex-matrix",
        "aurora-silk",
        "cinematic-drift",
        "glass-sweep",
        "soft-aperture"
    };

    public static synchronized String resolveEffect(String key) {
        if ("fade".equals(key)) return key;
        if (key != null && !"random".equals(key)) {
            for (int i = 1; i < ALL_KEYS.length; i++) {
                if (ALL_KEYS[i].equals(key)) return key;
            }
        }
        int count = ALL_KEYS.length - 1;
        int pick = 1 + RNG.nextInt(count);
        if (pick == lastRandomIdx && count > 1) {
            pick = 1 + ((pick - 1 + 1 + RNG.nextInt(count - 1)) % count);
        }
        lastRandomIdx = pick;
        return ALL_KEYS[pick];
    }

    public static String label(String key, boolean vi) {
        if (key == null) return vi ? "Ngẫu nhiên" : "Random";
        switch (key) {
            case "cinematic-drift":
                return vi ? "Điện ảnh" : "Cinema";
            case "glass-sweep":
                return vi ? "Dải kính" : "Glass Sweep";
            case "soft-aperture":
                return vi ? "Mở từ tâm" : "Soft Aperture";
            case "random":
                return vi ? "Ngẫu nhiên" : "Random";
            case "pocket-contagion":
                return vi ? "Đốm lửa Pocket" : "Pocket colonies";
            case "pocket-outline":
                return vi ? "Phác nét Pocket" : "Pocket outline";
            case "liquid-prism":
                return vi ? "Lăng kính lỏng" : "Liquid prism";
            case "singularity":
                return vi ? "Hố đen" : "Singularity";
            case "hex-matrix":
                return vi ? "Lục giác 3D" : "Hex matrix";
            case "aurora-silk":
                return vi ? "Lụa cực quang" : "Aurora silk";
            case "chromatic-bloom":
                return vi ? "Tách màu" : "Chromatic bloom";
            case "smoke":
                return vi ? "Khói" : "Smoke";
            case "heat-melt":
                return vi ? "Tan chảy" : "Heat melt";
            case "voronoi-shatter":
                return vi ? "Vỡ mảnh" : "Shatter";
            default:
                return vi ? "Loang mực" : "Ink splash";
        }
    }

    public static String customPrep(String key) {
        if (key == null) return null;
        switch (key) {
            case "pocket-contagion":
                // Precomputes 3-octave value noise + 9 staggered colony arrival field (smooth surface in G, tendril noise in R)
                return "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
                     + "float vnoise(vec2 p) {\n"
                     + "  vec2 i = floor(p); vec2 f = fract(p);\n"
                     + "  f = f * f * (3.0 - 2.0 * f);\n"
                     + "  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),\n"
                     + "             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);\n"
                     + "}\n"
                     + "float noise3(vec2 uv) {\n"
                     + "  vec2 p = vec2(uv.x * 1.7777, uv.y);\n"
                     + "  return 0.52 * vnoise(p * 4.5 + vec2(1.3, 2.7))\n"
                     + "       + 0.30 * vnoise(p * 11.5 + vec2(5.1, 8.3))\n"
                     + "       + 0.18 * vnoise(p * 28.0 + vec2(9.7, 3.4));\n"
                     + "}\n"
                     + "float colonyDist(vec2 uv, vec2 seed, float t0, float n) {\n"
                     + "  vec2 d = (uv - seed) * vec2(1.7777, 1.0);\n"
                     + "  return t0 + length(d) * (0.78 + 0.55 * n);\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  float n = noise3(v_uv);\n"
                     + "  float fine = vnoise(v_uv * vec2(64.0, 36.0) + vec2(3.7, 7.1));\n"
                     + "  float a = 10.0;\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.18, 0.24), 0.00, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.78, 0.22), 0.03, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.48, 0.52), 0.01, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.24, 0.78), 0.05, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.82, 0.74), 0.04, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.62, 0.34), 0.07, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.34, 0.40), 0.09, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.12, 0.54), 0.08, n));\n"
                     + "  a = min(a, colonyDist(v_uv, vec2(0.88, 0.48), 0.10, n));\n"
                     + "  float s = clamp(a * 1.95 - 0.02 + (n - 0.5) * 0.16, 0.0, 0.95);\n"
                     + "  gl_FragColor = vec4(fine, s, n, 1.0);\n"
                     + "}\n";
            case "pocket-outline":
                // Precomputes silhouette signed distance (R), inward character fill map (G), and outward world spread map (B)
                return "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
                     + "float vnoise(vec2 p) {\n"
                     + "  vec2 i = floor(p); vec2 f = fract(p);\n"
                     + "  f = f * f * (3.0 - 2.0 * f);\n"
                     + "  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),\n"
                     + "             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);\n"
                     + "}\n"
                     + "float noise3(vec2 uv) {\n"
                     + "  vec2 p = vec2(uv.x * 1.7777, uv.y);\n"
                     + "  return 0.52 * vnoise(p * 5.0 + vec2(2.1, 4.3))\n"
                     + "       + 0.30 * vnoise(p * 13.0 + vec2(6.7, 1.9))\n"
                     + "       + 0.18 * vnoise(p * 30.0 + vec2(4.2, 8.5));\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  float n = noise3(v_uv);\n"
                     + "  vec2 c = (v_uv - vec2(0.50, 0.54)) * vec2(1.45, 1.0);\n"
                     + "  vec2 h = (v_uv - vec2(0.50, 0.31)) * vec2(1.65, 1.15);\n"
                     + "  float dBody = length(c) - (0.31 + 0.035 * (n - 0.5));\n"
                     + "  float dHead = length(h) - (0.16 + 0.020 * (n - 0.5));\n"
                     + "  float k = 0.08;\n"
                     + "  float sm = clamp(0.5 + 0.5 * (dBody - dHead) / k, 0.0, 1.0);\n"
                     + "  float sd = mix(dBody, dHead, sm) - k * sm * (1.0 - sm);\n"
                     + "  float sdPacked = clamp(0.5 + sd * 2.4, 0.0, 1.0);\n"
                     + "  float inDepth = clamp((-sd / 0.28) * 0.76 + n * 0.22, 0.0, 0.94);\n"
                     + "  float outDist = clamp((max(sd, 0.0) / 0.68) * 0.78 + n * 0.20, 0.0, 0.94);\n"
                     + "  gl_FragColor = vec4(sdPacked, inDepth, outDist, 1.0);\n"
                     + "}\n";
            default:
                return null;
        }
    }

    public static String customShader(String key) {
        if (key == null) return null;
        String motion = WallpaperMotion.body(key);
        if (motion != null) return motion;
        switch (key) {
            case "fade":
                return "void main(){gl_FragColor=mix(sampleOld(v_uv),sampleNew(v_uv),smoothstep(0.0,1.0,u_progress));}\n";
            case "pocket-contagion":
                // Faithful clone of diegoMalagrida/pocket-dots CONTAGION (wallfx.frag mode 0)
                return "uniform vec3 u_ember;\n"
                     + "float escena(float x) { x = clamp(x, 0.0, 1.0); return x * x * (3.0 - 2.0 * x); }\n"
                     + "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
                     + "vec2 cam(vec2 uv, float z) { return clamp(0.5 + (uv - 0.5) / z, 0.0, 1.0); }\n"
                     + "float arrivalAt(vec2 c, vec2 grid) {\n"
                     + "  vec2 cc = clamp(c, vec2(0.0), grid - 1.0);\n"
                     + "  vec2 cuv = (cc + 0.5) / grid;\n"
                     + "  vec4 f = texture2D(u_field, vec2(cuv.x, 1.0 - cuv.y));\n"
                     + "  float grain = (hash(cc) - 0.5) * 0.055 + (f.r - 0.5) * 0.045;\n"
                     + "  return clamp(f.g + grain, 0.0, 0.97);\n"
                     + "}\n"
                     + "void effectMain() {\n"
                     + "  const float LIFE = 0.065;\n"
                     + "  float t = clamp(u_progress, 0.0, 1.0);\n"
                     + "  float p = escena(t);\n"
                     + "  float energy = sin(3.14159265 * t);\n"
                     + "  float T = p * (1.0 + LIFE + 0.02) - 0.01;\n"
                     + "  vec2 grid = vec2(240.0, 135.0);\n"
                     + "  vec2 cell = floor(v_uv * grid);\n"
                     + "  float s = texture2D(u_field, vec2(v_uv.x, 1.0 - v_uv.y)).g;\n"
                     + "  float a = arrivalAt(cell, grid);\n"
                     + "  float flick = hash(cell + vec2(19.1, 47.3));\n"
                     + "  vec3 emberCol = (dot(u_ember, u_ember) > 0.001) ? u_ember : vec3(1.0, 0.52, 0.18);\n"
                     + "  vec3 hot = mix(emberCol, vec3(1.0), 0.18);\n"
                     + "  vec3 c;\n"
                     + "  if (a > T) {\n"
                     + "    c = sampleOld(v_uv).rgb;\n"
                     + "    float ahead = a - T;\n"
                     + "    if (flick > 0.982 && ahead < 0.024) {\n"
                     + "      c = mix(c, hot, (1.0 - ahead / 0.024) * 0.75 * energy * step(0.0, T));\n"
                     + "    }\n"
                     + "  } else {\n"
                     + "    c = sampleNew(cam(v_uv, 1.0 + 0.025 * (1.0 - p))).rgb;\n"
                     + "    float maxNb = max(\n"
                     + "      max(arrivalAt(cell + vec2(1.0, 0.0), grid), arrivalAt(cell - vec2(1.0, 0.0), grid)),\n"
                     + "      max(arrivalAt(cell + vec2(0.0, 1.0), grid), arrivalAt(cell - vec2(0.0, 1.0), grid))\n"
                     + "    );\n"
                     + "    bool edge = maxNb > T;\n"
                     + "    float life = clamp((T - a) / LIFE, 0.0, 1.0);\n"
                     + "    float burn = (1.0 - life) * (1.0 - life) * (edge ? 1.0 : 0.55);\n"
                     + "    c = mix(c, hot, burn * (0.55 + 0.45 * flick) * 0.78 * energy);\n"
                     + "  }\n"
                     + "  c += emberCol * 0.20 * energy * exp(-abs(s - T) / 0.007);\n"
                     + "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  if (u_progress <= 0.0) { gl_FragColor = sampleOld(v_uv); return; }\n"
                     + "  if (u_progress >= 1.0) { gl_FragColor = sampleNew(v_uv); return; }\n"
                     + "  effectMain();\n"
                     + "}\n";
            case "pocket-outline":
                // Faithful clone of diegoMalagrida/pocket-dots OUTLINE / perfil (wallfx.frag mode 1)
                return "uniform vec3 u_ink;\n"
                     + "float escena(float x) { x = clamp(x, 0.0, 1.0); return x * x * (3.0 - 2.0 * x); }\n"
                     + "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
                     + "float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n"
                     + "vec2 cam(vec2 uv, float z) { return clamp(0.5 + (uv - 0.5) / z, 0.0, 1.0); }\n"
                     + "void effectMain() {\n"
                     + "  float t = clamp(u_progress, 0.0, 1.0);\n"
                     + "  float p = escena(t);\n"
                     + "  float zCh = 1.0 + 0.030 * (1.0 - p);\n"
                     + "  float zBg = 1.0 + 0.015 * (1.0 - p);\n"
                     + "  vec2 uvCh = cam(v_uv, zCh);\n"
                     + "  vec2 uvBg = cam(v_uv, zBg);\n"
                     + "  vec4 fSmooth = texture2D(u_field, vec2(uvCh.x, 1.0 - uvCh.y));\n"
                     + "  float sd = (fSmooth.r - 0.5) / 2.4;\n"
                     + "  float inside = 1.0 - smoothstep(-0.008, 0.008, sd);\n"
                     + "  float back = escena(t / 0.18);\n"
                     + "  vec3 st = sampleOld(v_uv).rgb;\n"
                     + "  st = mix(st, vec3(luma(st)), 0.25 * back) * (1.0 - 0.20 * back);\n"
                     + "  vec2 grid = vec2(240.0, 135.0);\n"
                     + "  vec2 cell = floor(v_uv * grid);\n"
                     + "  vec2 cuv = (cell + 0.5) / grid;\n"
                     + "  float grain = (hash(cell) - 0.5) * 0.04;\n"
                     + "  vec2 cuvCh = cam(cuv, zCh);\n"
                     + "  vec2 cuvBg = cam(cuv, zBg);\n"
                     + "  float inArr = clamp(texture2D(u_field, vec2(cuvCh.x, 1.0 - cuvCh.y)).g + grain, 0.0, 0.97);\n"
                     + "  float outArr = clamp(texture2D(u_field, vec2(cuvBg.x, 1.0 - cuvBg.y)).b + grain, 0.0, 0.97);\n"
                     + "  float Ti = escena((t - 0.30) / 0.34) * 1.02 - 0.01;\n"
                     + "  vec3 cIn = (inArr <= Ti) ? sampleNew(uvCh).rgb : st;\n"
                     + "  float To = escena((t - 0.44) / 0.50) * 1.02 - 0.01;\n"
                     + "  vec3 cOut = (outArr <= To) ? sampleNew(uvBg).rgb : st;\n"
                     + "  vec3 c = mix(cOut, cIn, inside);\n"
                     + "  float drawP = escena((t - 0.02) / 0.33);\n"
                     + "  float fade = 1.0 - escena((t - 0.45) / 0.25);\n"
                     + "  float lp = clamp((uvCh.y - 0.12) / 0.74 + 0.06 * abs(uvCh.x - 0.5), 0.0, 1.0);\n"
                     + "  if (lp <= drawP && fade > 0.0) {\n"
                     + "    float silLine = 1.0 - smoothstep(0.002, 0.011, abs(sd));\n"
                     + "    float lx = luma(sampleNew(clamp(uvCh + vec2(0.003, 0.0), 0.0, 1.0)).rgb)\n"
                     + "             - luma(sampleNew(clamp(uvCh - vec2(0.003, 0.0), 0.0, 1.0)).rgb);\n"
                     + "    float ly = luma(sampleNew(clamp(uvCh + vec2(0.0, 0.005), 0.0, 1.0)).rgb)\n"
                     + "             - luma(sampleNew(clamp(uvCh - vec2(0.0, 0.005), 0.0, 1.0)).rgb);\n"
                     + "    float imgEdge = smoothstep(0.08, 0.24, abs(lx) + abs(ly)) * inside * 0.65;\n"
                     + "    float core = max(silLine, imgEdge);\n"
                     + "    vec3 inkCol = (dot(u_ink, u_ink) > 0.001) ? u_ink : vec3(0.95, 0.94, 0.90);\n"
                     + "    c = mix(c, inkCol, core * 0.78 * fade);\n"
                     + "  }\n"
                     + "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  if (u_progress <= 0.0) { gl_FragColor = sampleOld(v_uv); return; }\n"
                     + "  if (u_progress >= 1.0) { gl_FragColor = sampleNew(v_uv); return; }\n"
                     + "  effectMain();\n"
                     + "}\n";
            case "liquid-prism":
                // Optimized for 1080p TV GPUs: zero atan(), fast rational bell curve, and 3 texture fetches instead of 6
                return "float motionProgress() { float t = clamp(u_progress, 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }\n"
                     + "void effectMain() {\n"
                     + "  float p = motionProgress();\n"
                     + "  vec2 c = v_uv - vec2(0.5);\n"
                     + "  c.x *= 1.7777;\n"
                     + "  float r = length(c) + 0.0001;\n"
                     + "  vec2 dir = c / r;\n"
                     + "  float harm = dir.x * dir.y * (dir.x * dir.x - dir.y * dir.y);\n"
                     + "  float wobble = 0.055 * harm + 0.025 * sin(v_uv.x * 16.0 + p * 6.0) * cos(v_uv.y * 14.0 - p * 5.0);\n"
                     + "  float radius = p * 1.32 - 0.12;\n"
                     + "  float diff = (r + wobble) - radius;\n"
                     + "  float env = 4.0 * p * (1.0 - p);\n"
                     + "  float dNorm = diff * 10.5;\n"
                     + "  float ring = (1.0 / (1.0 + dNorm * dNorm)) * env;\n"
                     + "  float disp = ring * 0.082;\n"
                     + "  vec2 shift = dir * disp;\n"
                     + "  vec3 oldCol = sampleOld(clamp(v_uv - shift, 0.0, 1.0)).rgb;\n"
                     + "  vec3 newCol = sampleNew(clamp(v_uv + shift * 0.65, 0.0, 1.0)).rgb;\n"
                     + "  float chromaR = sampleOld(clamp(v_uv - shift * 1.35, 0.0, 1.0)).r;\n"
                     + "  oldCol.r = mix(oldCol.r, chromaR, ring);\n"
                     + "  float reveal = 1.0 - smoothstep(-0.04, 0.04, diff);\n"
                     + "  vec3 mixed = mix(oldCol, newCol, reveal);\n"
                     + "  float caustic = ring * ring;\n"
                     + "  vec3 irid = vec3(0.65, 0.90, 1.0) + vec3(0.30, -0.10, 0.20) * harm;\n"
                     + "  mixed += irid * caustic * 0.78;\n"
                     + "  gl_FragColor = vec4(clamp(mixed, 0.0, 1.0), 1.0);\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  if (u_progress <= 0.0) { gl_FragColor = sampleOld(v_uv); return; }\n"
                     + "  if (u_progress >= 1.0) { gl_FragColor = sampleNew(v_uv); return; }\n"
                     + "  effectMain();\n"
                     + "}\n";
            case "singularity":
                // Optimized for 1080p TV GPUs: direct 2x2 rotation matrix (zero atan(), single sin/cos pair, rational falloff)
                return "float motionProgress() { float t = clamp(u_progress, 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }\n"
                     + "void effectMain() {\n"
                     + "  float p = motionProgress();\n"
                     + "  vec2 c = v_uv - vec2(0.5);\n"
                     + "  c.x *= 1.7777;\n"
                     + "  float r2 = dot(c, c);\n"
                     + "  float r = sqrt(r2) + 0.0001;\n"
                     + "  float intensity = 4.0 * p * (1.0 - p);\n"
                     + "  float falloff = 1.0 / (1.0 + r2 * 3.2);\n"
                     + "  float twist = intensity * (1.65 / (r + 0.20)) * (1.0 - smoothstep(0.0, 0.95, r));\n"
                     + "  float cs = cos(twist);\n"
                     + "  float sn = sin(twist);\n"
                     + "  vec2 rotOld = vec2(c.x * cs - c.y * sn, c.x * sn + c.y * cs) * (1.0 + p * 0.32 * falloff);\n"
                     + "  vec2 rotNew = vec2(c.x * cs + c.y * sn, -c.x * sn + c.y * cs) * (1.0 - (1.0 - p) * 0.30 * falloff);\n"
                     + "  vec2 uvOld = clamp(vec2(0.5 + rotOld.x * 0.5625, 0.5 + rotOld.y), 0.0, 1.0);\n"
                     + "  vec2 uvNew = clamp(vec2(0.5 + rotNew.x * 0.5625, 0.5 + rotNew.y), 0.0, 1.0);\n"
                     + "  vec3 oldCol = sampleOld(uvOld).rgb;\n"
                     + "  vec3 newCol = sampleNew(uvNew).rgb;\n"
                     + "  float horizonR = smoothstep(0.10, 0.90, p) * 1.22;\n"
                     + "  float reveal = 1.0 - smoothstep(horizonR - 0.08, horizonR + 0.08, r);\n"
                     + "  vec3 mixed = mix(oldCol, newCol, reveal);\n"
                     + "  float dr = (r - horizonR) * 18.0;\n"
                     + "  float photonRing = (1.0 / (1.0 + dr * dr)) * intensity;\n"
                     + "  vec3 ringCol = vec3(0.72, 0.82, 1.0);\n"
                     + "  float dp = (p - 0.32) * 7.0;\n"
                     + "  float coreDark = (1.0 / (1.0 + r2 * 48.0)) * (1.0 / (1.0 + dp * dp)) * 0.80;\n"
                     + "  mixed = mixed * (1.0 - coreDark) + ringCol * (photonRing * 0.95);\n"
                     + "  gl_FragColor = vec4(clamp(mixed, 0.0, 1.0), 1.0);\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  if (u_progress <= 0.0) { gl_FragColor = sampleOld(v_uv); return; }\n"
                     + "  if (u_progress >= 1.0) { gl_FragColor = sampleNew(v_uv); return; }\n"
                     + "  effectMain();\n"
                     + "}\n";
            case "hex-matrix":
                // Optimized for 1080p TV GPUs: zero trig calls per pixel, mediump-safe hash, parabolic 3D flip
                return "float motionProgress() { float t = clamp(u_progress, 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }\n"
                     + "void effectMain() {\n"
                     + "  float p = motionProgress();\n"
                     + "  float scale = 11.0;\n"
                     + "  float aspect = 1.7777;\n"
                     + "  float px = (v_uv.x - 0.5) * aspect * scale;\n"
                     + "  float py = (v_uv.y - 0.5) * scale;\n"
                     + "  float xc = 0.6666667 * px;\n"
                     + "  float zc = -0.3333333 * px + 0.5773503 * py;\n"
                     + "  float yc = -xc - zc;\n"
                     + "  float rx = floor(xc + 0.5);\n"
                     + "  float ry = floor(yc + 0.5);\n"
                     + "  float rz = floor(zc + 0.5);\n"
                     + "  float xd = abs(rx - xc);\n"
                     + "  float yd = abs(ry - yc);\n"
                     + "  float zd = abs(rz - zc);\n"
                     + "  if (xd > yd && xd > zd) { rx = -ry - rz; }\n"
                     + "  else if (yd > zd) { ry = -rx - rz; }\n"
                     + "  else { rz = -rx - ry; }\n"
                     + "  float hx = 1.5 * rx;\n"
                     + "  float hy = 0.8660254 * rx + 1.7320508 * rz;\n"
                     + "  float lx = px - hx;\n"
                     + "  float ly = py - hy;\n"
                     + "  float cellHash = fract(rx * 0.1731 + rz * 0.3117 + fract((rx - rz) * 0.293));\n"
                     + "  float cellU = 0.5 + (hx / scale) * 0.5625;\n"
                     + "  float cellV = 0.5 + (hy / scale);\n"
                     + "  float wavePos = (cellU * 0.55 + cellV * 0.45) * 0.55 + cellHash * 0.12;\n"
                     + "  float localP = clamp((p * 1.48 - wavePos) * 2.083333, 0.0, 1.0);\n"
                     + "  float u = localP * 2.0 - 1.0;\n"
                     + "  float absCos = max(abs(u), 0.08);\n"
                     + "  float sinT = 1.0 - u * u;\n"
                     + "  float rotLx = lx / absCos;\n"
                     + "  float rotLy = ly * (1.0 - 0.16 * sinT);\n"
                     + "  float hexMetric = max(abs(rotLx) * 0.8660254 + abs(rotLy) * 0.5, abs(rotLy));\n"
                     + "  vec2 sampleUv = clamp(vec2(cellU + (rotLx / scale) * 0.5625, cellV + (rotLy / scale)), 0.0, 1.0);\n"
                     + "  vec3 tileCol = (localP < 0.5) ? sampleOld(sampleUv).rgb : sampleNew(sampleUv).rgb;\n"
                     + "  float shade = 0.58 + 0.42 * absCos;\n"
                     + "  float bevel = smoothstep(0.70, 0.86, hexMetric) * sinT;\n"
                     + "  vec3 neonCol = mix(vec3(0.27, 0.92, 1.0), vec3(1.0, 0.35, 0.86), localP);\n"
                     + "  tileCol = tileCol * shade + neonCol * bevel * 0.85;\n"
                     + "  gl_FragColor = vec4(clamp(tileCol, 0.0, 1.0), 1.0);\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  if (u_progress <= 0.0) { gl_FragColor = sampleOld(v_uv); return; }\n"
                     + "  if (u_progress >= 1.0) { gl_FragColor = sampleNew(v_uv); return; }\n"
                     + "  effectMain();\n"
                     + "}\n";
            case "aurora-silk":
                // Optimized for 1080p TV GPUs: rational envelope curves, zero exp()/pow(), 2 texture fetches
                return "float motionProgress() { float t = clamp(u_progress, 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }\n"
                     + "void effectMain() {\n"
                     + "  float p = motionProgress();\n"
                     + "  float env = 4.0 * p * (1.0 - p);\n"
                     + "  float diag = v_uv.x * 0.75 + v_uv.y * 0.55;\n"
                     + "  float perp = v_uv.x * 0.55 - v_uv.y * 0.75;\n"
                     + "  float foldWave = sin(perp * 16.0 + p * 7.0);\n"
                     + "  float curtainEdge = p * 1.62 - 0.18 + 0.085 * foldWave;\n"
                     + "  float diff = diag - curtainEdge;\n"
                     + "  float reveal = 1.0 - smoothstep(-0.035, 0.035, diff);\n"
                     + "  float posDiff = max(diff, 0.0) * 4.5;\n"
                     + "  float pleatZone = (1.0 / (1.0 + posDiff * posDiff)) * env;\n"
                     + "  float zHeight = pleatZone * (0.5 + 0.5 * sin(diff * 36.0 - p * 12.0));\n"
                     + "  vec2 uvOld = clamp(vec2(v_uv.x - zHeight * 0.075, v_uv.y - zHeight * 0.050), 0.0, 1.0);\n"
                     + "  vec3 oldCol = sampleOld(uvOld).rgb;\n"
                     + "  float negDiff = max(-diff, 0.0) * 9.0;\n"
                     + "  float shadowZone = (1.0 / (1.0 + negDiff * negDiff)) * env;\n"
                     + "  vec2 uvNew = clamp(v_uv + vec2(shadowZone * 0.022, shadowZone * 0.016), 0.0, 1.0);\n"
                     + "  vec3 newCol = sampleNew(uvNew).rgb * (1.0 - shadowZone * 0.40);\n"
                     + "  vec3 irid = vec3(0.45, 0.88, 0.95) + vec3(0.35, -0.15, 0.25) * foldWave;\n"
                     + "  oldCol = oldCol * (0.78 + 0.32 * zHeight) + irid * (zHeight * zHeight) * 0.65;\n"
                     + "  float dh = diff * 32.0;\n"
                     + "  float hem = (1.0 / (1.0 + dh * dh)) * env;\n"
                     + "  vec3 mixed = mix(oldCol, newCol, reveal) + irid * hem * 0.75;\n"
                     + "  gl_FragColor = vec4(clamp(mixed, 0.0, 1.0), 1.0);\n"
                     + "}\n"
                     + "void main() {\n"
                     + "  if (u_progress <= 0.0) { gl_FragColor = sampleOld(v_uv); return; }\n"
                     + "  if (u_progress >= 1.0) { gl_FragColor = sampleNew(v_uv); return; }\n"
                     + "  effectMain();\n"
                     + "}\n";
            default:
                return null;
        }
    }

    static float[] bounds(int imageWidth, int imageHeight, int viewWidth, int viewHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0)
            return new float[]{0, 0, 1, 1};
        float scale = Math.max((float)viewWidth / imageWidth, (float)viewHeight / imageHeight);
        float width = imageWidth * scale, height = imageHeight * scale;
        // ImageView rounds the CENTER_CROP translation to device pixels.
        float left = -Math.round((viewWidth - width) * .5f) / width;
        float top = -Math.round((viewHeight - height) * .5f) / height;
        return new float[]{left, top, viewWidth / width, viewHeight / height};
    }

    private static float hue2rgb(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }

    /** Exact port of WallpaperFx.qml's lifted(c) + chroma/light palette extraction. */
    private static float[] extractPalette(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            return cachedPalette;
        }
        int id = System.identityHashCode(bmp);
        if (id == cachedBitmapId) {
            return cachedPalette;
        }
        int w = bmp.getWidth(), h = bmp.getHeight();
        float bestChroma = -1f;
        float er = 1.0f, eg = 0.52f, eb = 0.18f;
        float bestLuma = -1f;
        float ir = 0.96f, ig = 0.95f, ib = 0.92f;
        for (int gy = 0; gy < 6; gy++) {
            int y = Math.min(h - 1, (gy * 2 + 1) * h / 12);
            for (int gx = 0; gx < 8; gx++) {
                int x = Math.min(w - 1, (gx * 2 + 1) * w / 16);
                int c = bmp.getPixel(x, y);
                float r = ((c >> 16) & 0xff) / 255f;
                float g = ((c >> 8) & 0xff) / 255f;
                float b = (c & 0xff) / 255f;
                float max = Math.max(r, Math.max(g, b));
                float min = Math.min(r, Math.min(g, b));
                float chroma = max - min;
                if (chroma > bestChroma) {
                    bestChroma = chroma;
                    er = r; eg = g; eb = b;
                }
                float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                if (luma > bestLuma) {
                    bestLuma = luma;
                    ir = r; ig = g; ib = b;
                }
            }
        }
        // Lift ember saturation >= 0.55 and lightness into [0.62, 0.82] (WallpaperFx.qml lines 78-82)
        float max = Math.max(er, Math.max(eg, eb));
        float min = Math.min(er, Math.min(eg, eb));
        float d = max - min;
        float hVal = 0.08f, sVal = 0.75f, lVal = (max + min) * 0.5f;
        if (d > 0.001f) {
            sVal = lVal > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == er) hVal = ((eg - eb) / d + (eg < eb ? 6f : 0f)) / 6f;
            else if (max == eg) hVal = ((eb - er) / d + 2f) / 6f;
            else hVal = ((er - eg) / d + 4f) / 6f;
        }
        sVal = Math.max(sVal, 0.55f);
        lVal = Math.min(Math.max(lVal, 0.62f), 0.82f);
        float q = lVal < 0.5f ? lVal * (1f + sVal) : lVal + sVal - lVal * sVal;
        float p = 2f * lVal - q;
        cachedPalette[0] = hue2rgb(p, q, hVal + 1f / 3f);
        cachedPalette[1] = hue2rgb(p, q, hVal);
        cachedPalette[2] = hue2rgb(p, q, hVal - 1f / 3f);
        // Ensure ink is a light tone even if the wallpaper is very dark
        float liftInk = bestLuma < 0.78f ? (0.78f - bestLuma) : 0f;
        cachedPalette[3] = Math.min(1f, ir + liftInk);
        cachedPalette[4] = Math.min(1f, ig + liftInk);
        cachedPalette[5] = Math.min(1f, ib + liftInk);
        cachedBitmapId = id;
        return cachedPalette;
    }

    public static void bind(int program, Bitmap oldImage, Bitmap newImage, View host) {
        if (host != null) {
            CrystalSoundEngine.playWallpaperApply(host.getContext());
        }
        int width = host.getWidth(), height = host.getHeight();
        if (width <= 0 || height <= 0) {
            android.util.DisplayMetrics metrics = host.getResources().getDisplayMetrics();
            width = metrics.widthPixels; height = metrics.heightPixels;
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_aspect"),
            (float)Math.max(1, width) / Math.max(1, height));
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "u_crop_old"), 1,
            bounds(oldImage.getWidth(), oldImage.getHeight(), width, height), 0);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "u_crop_new"), 1,
            bounds(newImage.getWidth(), newImage.getHeight(), width, height), 0);
        float[] pal = extractPalette(newImage);
        int emberLoc = GLES20.glGetUniformLocation(program, "u_ember");
        if (emberLoc >= 0) {
            GLES20.glUniform3fv(emberLoc, 1, pal, 0);
        }
        int inkLoc = GLES20.glGetUniformLocation(program, "u_ink");
        if (inkLoc >= 0) {
            GLES20.glUniform3fv(inkLoc, 1, pal, 3);
        }
    }
}
