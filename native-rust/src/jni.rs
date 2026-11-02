//! JNI entry points of `org.patryk3211.powergrid.electricity.sim.solver.NativeMNA`.

use crate::solver::{Callbacks, Solver, JACOBIAN_OP_SIZE, RHS_OP_SIZE};
use jni::objects::{GlobalRef, JByteBuffer, JMethodID, JObject, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jboolean, jdouble, jint, jlong, jobject};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

const ITER_HOOK_NAME: &str = "runIterHooks";
const ITER_HOOK_SIG: &str = "(ILjava/nio/ByteBuffer;)I";
const RESIDUAL_HOOK_NAME: &str = "runAddResidual";
const RESIDUAL_HOOK_SIG: &str = "(Ljava/nio/ByteBuffer;)V";
const REPORT_NAME: &str = "reportConvergenceProblems";
const REPORT_SIG: &str = "(DILjava/nio/ByteBuffer;)V";

/// Java side callbacks and the buffers shared with it.
struct JavaSide {
    iter_hook: JMethodID,
    residual_hook: JMethodID,
    report_problems: JMethodID,
    // Direct buffers over the two state/b arrays, recreated whenever the solver is resized.
    buffers: Option<[GlobalRef; 2]>,
    residual_ptr: *mut u8,
    residual_len: usize,
}

struct Native {
    solver: Solver,
    java: JavaSide,
}

// Upper bound for the state size, protects against bogus sizes which would abort on allocation failure.
const MAX_STATE_SIZE: jint = 1 << 22;

/// Java callbacks. The JVM may call back into the solver while a callback is running, so this only
/// keeps a raw pointer to the Java side data (which isn't touched by those re-entrant calls).
struct Hooks<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    object: &'a JObject<'local>,
    java: *const JavaSide,
}

impl Hooks<'_, '_> {
    fn java(&self) -> &JavaSide {
        // SAFETY: the pointer refers to the native object which outlives the tick.
        unsafe { &*self.java }
    }

    fn buffer(&self, index: usize) -> Result<&JObject<'static>, ()> {
        match &self.java().buffers {
            Some(buffers) => Ok(buffers[index].as_obj()),
            None => Err(()),
        }
    }
}

impl Callbacks for Hooks<'_, '_> {
    fn iteration_hooks(&mut self, iteration: i32, state_buffer: usize) -> Result<i32, ()> {
        let state = self.buffer(state_buffer)?;
        // SAFETY: method id and signature were resolved from the object's class.
        let result = unsafe {
            self.env.call_method_unchecked(
                self.object,
                self.java().iter_hook,
                ReturnType::Primitive(Primitive::Int),
                &[JValue::Int(iteration).as_jni(), JValue::Object(state).as_jni()],
            )
        };
        result.map_err(|_| ())?.i().map_err(|_| ())
    }

    fn add_residual(&mut self, b_buffer: usize) -> Result<(), ()> {
        let b = self.buffer(b_buffer)?;
        // SAFETY: see `iteration_hooks`.
        let result = unsafe {
            self.env.call_method_unchecked(
                self.object,
                self.java().residual_hook,
                ReturnType::Primitive(Primitive::Void),
                &[JValue::Object(b).as_jni()],
            )
        };
        result.map(|_| ()).map_err(|_| ())
    }

    fn convergence_problems(&mut self, norm: f64, iterations: i32) -> Result<(), ()> {
        // SAFETY: the residual vector outlives the call, it is owned by the solver.
        let buffer = unsafe { self.env.new_direct_byte_buffer(self.java().residual_ptr, self.java().residual_len) }
            .map_err(|_| ())?;
        let buffer = JObject::from(buffer);
        // SAFETY: see `iteration_hooks`.
        let result = unsafe {
            self.env.call_method_unchecked(
                self.object,
                self.java().report_problems,
                ReturnType::Primitive(Primitive::Void),
                &[JValue::Double(norm).as_jni(), JValue::Int(iterations).as_jni(), JValue::Object(&buffer).as_jni()],
            )
        };
        let _ = self.env.delete_local_ref(buffer);
        result.map(|_| ()).map_err(|_| ())
    }
}

/// Accessors which only borrow one field of the native object, so that re-entrant calls made by
/// Java callbacks never alias the data used by the running tick.
unsafe fn solver_of<'a>(pointer: jlong) -> Option<&'a mut Solver> {
    let native = pointer as *mut Native;
    if native.is_null() { None } else { Some(&mut (*native).solver) }
}

unsafe fn java_of<'a>(pointer: jlong) -> Option<&'a mut JavaSide> {
    let native = pointer as *mut Native;
    if native.is_null() { None } else { Some(&mut (*native).java) }
}

/// Runs `body`, a panic must never unwind into the JVM so it is converted to a Java exception.
fn guarded<'l, T>(env: &mut JNIEnv<'l>, fallback: T, body: impl FnOnce(&mut JNIEnv<'l>) -> T) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(value) => value,
        Err(_) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", "Native solver panicked");
            }
            fallback
        }
    }
}

fn direct_buffer(env: &mut JNIEnv<'_>, buffer: JObject<'_>, minimum_capacity: usize) -> Result<*mut u8, ()> {
    let buffer = JByteBuffer::from(buffer);
    let address = env.get_direct_buffer_address(&buffer);
    let capacity = env.get_direct_buffer_capacity(&buffer);
    match (address, capacity) {
        (Ok(address), Ok(capacity)) if !address.is_null() && capacity >= minimum_capacity => Ok(address),
        _ => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalArgumentException", "Expected a large enough direct byte buffer");
            }
            Err(())
        }
    }
}

fn rebuild_buffers(env: &mut JNIEnv<'_>, solver: &mut Solver, java: &mut JavaSide) -> Result<(), ()> {
    java.buffers = None;
    let size = solver.size();
    java.residual_len = size * std::mem::size_of::<f64>();
    java.residual_ptr = solver.residual_ptr().cast();
    if size == 0 {
        return Ok(());
    }
    let mut refs = Vec::with_capacity(2);
    for index in 0..2 {
        let pointer = solver.buffer_ptr(index).cast::<u8>();
        // SAFETY: the buffers live until the next resize, at which point the Java references are replaced.
        let local = unsafe { env.new_direct_byte_buffer(pointer, java.residual_len) }.map_err(|_| ())?;
        refs.push(env.new_global_ref(&local).map_err(|_| ())?);
        let _ = env.delete_local_ref(local);
    }
    let second = refs.pop().unwrap();
    let first = refs.pop().unwrap();
    java.buffers = Some([first, second]);
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_verifySupport(
    _env: JNIEnv<'_>, _object: JObject<'_>,
) -> jint {
    // The solver doesn't depend on any optional instruction set extensions.
    0
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_allocateNativeObject(
    mut env: JNIEnv<'_>, object: JObject<'_>, rhs_buffer: JObject<'_>, jacobian_buffer: JObject<'_>,
    command_count: jint, auxiliary_buffer: JObject<'_>,
) -> jlong {
    guarded(&mut env, 0, |env| {
        // The command buffers are read using the command count, make sure they are big enough for it.
        let commands = command_count.max(0) as usize;
        let Ok(rhs) = direct_buffer(env, rhs_buffer, commands * RHS_OP_SIZE) else { return 0 };
        let Ok(jacobian) = direct_buffer(env, jacobian_buffer, commands * JACOBIAN_OP_SIZE) else { return 0 };
        let Ok(auxiliary) = direct_buffer(env, auxiliary_buffer, 1) else { return 0 };

        let Ok(class) = env.get_object_class(&object) else { return 0 };
        let Ok(iter_hook) = env.get_method_id(&class, ITER_HOOK_NAME, ITER_HOOK_SIG) else { return 0 };
        let Ok(residual_hook) = env.get_method_id(&class, RESIDUAL_HOOK_NAME, RESIDUAL_HOOK_SIG) else { return 0 };
        let Ok(report_problems) = env.get_method_id(&class, REPORT_NAME, REPORT_SIG) else { return 0 };

        // SAFETY: the Java object keeps the direct buffers alive for as long as the native object exists.
        let solver = unsafe { Solver::new(rhs, jacobian, commands, auxiliary) };
        let native = Native {
            solver,
            java: JavaSide {
                iter_hook,
                residual_hook,
                report_problems,
                buffers: None,
                residual_ptr: std::ptr::null_mut(),
                residual_len: 0,
            },
        };
        Box::into_raw(Box::new(native)) as jlong
    })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_deallocateNativeObject(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong,
) {
    guarded(&mut env, (), |_| {
        if pointer != 0 {
            // SAFETY: the pointer was produced by `allocateNativeObject` and is released exactly once.
            unsafe { drop(Box::from_raw(pointer as *mut Native)) };
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_setStateSize(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong, size: jint,
) {
    guarded(&mut env, (), |env| {
        if !(0..=MAX_STATE_SIZE).contains(&size) {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid solver state size");
            return;
        }
        let (Some(solver), Some(java)) = (unsafe { solver_of(pointer) }, unsafe { java_of(pointer) }) else { return };
        if solver.resize(size as usize) {
            if rebuild_buffers(env, solver, java).is_err() && !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", "Failed to create the native state buffers");
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_zeroRHS(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong,
) {
    guarded(&mut env, (), |_| if let Some(solver) = unsafe { solver_of(pointer) } { solver.zero_rhs() })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_zeroState(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong,
) {
    guarded(&mut env, (), |_| if let Some(solver) = unsafe { solver_of(pointer) } { solver.zero_state() })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_zeroJacobian(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong,
) {
    guarded(&mut env, (), |_| if let Some(solver) = unsafe { solver_of(pointer) } { solver.zero_jacobian() })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_finishJacobianWrite(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong, command_count: jint,
) {
    guarded(&mut env, (), |_| if let Some(solver) = unsafe { solver_of(pointer) } { solver.process_jacobian_buffer(command_count) })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_processJacobianBuffer(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong, command_count: jint,
) {
    guarded(&mut env, (), |_| if let Some(solver) = unsafe { solver_of(pointer) } { solver.process_jacobian_buffer(command_count) })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_processRHSBuffer(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong,
) {
    guarded(&mut env, (), |_| if let Some(solver) = unsafe { solver_of(pointer) } { solver.process_rhs_buffer() })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_setPrecision(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong, absolute: jdouble,
    relative: jdouble, minimum: jdouble, search_alpha: jdouble,
) {
    guarded(&mut env, (), |_| if let Some(solver) = unsafe { solver_of(pointer) } { solver.set_precision(absolute, relative, minimum, search_alpha) })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_setHookPresence(
    mut env: JNIEnv<'_>, _object: JObject<'_>, pointer: jlong, iteration_hooks: jboolean,
    residual_hooks: jboolean,
) {
    guarded(&mut env, (), |_| {
        if let Some(solver) = unsafe { solver_of(pointer) } {
            solver.set_hook_presence(iteration_hooks != 0, residual_hooks != 0)
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_org_patryk3211_powergrid_electricity_sim_solver_NativeMNA_singleTick<'a>(
    mut env: JNIEnv<'a>, object: JObject<'a>, pointer: jlong, max_iterations: jint, jacobian_count: jint,
) -> jobject {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let native = pointer as *mut Native;
        if native.is_null() {
            return std::ptr::null_mut();
        }
        // Only raw pointers are used while the tick runs, Java may re-enter the solver from its hooks.
        let (solver, java) = unsafe { (std::ptr::addr_of_mut!((*native).solver), std::ptr::addr_of!((*native).java)) };
        let result = {
            let mut hooks = Hooks { env, object: &object, java };
            unsafe { Solver::tick(solver, max_iterations, jacobian_count, &mut hooks) }
        };
        match result {
            Ok(Some(index)) => match unsafe { &(*java).buffers } {
                Some(buffers) => match env.new_local_ref(buffers[index].as_obj()) {
                    Ok(local) => local.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                },
                None => std::ptr::null_mut(),
            },
            // Either nothing to solve or a Java exception which is now pending.
            _ => std::ptr::null_mut(),
        }
    })
}
