//! Port of `solver.c`: Newton iteration with residual line search on top of a sparse LU solver.

use crate::sparse::SparseMatrix;

/// Layout of the commands written by the JVM (`#pragma pack(1)` in the C implementation).
pub const RHS_OP_SIZE: usize = 12;
pub const JACOBIAN_OP_SIZE: usize = 16;

/// Hooks implemented by the JVM side. An `Err` aborts the tick, the JNI layer leaves the pending
/// Java exception in place.
pub trait Callbacks {
    /// Runs the iteration hooks and returns the number of Jacobian commands they have written.
    fn iteration_hooks(&mut self, iteration: i32, state_buffer: usize) -> Result<i32, ()>;
    /// Lets the residual hooks modify the right hand side held in buffer `b_buffer`.
    fn add_residual(&mut self, b_buffer: usize) -> Result<(), ()>;
    fn convergence_problems(&mut self, norm: f64, iterations: i32) -> Result<(), ()>;
}

pub struct Solver {
    size: usize,
    matrix: SparseMatrix,

    // Two buffers swapping between the roles of `state` and `b`.
    buffers: [Vec<f64>; 2],
    state_index: usize,

    rhs: Vec<f64>,
    residual: Vec<f64>,
    state_delta: Vec<f64>,

    minimum_allowed_precision: f64,
    absolute_stopping_criterion: f64,
    relative_stopping_criterion: f64,
    max_search_alpha: f64,

    max_cmd_count: usize,
    rhs_ops: *const u8,
    jacobian_ops: *const u8,
    aux: *mut u8,

    converged: bool,
    has_iter_hooks: bool,
    has_residual_hooks: bool,
}

// The raw pointers refer to direct byte buffers owned by the JVM object which owns the solver.
unsafe impl Send for Solver {}

impl Solver {
    /// # Safety
    /// `rhs_ops` and `jacobian_ops` must point to at least `cmd_count` commands and stay valid for the
    /// lifetime of the solver, `aux` must point to a writable byte. Null pointers are allowed when the
    /// corresponding feature is unused.
    pub unsafe fn new(rhs_ops: *const u8, jacobian_ops: *const u8, cmd_count: usize, aux: *mut u8) -> Self {
        Solver {
            size: 0,
            matrix: SparseMatrix::default(),
            buffers: [Vec::new(), Vec::new()],
            state_index: 0,
            rhs: Vec::new(),
            residual: Vec::new(),
            state_delta: Vec::new(),
            minimum_allowed_precision: 1e-6,
            absolute_stopping_criterion: 1e-7,
            relative_stopping_criterion: 1e-12,
            max_search_alpha: 0.99,
            max_cmd_count: cmd_count,
            rhs_ops,
            jacobian_ops,
            aux,
            converged: false,
            has_iter_hooks: false,
            has_residual_hooks: false,
        }
    }

    pub fn size(&self) -> usize { self.size }
    pub fn is_converged(&self) -> bool { self.converged }
    pub fn state_index(&self) -> usize { self.state_index }
    pub fn state(&self) -> &[f64] { &self.buffers[self.state_index] }
    pub fn residual(&self) -> &[f64] { &self.residual }

    /// Stable pointer of one of the two state/b buffers, valid until the next `resize`.
    pub fn buffer_ptr(&mut self, index: usize) -> *mut f64 { self.buffers[index].as_mut_ptr() }
    pub fn residual_ptr(&mut self) -> *mut f64 { self.residual.as_mut_ptr() }

    /// Returns true when the buffers have been reallocated.
    pub fn resize(&mut self, size: usize) -> bool {
        if self.size == size {
            return false;
        }
        self.size = size;
        self.matrix.resize(size);
        self.state_index = 0;
        self.buffers = [vec![0.0; size], vec![0.0; size]];
        self.rhs = vec![0.0; size];
        self.residual = vec![0.0; size];
        self.state_delta = vec![0.0; size];
        true
    }

    pub fn zero_state(&mut self) {
        let index = self.state_index;
        self.buffers[index].iter_mut().for_each(|v| *v = 0.0);
    }

    pub fn zero_rhs(&mut self) {
        self.rhs.iter_mut().for_each(|v| *v = 0.0);
    }

    pub fn zero_jacobian(&mut self) {
        if self.size == 0 {
            return;
        }
        self.matrix.zero();
    }

    pub fn set_precision(&mut self, absolute: f64, relative: f64, minimum: f64, search_alpha: f64) {
        self.absolute_stopping_criterion = absolute;
        self.relative_stopping_criterion = relative;
        self.minimum_allowed_precision = minimum;
        self.max_search_alpha = search_alpha;
    }

    pub fn set_hook_presence(&mut self, iteration_hooks: bool, residual_hooks: bool) {
        self.has_iter_hooks = iteration_hooks;
        self.has_residual_hooks = residual_hooks;
    }

    pub fn process_jacobian_buffer(&mut self, cmd_count: i32) {
        if self.size == 0 || self.jacobian_ops.is_null() {
            return;
        }
        let count = (cmd_count.max(0) as usize).min(self.max_cmd_count);
        for index in 0..count {
            // SAFETY: guaranteed by the contract of `new`, commands are packed so reads are unaligned.
            let (row, column, change) = unsafe {
                let op = self.jacobian_ops.add(index * JACOBIAN_OP_SIZE);
                (
                    (op as *const i32).read_unaligned(),
                    (op.add(4) as *const i32).read_unaligned(),
                    (op.add(8) as *const f64).read_unaligned(),
                )
            };
            if row < 0 || column < 0 || row as usize >= self.size || column as usize >= self.size {
                continue;
            }
            self.matrix.add(row as usize, column as usize, change);
        }
    }

    pub fn process_rhs_buffer(&mut self) {
        if self.size == 0 || self.rhs_ops.is_null() {
            return;
        }
        for index in 0..self.max_cmd_count {
            // SAFETY: see `process_jacobian_buffer`.
            let (row, change) = unsafe {
                let op = self.rhs_ops.add(index * RHS_OP_SIZE);
                ((op as *const i32).read_unaligned(), (op.add(4) as *const f64).read_unaligned())
            };
            if row == -1 {
                break;
            }
            if row < 0 || row as usize >= self.size {
                continue;
            }
            self.rhs[row as usize] += change;
        }
    }

    /// Runs one solver tick, returns the index of the buffer holding the new state
    /// or `None` when there is nothing to solve.
    pub fn single_tick<C: Callbacks>(&mut self, max_iterations: i32, cmd_count: i32, callbacks: &mut C) -> Result<Option<usize>, ()> {
        // SAFETY: the pointer is derived from a live exclusive reference which isn't used while ticking.
        unsafe { Self::tick(self as *mut Solver, max_iterations, cmd_count, callbacks) }
    }

    /// Computes `residual = A * state - b` where `b = rhs + residual hooks`, returns its infinity norm.
    ///
    /// # Safety
    /// See [`Solver::tick`].
    unsafe fn compute_residual<C: Callbacks>(this: *mut Solver, callbacks: &mut C, prepare_matrix: bool) -> Result<f64, ()> {
        let (b_index, hooks) = {
            let solver = &mut *this;
            let b_index = 1 - solver.state_index;
            solver.buffers[b_index].copy_from_slice(&solver.rhs);
            (b_index, solver.has_residual_hooks)
        };
        if hooks {
            callbacks.add_residual(b_index)?;
        }
        let solver = &mut *this;
        solver.residual.copy_from_slice(&solver.buffers[b_index]);
        if prepare_matrix {
            solver.matrix.prepare();
        }
        solver.matrix.gemv(1.0, &solver.buffers[solver.state_index], -1.0, &mut solver.residual);
        Ok(max_abs(&solver.residual))
    }

    /// # Safety
    /// See [`Solver::tick`].
    unsafe fn run_iteration_hooks<C: Callbacks>(this: *mut Solver, callbacks: &mut C, iteration: i32) -> Result<(), ()> {
        let state_index = (*this).state_index;
        let commands = callbacks.iteration_hooks(iteration, state_index)?;
        if commands != 0 {
            (*this).process_jacobian_buffer(commands);
        }
        Ok(())
    }

    /// The callbacks call into Java which is allowed to call back into the solver (for instance to flush
    /// a full command buffer), therefore no reference to the solver or its buffers may be alive while a
    /// callback runs. This is why the tick works on a raw pointer and only creates short lived references.
    ///
    /// # Safety
    /// `this` must point to a valid solver which isn't accessed through other references during the call,
    /// except for re-entrant calls made by the callbacks.
    pub unsafe fn tick<C: Callbacks>(this: *mut Solver, max_iterations: i32, cmd_count: i32, callbacks: &mut C) -> Result<Option<usize>, ()> {
        if (*this).size == 0 {
            return Ok(None);
        }
        (*this).process_jacobian_buffer(cmd_count);
        (*this).process_rhs_buffer();

        let mut i = 0;
        let mut norm = 0.0f64;
        while i < max_iterations {
            if i == 0 && (*this).has_iter_hooks {
                Self::run_iteration_hooks(this, callbacks, i)?;
            }

            let next_norm = Self::compute_residual(this, callbacks, true)?;
            let solver = &mut *this;
            let delta_norm = (next_norm - norm).abs();
            norm = next_norm;
            if norm < solver.absolute_stopping_criterion || delta_norm < solver.relative_stopping_criterion {
                break;
            }

            // Solve A * x = b, b is overwritten by the new state.
            let b_index = 1 - solver.state_index;
            if !solver.matrix.solve(&mut solver.buffers[b_index]) {
                // Singular system, fall back to a zero solution (same as the Java solver).
                solver.buffers[b_index].iter_mut().for_each(|v| *v = 0.0);
            }
            solver.state_index = b_index;

            {
                let old_index = 1 - solver.state_index;
                let (state, old_state) = (&solver.buffers[solver.state_index], &solver.buffers[old_index]);
                for ((delta, &new), &old) in solver.state_delta.iter_mut().zip(state).zip(old_state) {
                    *delta = new - old;
                }
            }

            // Perform solution fitting
            let mut alpha = 0.0f64;
            while alpha < (*this).max_search_alpha {
                if (*this).has_iter_hooks {
                    Self::run_iteration_hooks(this, callbacks, i)?;
                }
                let test_norm = Self::compute_residual(this, callbacks, false)?;
                if test_norm < norm {
                    break;
                }
                let delta_alpha = -(1.0 - alpha) * 0.5;
                alpha -= delta_alpha;
                let solver = &mut *this;
                let index = solver.state_index;
                for (state, &delta) in solver.buffers[index].iter_mut().zip(&solver.state_delta) {
                    *state += delta_alpha * delta;
                }
            }

            (*this).matrix.same_pattern(true);
            i += 1;
        }

        let report = {
            let solver = &mut *this;
            if norm > solver.minimum_allowed_precision {
                let report = solver.converged;
                solver.converged = false;
                report
            } else {
                solver.converged = true;
                false
            }
        };
        if report {
            callbacks.convergence_problems(norm, i)?;
        }

        let solver = &mut *this;
        if !solver.aux.is_null() {
            // SAFETY: guaranteed by the contract of `new`.
            *solver.aux = solver.converged as u8;
        }
        Ok(Some(solver.state_index))
    }
}

/// Absolute value of the first element with the largest magnitude (BLAS `idamax` semantics).
fn max_abs(values: &[f64]) -> f64 {
    let mut index = 0;
    for (i, value) in values.iter().enumerate().skip(1) {
        if value.abs() > values[index].abs() {
            index = i;
        }
    }
    values[index].abs()
}

#[cfg(test)]
mod tests {
    use super::*;

    struct NoHooks;
    impl Callbacks for NoHooks {
        fn iteration_hooks(&mut self, _: i32, _: usize) -> Result<i32, ()> { Ok(0) }
        fn add_residual(&mut self, _: usize) -> Result<(), ()> { Ok(()) }
        fn convergence_problems(&mut self, _: f64, _: i32) -> Result<(), ()> { Ok(()) }
    }

    fn jacobian_ops(entries: &[(i32, i32, f64)]) -> Vec<u8> {
        let mut bytes = Vec::new();
        for &(row, column, value) in entries {
            bytes.extend_from_slice(&row.to_ne_bytes());
            bytes.extend_from_slice(&column.to_ne_bytes());
            bytes.extend_from_slice(&value.to_ne_bytes());
        }
        bytes
    }

    fn rhs_ops(entries: &[(i32, f64)], capacity: usize) -> Vec<u8> {
        let mut bytes = Vec::new();
        for &(row, value) in entries {
            bytes.extend_from_slice(&row.to_ne_bytes());
            bytes.extend_from_slice(&value.to_ne_bytes());
        }
        bytes.extend_from_slice(&(-1i32).to_ne_bytes());
        bytes.resize(capacity * RHS_OP_SIZE, 0);
        bytes
    }

    #[test]
    fn solves_linear_system() {
        let jacobian = jacobian_ops(&[(0, 0, 2.0), (1, 1, 4.0), (0, 1, 1.0), (0, 0, 1.0)]);
        let rhs = rhs_ops(&[(0, 7.0), (1, 8.0)], 4);
        let mut aux = 0u8;
        let mut solver = unsafe { Solver::new(rhs.as_ptr(), jacobian.as_ptr(), 4, &mut aux) };
        assert!(solver.resize(2));
        let state = solver.single_tick(10, 4, &mut NoHooks).unwrap().unwrap();
        // [[3, 1], [0, 4]] x = [7, 8]
        let x = &solver.buffers[state];
        assert!((x[1] - 2.0).abs() < 1e-12);
        assert!((x[0] - 5.0 / 3.0).abs() < 1e-12);
        assert!(solver.is_converged());
        assert_eq!(aux, 1);
    }

    #[test]
    fn empty_solver_returns_nothing() {
        let mut solver = unsafe { Solver::new(std::ptr::null(), std::ptr::null(), 0, std::ptr::null_mut()) };
        assert_eq!(solver.single_tick(10, 0, &mut NoHooks), Ok(None));
    }

    #[test]
    fn residual_hooks_modify_rhs() {
        struct Hooks([*mut f64; 2]);
        impl Callbacks for Hooks {
            fn iteration_hooks(&mut self, _: i32, _: usize) -> Result<i32, ()> { Ok(0) }
            fn add_residual(&mut self, b: usize) -> Result<(), ()> { unsafe { *self.0[b] -= 1.0 }; Ok(()) }
            fn convergence_problems(&mut self, _: f64, _: i32) -> Result<(), ()> { Ok(()) }
        }
        let jacobian = jacobian_ops(&[(0, 0, 1.0)]);
        let rhs = rhs_ops(&[], 1);
        let mut aux = 0u8;
        let mut solver = unsafe { Solver::new(rhs.as_ptr(), jacobian.as_ptr(), 1, &mut aux) };
        solver.resize(1);
        solver.set_hook_presence(false, true);
        let mut hooks = Hooks([solver.buffer_ptr(0), solver.buffer_ptr(1)]);
        let state = solver.single_tick(10, 1, &mut hooks).unwrap().unwrap();
        assert!((solver.buffers[state][0] + 1.0).abs() < 1e-12);
        assert!(solver.is_converged());
    }
}
