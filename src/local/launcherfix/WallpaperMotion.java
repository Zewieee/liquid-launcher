package local.launcherfix;

/** Restrained full-screen motion; both still and live renderers use these same bodies. */
public final class WallpaperMotion {
    private WallpaperMotion() {}

    public static long durationNanos(String key) {
        if ("glass-sweep".equals(key)) return 1100000000L;
        if ("cinematic-drift".equals(key) || "soft-aperture".equals(key)) return 1000000000L;
        return 1400000000L;
    }

    private static final String EASING =
        "float motionEase(float t){t=clamp(t,0.0,1.0);return t*t*t*(t*(t*6.0-15.0)+10.0);}\n";
    private static final String MAIN =
        "void main(){\n"
        + " if(u_progress<=0.0){gl_FragColor=sampleOld(v_uv);return;}\n"
        + " if(u_progress>=1.0){gl_FragColor=sampleNew(v_uv);return;}\n"
        + " effectMain();\n}\n";

    public static String body(String key) {
        if (key == null) return null;
        switch (key) {
            case "cinematic-drift":
                // Zoom keeps both small counter-moving camera paths inside their source crop.
                return EASING
                    + "void effectMain(){\n"
                    + " float p=motionEase(u_progress);\n"
                    + " vec2 center=v_uv-vec2(0.5);\n"
                    + " vec2 oldUv=vec2(0.5)+center*(1.0-0.035*p)+vec2(-0.016,0.006)*p;\n"
                    + " vec2 newUv=vec2(0.5)+center*(1.0-0.055*(1.0-p))+vec2(0.018,-0.008)*(1.0-p);\n"
                    + " float blend=smoothstep(0.08,0.92,p);\n"
                    + " gl_FragColor=mix(sampleOld(oldUv),sampleNew(newUv),blend);\n"
                    + "}\n" + MAIN;
            case "glass-sweep":
                // One soft veil: compact refraction and a low-contrast highlight, no bright flash.
                return EASING
                    + "void effectMain(){\n"
                    + " float p=motionEase(u_progress);\n"
                    + " float envelope=4.0*p*(1.0-p);\n"
                    + " float edge=mix(-0.18,1.18,p);\n"
                    + " float d=dot(v_uv,vec2(0.82,0.18))-edge;\n"
                    + " float reveal=1.0-smoothstep(-0.10,0.10,d);\n"
                    + " float band=1.0-smoothstep(0.0,0.18,abs(d));\n"
                    + " float glass=band*band*envelope;\n"
                    + " vec2 shift=vec2(0.018,-0.006)*glass;\n"
                    + " vec2 newUv=vec2(0.5)+(v_uv-vec2(0.5))*(1.0-0.018*(1.0-p));\n"
                    + " vec4 color=mix(sampleOld(clamp(v_uv+shift,0.0,1.0)),sampleNew(clamp(newUv-shift*0.4,0.0,1.0)),reveal);\n"
                    + " color.rgb*=1.0-0.045*glass*(1.0-reveal);\n"
                    + " color.rgb=mix(color.rgb,vec3(1.0),0.035*glass);\n"
                    + " gl_FragColor=color;\n"
                    + "}\n" + MAIN;
            case "soft-aperture":
                return "uniform float u_aspect;\n" + EASING
                    + "void effectMain(){\n"
                    + " float p=motionEase(u_progress);\n"
                    + " float aspect=max(u_aspect,0.1);\n"
                    + " vec2 metric=vec2(aspect,1.0);\n"
                    + " vec2 center=(v_uv-vec2(0.5))*metric;\n"
                    + " float radius=length(center);\n"
                    + " float reach=length(metric*0.5);\n"
                    + " float edge=mix(-0.14,reach+0.14,p);\n"
                    + " float reveal=1.0-smoothstep(edge-0.12,edge+0.12,radius);\n"
                    + " float rim=1.0-smoothstep(0.02,0.14,abs(radius-edge));\n"
                    + " float envelope=4.0*p*(1.0-p);\n"
                    + " vec2 shift=(center/max(radius,0.001))/metric*(0.008*rim*envelope);\n"
                    + " vec2 oldUv=vec2(0.5)+(v_uv-vec2(0.5))*(1.0-0.018*p);\n"
                    + " vec2 newUv=vec2(0.5)+(v_uv-vec2(0.5))*(1.0-0.045*(1.0-p));\n"
                    + " vec4 color=mix(sampleOld(clamp(oldUv+shift,0.0,1.0)),sampleNew(clamp(newUv-shift,0.0,1.0)),reveal);\n"
                    + " color.rgb*=1.0-0.025*rim*envelope*(1.0-reveal);\n"
                    + " gl_FragColor=color;\n"
                    + "}\n" + MAIN;
            default:
                return null;
        }
    }
}
