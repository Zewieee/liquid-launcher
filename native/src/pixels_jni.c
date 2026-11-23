#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

typedef uint8_t (*IconKernel)(const int32_t*,int32_t*,int,int,int,int,int,int,int,int,int,int);
typedef uint8_t (*LensKernel)(const int32_t*,int32_t*,const int32_t*,const float*,const float*,const float*,const float*,int,int,int,int,float,float,float,float,float,float);

typedef struct {int32_t locations[11];float values[38];int32_t check_error;} VideoDraw;
typedef uint8_t (*VideoKernel)(const VideoDraw*);

// Reusable scratch buffers serialized by synchronized Java callers (RustPixels.recolour / RustPixels.lens)
// and released via lp_trim() when MemoryTrimmer runs.
static jint *s_icon_out = NULL;
static size_t s_icon_cap = 0;
static jint *s_lens_out = NULL;
static size_t s_lens_cap = 0;

static jint* ensure_scratch(jint **buf, size_t *cap, size_t count) {
    if (count == 0 || count > 4194304) return NULL;
    if (*cap < count || !*buf) {
        jint *next = (jint*)realloc(*buf, count * sizeof(jint));
        if (!next) return NULL;
        *buf = next;
        *cap = count;
    }
    return *buf;
}

void lp_trim(void) {
    free(s_icon_out);
    s_icon_out = NULL;
    s_icon_cap = 0;
    free(s_lens_out);
    s_lens_out = NULL;
    s_lens_cap = 0;
}

jboolean lp_video(JNIEnv *e,jint program,jint position,jint source,jint next,jint source_matrix,
        jint next_matrix,jint blend_uniform,jint crop_uniform,jint flip_uniform,jint texture,jint next_texture,
        jfloatArray matrix,jfloatArray second_matrix,jfloatArray crop,jfloat blend,jfloat flip,
        jboolean check_error,VideoKernel kernel) {
    if (!matrix || !second_matrix || !crop || (*e)->GetArrayLength(e,matrix)<16
        || (*e)->GetArrayLength(e,second_matrix)<16 || (*e)->GetArrayLength(e,crop)<4) return JNI_FALSE;
    VideoDraw draw={{program,position,source,next,source_matrix,next_matrix,
        blend_uniform,crop_uniform,flip_uniform,texture,next_texture},{0},check_error};
    (*e)->GetFloatArrayRegion(e,matrix,0,16,draw.values);
    if ((*e)->ExceptionCheck(e)) return JNI_FALSE;
    (*e)->GetFloatArrayRegion(e,second_matrix,0,16,draw.values+16);
    if ((*e)->ExceptionCheck(e)) return JNI_FALSE;
    (*e)->GetFloatArrayRegion(e,crop,0,4,draw.values+32);
    if ((*e)->ExceptionCheck(e)) return JNI_FALSE;
    draw.values[36]=blend;draw.values[37]=flip;
    return kernel(&draw);
}

static int valid(int w,int h,jsize len) {
    return w>0 && h>0 && (int64_t)w*h<=4194304 && (int64_t)w*h<=len;
}

// Never pin critical arrays. Commit output only after the Rust kernel succeeds.
jboolean lp_icon(JNIEnv *e,jintArray pixels,jint w,jint h,jint color,jboolean transparent,
        jboolean glass,jboolean found,jint r,jint g,jint b,IconKernel kernel) {
    if (!pixels) return JNI_FALSE;
    jsize len=(*e)->GetArrayLength(e,pixels);
    if (!valid(w,h,len) || len>4194304 || r<0 || r>255 || g<0 || g>255 || b<0 || b>255) return JNI_FALSE;
    jint *out=ensure_scratch(&s_icon_out,&s_icon_cap,(size_t)len);
    if (!out) return JNI_FALSE;
    jint *in=(*e)->GetIntArrayElements(e,pixels,NULL);
    if (!in) return JNI_FALSE;
    jboolean ok=kernel(in,out,len,w,h,color,transparent,glass,found,r,g,b);
    (*e)->ReleaseIntArrayElements(e,pixels,in,JNI_ABORT);
    if (ok) (*e)->SetIntArrayRegion(e,pixels,0,len,out);
    return (*e)->ExceptionCheck(e) ? JNI_FALSE : ok;
}

jboolean lp_lens(JNIEnv *e,jintArray src,jintArray dst,jintArray alpha,
        jfloatArray ox,jfloatArray oy,jfloatArray dispersion,jfloatArray specular,
        jint sw,jint sh,jint w,jint h,jfloat x,jfloat y,jfloat lw,jfloat lh,jfloat strength,jfloat glass,
        LensKernel kernel) {
    if (!src || !dst || !alpha || !ox || !oy || !dispersion || !specular || sw<2 || sh<2 || sw>8192 || sh>8192) return JNI_FALSE;
    if (!valid(sw,sh,(*e)->GetArrayLength(e,src)) || !valid(w,h,(*e)->GetArrayLength(e,dst))
        || !valid(w,h,(*e)->GetArrayLength(e,alpha)) || !valid(w,h,(*e)->GetArrayLength(e,ox))
        || !valid(w,h,(*e)->GetArrayLength(e,oy)) || !valid(w,h,(*e)->GetArrayLength(e,dispersion))
        || !valid(w,h,(*e)->GetArrayLength(e,specular))) return JNI_FALSE;
    jint *out=ensure_scratch(&s_lens_out,&s_lens_cap,(size_t)w*(size_t)h);
    if (!out) return JNI_FALSE;
    jint *s=NULL,*a=NULL;
    jfloat *px=NULL,*py=NULL,*d=NULL,*sp=NULL;
    jboolean ok=JNI_FALSE;
    s=(*e)->GetIntArrayElements(e,src,NULL);if (!s) goto done;
    a=(*e)->GetIntArrayElements(e,alpha,NULL);if (!a) goto done;
    px=(*e)->GetFloatArrayElements(e,ox,NULL);if (!px) goto done;
    py=(*e)->GetFloatArrayElements(e,oy,NULL);if (!py) goto done;
    d=(*e)->GetFloatArrayElements(e,dispersion,NULL);if (!d) goto done;
    sp=(*e)->GetFloatArrayElements(e,specular,NULL);if (!sp) goto done;
    ok=kernel(s,out,a,px,py,d,sp,sw,sh,w,h,x,y,lw,lh,strength,glass);
done:
    if(sp) (*e)->ReleaseFloatArrayElements(e,specular,sp,JNI_ABORT);
    if(d) (*e)->ReleaseFloatArrayElements(e,dispersion,d,JNI_ABORT);
    if(py) (*e)->ReleaseFloatArrayElements(e,oy,py,JNI_ABORT);
    if(px) (*e)->ReleaseFloatArrayElements(e,ox,px,JNI_ABORT);
    if(a) (*e)->ReleaseIntArrayElements(e,alpha,a,JNI_ABORT);
    if(s) (*e)->ReleaseIntArrayElements(e,src,s,JNI_ABORT);
    if(ok) (*e)->SetIntArrayRegion(e,dst,0,w*h,out);
    return (*e)->ExceptionCheck(e) ? JNI_FALSE : ok;
}
