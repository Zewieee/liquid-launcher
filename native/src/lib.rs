use std::ffi::{c_int, c_void};
use std::panic::catch_unwind;
use std::sync::Mutex;

mod pixels;
mod video;

const RADIUS: usize = 4;
const DIVISOR: u32 = 9;
const MAX_PIXELS: usize = 4_194_304;

struct Buffers {
    rgb: Vec<u8>,
    temporary: Vec<u8>,
}

static BUFFERS: Mutex<Buffers> = Mutex::new(Buffers {
    rgb: Vec::new(),
    temporary: Vec::new(),
});

fn resize(buffer: &mut Vec<u8>, length: usize) -> bool {
    if length > buffer.len() && buffer.try_reserve_exact(length - buffer.len()).is_err() {
        return false;
    }
    buffer.resize(length, 0);
    true
}

// Same edge clamping and integer division after EACH axis as FastPixelOps.box3.
fn blur_rgb(rgb: &mut [u8], temp: &mut [u8], width: usize, height: usize) {
    for _ in 0..3 {
        for y in 0..height {
            let row = y * width;
            let mut sums = [0u32; 3];
            for k in 0..DIVISOR as usize {
                let x = k.saturating_sub(RADIUS).min(width - 1);
                let at = (row + x) * 3;
                for c in 0..3 { sums[c] += rgb[at + c] as u32; }
            }
            for x in 0..width {
                let at = (row + x) * 3;
                let add = (row + (x + RADIUS + 1).min(width - 1)) * 3;
                let remove = (row + x.saturating_sub(RADIUS)) * 3;
                for c in 0..3 {
                    temp[at + c] = (sums[c] / DIVISOR) as u8;
                    sums[c] = sums[c] + rgb[add + c] as u32 - rgb[remove + c] as u32;
                }
            }
        }
        for x in 0..width {
            let mut sums = [0u32; 3];
            for k in 0..DIVISOR as usize {
                let y = k.saturating_sub(RADIUS).min(height - 1);
                let at = (y * width + x) * 3;
                for c in 0..3 { sums[c] += temp[at + c] as u32; }
            }
            for y in 0..height {
                let at = (y * width + x) * 3;
                let add = ((y + RADIUS + 1).min(height - 1) * width + x) * 3;
                let remove = (y.saturating_sub(RADIUS) * width + x) * 3;
                for c in 0..3 {
                    rgb[at + c] = (sums[c] / DIVISOR) as u8;
                    sums[c] = sums[c] + temp[add + c] as u32 - temp[remove + c] as u32;
                }
            }
        }
    }
}

// One small, deterministic startup guard; a failed result selects the original Java path.
fn probe() -> bool {
    let mut rgb = [0u8; 7 * 5 * 3];
    let mut temp = [0u8; 7 * 5 * 3];
    for (i, value) in rgb.iter_mut().enumerate() { *value = ((i * 73 + 19) % 256) as u8; }
    blur_rgb(&mut rgb, &mut temp, 7, 5);
    // Computed independently with a direct 9-tap reference, flooring after each axis.
    let hash = rgb.iter().fold(2_166_136_261u32, |h, &v| (h ^ v as u32).wrapping_mul(16_777_619));
    let mut singleton = [123, 45, 67];
    let mut scratch = [0u8; 3];
    blur_rgb(&mut singleton, &mut scratch, 1, 1);
    hash == 0xd322b20f && singleton == [123, 45, 67]
}

#[repr(C)]
#[derive(Default)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(env: *mut c_void, bitmap: *mut c_void, info: *mut AndroidBitmapInfo) -> c_int;
    fn AndroidBitmap_lockPixels(env: *mut c_void, bitmap: *mut c_void, address: *mut *mut c_void) -> c_int;
    fn AndroidBitmap_unlockPixels(env: *mut c_void, bitmap: *mut c_void) -> c_int;
}

extern "C" {
    fn lp_trim();
}

struct PixelLock {
    env: *mut c_void,
    bitmap: *mut c_void,
}
impl Drop for PixelLock {
    fn drop(&mut self) { unsafe { AndroidBitmap_unlockPixels(self.env, self.bitmap); } }
}

unsafe fn blur_bitmap(env: *mut c_void, bitmap: *mut c_void) -> bool {
    if env.is_null() || bitmap.is_null() { return false; }
    let mut info = AndroidBitmapInfo::default();
    if AndroidBitmap_getInfo(env, bitmap, &mut info) != 0 || info.format != 1 { return false; }
    let (width, height, stride) = (info.width as usize, info.height as usize, info.stride as usize);
    if width == 0 || height == 0 { return false; }
    let Some(count) = width.checked_mul(height).filter(|&n| n <= MAX_PIXELS) else { return false; };
    let Some(row_bytes) = width.checked_mul(4).filter(|&n| n <= stride) else { return false; };
    if stride.checked_mul(height).filter(|&n| n <= isize::MAX as usize).is_none() { return false; }
    let mut address = std::ptr::null_mut();
    if AndroidBitmap_lockPixels(env, bitmap, &mut address) != 0 { return false; }
    let _lock = PixelLock { env, bitmap };
    if address.is_null() { return false; }
    // getPixels() unpremultiplies non-opaque input. Leave those bitmaps to Java for exact semantics.
    for y in 0..height {
        let row = std::slice::from_raw_parts((address as *const u8).add(y * stride), row_bytes);
        if row.chunks_exact(4).any(|pixel| pixel[3] != 255) { return false; }
    }
    let Ok(mut buffers) = BUFFERS.lock() else { return false; };
    let Buffers { rgb, temporary } = &mut *buffers;
    if !resize(rgb, count * 3) || !resize(temporary, count * 3) { return false; }
    for y in 0..height {
        let row = std::slice::from_raw_parts((address as *const u8).add(y * stride), row_bytes);
        for x in 0..width {
            let source = x * 4;
            let target = (y * width + x) * 3;
            rgb[target..target + 3].copy_from_slice(&row[source..source + 3]);
        }
    }
    blur_rgb(rgb, temporary, width, height);
    // Commit only after all fallible preparation/calculation has finished; leave row padding intact.
    for y in 0..height {
        let row = (address as *mut u8).add(y * stride);
        for x in 0..width {
            std::ptr::copy_nonoverlapping(rgb.as_ptr().add((y * width + x) * 3), row.add(x * 4), 3);
        }
    }
    true
}

#[no_mangle]
pub extern "system" fn Java_local_launcherfix_RustBlur_probeNative(_env: *mut c_void, _class: *mut c_void) -> u8 {
    catch_unwind(probe).unwrap_or(false) as u8
}

#[no_mangle]
pub unsafe extern "system" fn Java_local_launcherfix_RustBlur_blurNative(
    env: *mut c_void, _class: *mut c_void, bitmap: *mut c_void,
) -> u8 {
    catch_unwind(|| blur_bitmap(env, bitmap)).unwrap_or(false) as u8
}

#[no_mangle]
pub extern "system" fn Java_local_launcherfix_RustBlur_trimNative(_env: *mut c_void, _class: *mut c_void) {
    let _ = catch_unwind(|| {
        if let Ok(mut buffers) = BUFFERS.lock() { *buffers = Buffers { rgb: Vec::new(), temporary: Vec::new() }; }
        unsafe { lp_trim(); }
    });
}
