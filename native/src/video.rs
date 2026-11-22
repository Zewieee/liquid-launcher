use std::ffi::c_void;
use std::panic::catch_unwind;

#[repr(C)]
struct VideoDraw { locations:[i32;11], values:[f32;38], check_error:i32 }
static QUAD:[f32;8]=[0.0,0.0,1.0,0.0,0.0,1.0,1.0,1.0];

#[link(name="GLESv2")]
extern "C" {
    fn glUseProgram(program:u32);
    fn glDisable(cap:u32);
    fn glBindBuffer(target:u32,buffer:u32);
    fn glEnableVertexAttribArray(index:u32);
    fn glVertexAttribPointer(index:u32,size:i32,kind:u32,normalized:u8,stride:i32,pointer:*const c_void);
    fn glActiveTexture(texture:u32);
    fn glBindTexture(target:u32,texture:u32);
    fn glUniform1i(location:i32,value:i32);
    fn glUniformMatrix4fv(location:i32,count:i32,transpose:u8,value:*const f32);
    fn glUniform1f(location:i32,value:f32);
    fn glUniform4fv(location:i32,count:i32,value:*const f32);
    fn glDrawArrays(mode:u32,first:i32,count:i32);
    fn glDisableVertexAttribArray(index:u32);
    fn glGetError()->u32;
}

unsafe extern "C" fn draw(input:*const VideoDraw)->u8 {
    catch_unwind(|| {
        if input.is_null() {return false;}
        let d=&*input;let l=&d.locations;let v=&d.values;
        // Validate before touching GL so unsupported input may use the Java fallback.
        if l[0]<=0 || l[1]<0 || l[9]==0 || l[10]==0 || l[2..9].iter().any(|&v|v< -1)
            || v.iter().any(|v| !v.is_finite() || v.abs()>1e6)
            || !(0.0..=1.0).contains(&v[36]) || !(0.0..=1.0).contains(&v[37]) {return false;}
        glUseProgram(l[0] as u32);
        glDisable(0x0BE2); // GL_BLEND
        glBindBuffer(0x8892,0); // Client vertex array, matching the Java FloatBuffer path.
        glEnableVertexAttribArray(l[1] as u32);
        glVertexAttribPointer(l[1] as u32,2,0x1406,0,0,QUAD.as_ptr().cast());
        glActiveTexture(0x84C0);
        glBindTexture(0x8D65,l[9] as u32); // GL_TEXTURE_EXTERNAL_OES
        glUniform1i(l[2],0);
        glUniformMatrix4fv(l[4],1,0,v.as_ptr());
        glActiveTexture(0x84C1);
        glBindTexture(0x8D65,l[10] as u32);
        glUniform1i(l[3],1);
        glUniformMatrix4fv(l[5],1,0,v.as_ptr().add(16));
        glUniform1f(l[6],v[36]);
        glUniform4fv(l[7],1,v.as_ptr().add(32));
        glUniform1f(l[8],v[37]);
        glDrawArrays(0x0005,0,4); // GL_TRIANGLE_STRIP
        glDisableVertexAttribArray(l[1] as u32);
        d.check_error==0 || glGetError()==0
    }).unwrap_or(false) as u8
}

extern "C" {
    fn lp_video(env:*mut c_void,program:i32,position:i32,source:i32,next:i32,source_matrix:i32,
        next_matrix:i32,blend_uniform:i32,crop_uniform:i32,flip_uniform:i32,texture:i32,next_texture:i32,
        matrix:*mut c_void,second_matrix:*mut c_void,crop:*mut c_void,blend:f32,flip:f32,check_error:u8,
        kernel:unsafe extern "C" fn(*const VideoDraw)->u8)->u8;
}
#[no_mangle]
pub unsafe extern "system" fn Java_local_launcherfix_RustVideo_drawNative(env:*mut c_void,_class:*mut c_void,
        program:i32,position:i32,source:i32,next:i32,source_matrix:i32,next_matrix:i32,
        blend_uniform:i32,crop_uniform:i32,flip_uniform:i32,texture:i32,next_texture:i32,
        matrix:*mut c_void,second_matrix:*mut c_void,crop:*mut c_void,blend:f32,flip:f32,check_error:u8)->u8 {
    lp_video(env,program,position,source,next,source_matrix,next_matrix,blend_uniform,crop_uniform,flip_uniform,
        texture,next_texture,matrix,second_matrix,crop,blend,flip,check_error,draw)
}
