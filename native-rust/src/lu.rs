//! Sparse LU factorization with partial pivoting (left-looking, Gilbert-Peierls).
//!
//! This stands in for SuperLU's `dgstrf`/`dgstrs`. It supports a full factorization
//! (column ordering + pivot search) and a numeric-only refactorization which reuses the
//! pivot order of a previous factorization, which is what `SamePattern_SameRowPerm` does.

use std::cmp::Reverse;
use std::collections::BinaryHeap;

const UNASSIGNED: usize = usize::MAX;

#[derive(Default, Clone)]
pub struct SparseLu {
    n: usize,
    // L (unit diagonal stored explicitly, first entry of each column) with rows relabelled by pivot order.
    lp: Vec<usize>,
    li: Vec<usize>,
    lx: Vec<f64>,
    // U with the diagonal stored as the last entry of each column.
    up: Vec<usize>,
    ui: Vec<usize>,
    ux: Vec<f64>,
    // Column k of the factorization corresponds to column `perm_c[k]` of A.
    perm_c: Vec<usize>,
    // `pivot_row[k]` is the row of A chosen as pivot of column k.
    pivot_row: Vec<usize>,
}

/// Greedy minimum degree ordering of the pattern of A + A^T.
pub fn minimum_degree_order(n: usize, colptr: &[usize], rowind: &[usize]) -> Vec<usize> {
    let mut adjacency: Vec<Vec<usize>> = vec![Vec::new(); n];
    for column in 0..n {
        for &row in &rowind[colptr[column]..colptr[column + 1]] {
            if row != column {
                adjacency[column].push(row);
                adjacency[row].push(column);
            }
        }
    }
    for list in &mut adjacency {
        list.sort_unstable();
        list.dedup();
    }

    let mut eliminated = vec![false; n];
    let mut heap = BinaryHeap::with_capacity(n);
    for (node, list) in adjacency.iter().enumerate() {
        heap.push(Reverse((list.len(), node)));
    }

    let mut order = Vec::with_capacity(n);
    let mut merged = Vec::new();
    while let Some(Reverse((degree, node))) = heap.pop() {
        if eliminated[node] || degree != adjacency[node].len() {
            continue;
        }
        eliminated[node] = true;
        order.push(node);
        let neighbors = std::mem::take(&mut adjacency[node]);
        for &neighbor in &neighbors {
            let list = &mut adjacency[neighbor];
            merged.clear();
            let (mut a, mut b) = (0, 0);
            while a < list.len() || b < neighbors.len() {
                let next = match (list.get(a), neighbors.get(b)) {
                    (Some(&x), Some(&y)) if x == y => { a += 1; b += 1; x }
                    (Some(&x), Some(&y)) if x < y => { a += 1; x }
                    (Some(_), Some(&y)) => { b += 1; y }
                    (Some(&x), None) => { a += 1; x }
                    (None, Some(&y)) => { b += 1; y }
                    (None, None) => unreachable!(),
                };
                if next != neighbor && next != node {
                    merged.push(next);
                }
            }
            std::mem::swap(list, &mut merged);
            heap.push(Reverse((list.len(), neighbor)));
        }
    }
    order
}

struct Workspace {
    x: Vec<f64>,
    stack: Vec<usize>,
    position: Vec<usize>,
    marked: Vec<bool>,
}

impl SparseLu {
    pub fn pivot_row(&self) -> &[usize] { &self.pivot_row }
    pub fn perm_c(&self) -> &[usize] { &self.perm_c }

    /// Full factorization of `A * Q` with partial pivoting.
    /// A diagonal entry is only preferred over the largest candidate when it is equally large.
    pub fn factorize(n: usize, colptr: &[usize], rowind: &[usize], values: &[f64], perm_c: Vec<usize>) -> Option<SparseLu> {
        Self::compute(n, colptr, rowind, values, perm_c, None)
    }

    /// Numeric refactorization reusing the pivot order of `previous`.
    pub fn refactorize(previous: &SparseLu, colptr: &[usize], rowind: &[usize], values: &[f64]) -> Option<SparseLu> {
        Self::compute(previous.n, colptr, rowind, values, previous.perm_c.clone(), Some(&previous.pivot_row))
    }

    fn compute(n: usize, colptr: &[usize], rowind: &[usize], values: &[f64], perm_c: Vec<usize>, fixed_pivots: Option<&[usize]>) -> Option<SparseLu> {
        let nnz_estimate = colptr[n] * 4 + n;
        let mut lu = SparseLu {
            n,
            lp: vec![0; n + 1],
            li: Vec::with_capacity(nnz_estimate),
            lx: Vec::with_capacity(nnz_estimate),
            up: vec![0; n + 1],
            ui: Vec::with_capacity(nnz_estimate),
            ux: Vec::with_capacity(nnz_estimate),
            perm_c,
            pivot_row: vec![0; n],
        };
        let mut pinv = vec![UNASSIGNED; n];
        let mut work = Workspace {
            x: vec![0.0; n],
            stack: vec![0; n],
            position: vec![0; n],
            marked: vec![false; n],
        };
        let mut reach = vec![0usize; n];

        for k in 0..n {
            let column = lu.perm_c[k];
            lu.lp[k] = lu.li.len();
            lu.up[k] = lu.ui.len();

            let top = lu.sparse_solve(colptr, rowind, values, column, &pinv, &mut reach, &mut work);

            let mut pivot = UNASSIGNED;
            let mut largest = -1.0f64;
            for &row in &reach[top..n] {
                if pinv[row] == UNASSIGNED {
                    let magnitude = work.x[row].abs();
                    if magnitude > largest {
                        largest = magnitude;
                        pivot = row;
                    }
                } else {
                    lu.ui.push(pinv[row]);
                    lu.ux.push(work.x[row]);
                }
            }
            match fixed_pivots {
                Some(rows) => {
                    pivot = rows[k];
                    if pinv[pivot] != UNASSIGNED { return None; }
                }
                None => {
                    if pivot == UNASSIGNED || !(largest > 0.0) { return None; }
                    if pinv[column] == UNASSIGNED && work.x[column].abs() >= largest {
                        pivot = column;
                    }
                }
            }
            let pivot_value = work.x[pivot];
            if pivot_value == 0.0 || !pivot_value.is_finite() { return None; }

            lu.ui.push(k);
            lu.ux.push(pivot_value);
            pinv[pivot] = k;
            lu.pivot_row[k] = pivot;
            lu.li.push(pivot);
            lu.lx.push(1.0);
            for &row in &reach[top..n] {
                if pinv[row] == UNASSIGNED {
                    lu.li.push(row);
                    lu.lx.push(work.x[row] / pivot_value);
                }
                work.x[row] = 0.0;
            }
        }
        lu.lp[n] = lu.li.len();
        lu.up[n] = lu.ui.len();
        for row in &mut lu.li {
            *row = pinv[*row];
        }
        if lu.ux.iter().any(|v| !v.is_finite()) || lu.lx.iter().any(|v| !v.is_finite()) {
            return None;
        }
        Some(lu)
    }

    /// Solves `x = L \ A(:, column)` over the nonzero pattern reachable from the column.
    /// Returns the start of the topologically ordered pattern in `reach`.
    #[allow(clippy::too_many_arguments)]
    fn sparse_solve(&self, colptr: &[usize], rowind: &[usize], values: &[f64], column: usize,
                    pinv: &[usize], reach: &mut [usize], work: &mut Workspace) -> usize {
        let n = self.n;
        let mut top = n;
        for &row in &rowind[colptr[column]..colptr[column + 1]] {
            if !work.marked[row] {
                top = self.depth_first(row, top, pinv, reach, work);
            }
        }
        for &row in &reach[top..n] {
            work.marked[row] = false;
        }
        for &row in &reach[top..n] {
            work.x[row] = 0.0;
        }
        for p in colptr[column]..colptr[column + 1] {
            work.x[rowind[p]] = values[p];
        }
        for index in top..n {
            let j = reach[index];
            let pivot_column = pinv[j];
            if pivot_column == UNASSIGNED { continue; }
            let start = self.lp[pivot_column];
            let end = self.lp[pivot_column + 1];
            work.x[j] /= self.lx[start];
            let xj = work.x[j];
            for p in start + 1..end {
                work.x[self.li[p]] -= self.lx[p] * xj;
            }
        }
        top
    }

    fn depth_first(&self, start: usize, mut top: usize, pinv: &[usize], reach: &mut [usize], work: &mut Workspace) -> usize {
        let mut head = 0usize;
        work.stack[0] = start;
        loop {
            let j = work.stack[head];
            let pivot_column = pinv[j];
            if !work.marked[j] {
                work.marked[j] = true;
                work.position[head] = if pivot_column == UNASSIGNED { 0 } else { self.lp[pivot_column] };
            }
            let end = if pivot_column == UNASSIGNED { 0 } else { self.lp[pivot_column + 1] };
            let mut finished = true;
            let mut p = work.position[head];
            while p < end {
                let i = self.li[p];
                p += 1;
                if work.marked[i] { continue; }
                work.position[head] = p;
                head += 1;
                work.stack[head] = i;
                finished = false;
                break;
            }
            if finished {
                top -= 1;
                reach[top] = j;
                if head == 0 { break; }
                head -= 1;
            }
        }
        top
    }

    /// Solves `A x = b` in place.
    pub fn solve(&self, b: &mut [f64], scratch: &mut Vec<f64>) {
        let n = self.n;
        scratch.clear();
        scratch.resize(n, 0.0);
        // y = P b
        for k in 0..n {
            scratch[k] = b[self.pivot_row[k]];
        }
        // L y = P b (unit lower triangular, diagonal stored first)
        for j in 0..n {
            let yj = scratch[j];
            for p in self.lp[j] + 1..self.lp[j + 1] {
                scratch[self.li[p]] -= self.lx[p] * yj;
            }
        }
        // U z = y (diagonal stored last)
        for j in (0..n).rev() {
            let end = self.up[j + 1];
            scratch[j] /= self.ux[end - 1];
            let zj = scratch[j];
            for p in self.up[j]..end - 1 {
                scratch[self.ui[p]] -= self.ux[p] * zj;
            }
        }
        // x = Q z
        for k in 0..n {
            b[self.perm_c[k]] = scratch[k];
        }
    }
}
