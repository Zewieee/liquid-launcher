use std::ffi::c_void;
use std::panic::catch_unwind;

#[inline(always)]
fn clamp(v:f32, lo:f32, hi:f32)->f32 { if v<lo {lo} else if v>hi {hi} else {v} }
#[inline(always)]
fn byte(v:f32)->i32 {
    if v<=0.0 {return 0;} if v>=255.0 {return 255;}
    let base=v as i32;
    base + ((v-base as f32)>=0.5) as i32
}
#[inline(always)]
fn mix(a:i32,b:i32,t:f32)->i32 { byte((b-a) as f32*t+a as f32) }

unsafe extern "C" fn icon_kernel(input:*const i32, output:*mut i32, len:i32,w:i32,h:i32,color:i32,
        transparent:i32,glass:i32,found:i32,pr:i32,pg:i32,pb:i32)->u8 {
    catch_unwind(|| {
        let src=std::slice::from_raw_parts(input,len as usize);
        let dst=std::slice::from_raw_parts_mut(output,len as usize);
        dst.copy_from_slice(src);
        let tinted=color!=0;let glass=glass!=0;let transparent=transparent!=0;let found=found!=0;
        let solid=glass && !found && (src.iter().filter(|&&p| (p as u32>>24)>=220).count() as f32)>len as f32*0.65;
        let r=if tinted {(color>>16)&255} else {225};
        let g=if tinted {(color>>8)&255} else {241};
        let b=if tinted {color&255} else {255};
        let contrast_max=pr.max(255-pr).max(pg.max(255-pg)).max(pb.max(255-pb)).max(1) as f32;
        for y in 0..h {
            let fy=if h<=1 {0.0} else {y as f32/(h-1) as f32};let inv=1.0-fy;
            let p=0.0f32.max(1.0-(fy-0.24).abs()/0.16) as f64;
            let row_alpha=if glass { ((p*p) as f32*0.15+inv*0.24)+0.56 } else {0.0};
            for x in 0..w {
                let at=(y*w+x) as usize;let pixel=src[at];let mut a=(pixel as u32>>24) as i32;
                if a==0 {continue;}
                let mut rr=(pixel>>16)&255;let mut gg=(pixel>>8)&255;let mut bb=pixel&255;
                let contrast=(rr-pr).abs().max((gg-pg).abs()).max((bb-pb).abs());
                let weight=if found {if contrast<=24 {0.0} else {(contrast as f32/contrast_max).min(1.0)}} else {1.0};
                if transparent || glass {
                    if found {
                        a=byte(a as f32*weight);
                        if a==0 {dst[at]=0;}
                        else if !tinted && !glass && weight>0.0 {
                            let inv_weight=1.0-weight;
                            rr=byte((rr as f32-pr as f32*inv_weight)/weight);
                            gg=byte((gg as f32-pg as f32*inv_weight)/weight);
                            bb=byte((bb as f32-pb as f32*inv_weight)/weight);
                        }
                    } else if transparent {a=byte(a as f32*0.78);}
                    if glass {
                        let fx=if w<=1 {0.0} else {x as f32/(w-1) as f32};
                        let shine=((1.0-fx)*0.17*inv)+0.1;
                        if solid {
                            let rf=rr as f32;let gf=gg as f32;let bf=bb as f32;
                            let luma=(0.0722*bf+0.7152*gf)+0.2126*rf;
                            if tinted {
                                let weight=(luma/255.0)*0.84+0.16;let t=0.45*shine;
                                rr=mix(byte(r as f32*weight),255,t);
                                gg=mix(byte(g as f32*weight),255,t);
                                bb=mix(byte(b as f32*weight),255,t);
                            } else {
                                let gray=luma*0.8;let t=shine*0.6;
                                rr=mix(byte(rf*0.2+gray),255,t);
                                gg=mix(byte(gf*0.2+gray),255,t);
                                bb=mix(byte(bf*0.2+gray),255,t);
                            }
                            a=byte(((shine*0.12+inv*0.12)+0.58)*a as f32);
                        } else {
                            rr=mix(r,255,shine);gg=mix(g,255,shine);bb=mix(b,255,shine);
                            a=byte(a as f32*row_alpha);
                        }
                    } else if tinted {rr=r;gg=g;bb=b;}
                    dst[at]=(a<<24)|(rr<<16)|(gg<<8)|bb;
                } else if tinted && weight!=0.0 {
                    if found {
                        dst[at]=(a<<24)|(mix(pr,r,weight)<<16)|(mix(pg,g,weight)<<8)|mix(pb,b,weight);
                    } else {
                        let weight=(rr.max(gg).max(bb) as f32/255.0)*0.82+0.18;
                        dst[at]=(a<<24)|(byte(r as f32*weight)<<16)|(byte(g as f32*weight)<<8)|byte(b as f32*weight);
                    }
                }
            }
        }
        true
    }).unwrap_or(false) as u8
}

#[inline(always)]
fn clamp_finite(v:f32, lo:f32, hi:f32)->f32 {
    if v>=lo { if v<=hi {v} else {hi} } else {lo}
}

#[inline(always)]
fn sample(src:&[i32],sw:usize,sh:usize,x:f32,y:f32,shift:u32)->i32 {
    let cx=clamp_finite(x-0.5,0.0,sw as f32-1.001);let cy=clamp_finite(y-0.5,0.0,sh as f32-1.001);
    let ix=(cx as u32) as usize;
    let iy=(cy as u32) as usize;
    let fx=cx-ix as f32;let fy=cy-iy as f32;
    let at=iy*sw+ix;
    let a=(src[at] as u32>>shift)&255;let b=(src[at+1] as u32>>shift)&255;
    let c=(src[at+sw] as u32>>shift)&255;let d=(src[at+sw+1] as u32>>shift)&255;
    let top=(b as i32-a as i32) as f32*fx+a as f32;
    ((((d as i32-c as i32) as f32*fx+c as f32)-top)*fy+top+0.5) as i32
}

#[inline(always)]
fn sample_rgb(src:&[i32],sw:usize,sh:usize,x:f32,y:f32)->[i32;3] {
    let cx=clamp_finite(x-0.5,0.0,sw as f32-1.001);let cy=clamp_finite(y-0.5,0.0,sh as f32-1.001);
    let ix=(cx as u32) as usize;
    let iy=(cy as u32) as usize;
    let fx=cx-ix as f32;let fy=cy-iy as f32;
    let at=iy*sw+ix;
    let a=src[at] as u32;let b=src[at+1] as u32;let c=src[at+sw] as u32;let d=src[at+sw+1] as u32;
    let mut result=[0;3];
    for (i,shift) in [16,8,0].iter().enumerate() {
        let a=((a>>shift)&255) as i32;let b=((b>>shift)&255) as i32;
        let c=((c>>shift)&255) as i32;let d=((d>>shift)&255) as i32;
        let top=(b-a) as f32*fx+a as f32;
        result[i]=((((d-c) as f32*fx+c as f32)-top)*fy+top+0.5) as i32;
    }
    result
}

unsafe extern "C" fn lens_kernel(input:*const i32,output:*mut i32,alpha:*const i32,
        ox:*const f32,oy:*const f32,disp:*const f32,spec:*const f32,
        sw:i32,sh:i32,w:i32,h:i32,x:f32,y:f32,lw:f32,lh:f32,strength:f32,glass:f32)->u8 {
    catch_unwind(|| {
        let n=(w*h) as usize;
        let src=std::slice::from_raw_parts(input,(sw*sh) as usize);
        let dst=std::slice::from_raw_parts_mut(output,n);
        let alpha=std::slice::from_raw_parts(alpha,n);
        let ox=std::slice::from_raw_parts(ox,n);let oy=std::slice::from_raw_parts(oy,n);
        let disp=std::slice::from_raw_parts(disp,n);let spec=std::slice::from_raw_parts(spec,n);
        if [x,y,lw,lh,strength,glass].iter().any(|v| !v.is_finite() || v.abs()>1e6)
            || lw<=0.0 || lh<=0.0 {return false;}
        let glass=clamp(glass,0.0,1.0);
        let strength=(0.6*glass+1.0)*strength.max(0.0);let highlight=1.0-glass*0.35;
        let dx=lw/w as f32;let dy=lh/h as f32;
        for row in 0..h {
            let py=(row as f32+0.5)*dy+y;
            for col in 0..w {
                let at=(row*w+col) as usize;let a=alpha[at];
                if !(0..=255).contains(&a) {return false;}
                if a==0 {dst[at]=0;continue;}
                let oxv=ox[at];let oyv=oy[at];let dv=disp[at];let spv=spec[at];
                if !(oxv.abs()<=1e6 && oyv.abs()<=1e6 && dv.abs()<=1e6 && spv.abs()<=1e6) {return false;}
                let px=(col as f32+0.5)*dx+x;let sx=oxv*strength;let sy=oyv*strength;
                let red=1.0-dv;let blue=dv+1.0;
                let rx=sx*red+px;let ry=red*sy+py;
                let gx=px+sx;let gy=py+sy;
                let bx=sx*blue+px;let by=sy*blue+py;
                let [r,g,b]=if rx==gx && gx==bx && ry==gy && gy==by {
                    sample_rgb(src,sw as usize,sh as usize,gx,gy)
                } else {[
                    sample(src,sw as usize,sh as usize,rx,ry,16),
                    sample(src,sw as usize,sh as usize,gx,gy,8),
                    sample(src,sw as usize,sh as usize,bx,by,0)
                ]};
                let sp=spv*highlight;
                let r=((r as f32*0.97+7.65+sp) as i32).clamp(0,255);
                let g=((g as f32*0.97+7.65+sp) as i32).clamp(0,255);
                let b=((b as f32*0.97+7.65+sp) as i32).clamp(0,255);
                dst[at]=(a<<24)|(r<<16)|(g<<8)|b;
            }
        }
        true
    }).unwrap_or(false) as u8
}

type IconKernel=unsafe extern "C" fn(*const i32,*mut i32,i32,i32,i32,i32,i32,i32,i32,i32,i32,i32)->u8;
type LensKernel=unsafe extern "C" fn(*const i32,*mut i32,*const i32,*const f32,*const f32,*const f32,*const f32,i32,i32,i32,i32,f32,f32,f32,f32,f32,f32)->u8;
extern "C" {
    fn lp_icon(env:*mut c_void,pixels:*mut c_void,w:i32,h:i32,color:i32,transparent:u8,glass:u8,found:u8,r:i32,g:i32,b:i32,kernel:IconKernel)->u8;
    fn lp_lens(env:*mut c_void,src:*mut c_void,dst:*mut c_void,alpha:*mut c_void,ox:*mut c_void,oy:*mut c_void,disp:*mut c_void,spec:*mut c_void,
        sw:i32,sh:i32,w:i32,h:i32,x:f32,y:f32,lw:f32,lh:f32,strength:f32,glass:f32,kernel:LensKernel)->u8;
}
#[no_mangle]
pub unsafe extern "system" fn Java_local_launcherfix_RustPixels_recolourNative(env:*mut c_void,_class:*mut c_void,
        pixels:*mut c_void,w:i32,h:i32,color:i32,transparent:u8,glass:u8,found:u8,r:i32,g:i32,b:i32)->u8 {
    lp_icon(env,pixels,w,h,color,transparent,glass,found,r,g,b,icon_kernel)
}
#[no_mangle]
pub unsafe extern "system" fn Java_local_launcherfix_RustPixels_lensNative(env:*mut c_void,_class:*mut c_void,
        src:*mut c_void,dst:*mut c_void,alpha:*mut c_void,ox:*mut c_void,oy:*mut c_void,disp:*mut c_void,spec:*mut c_void,
        sw:i32,sh:i32,w:i32,h:i32,x:f32,y:f32,lw:f32,lh:f32,strength:f32,glass:f32)->u8 {
    lp_lens(env,src,dst,alpha,ox,oy,disp,spec,sw,sh,w,h,x,y,lw,lh,strength,glass,lens_kernel)
}
