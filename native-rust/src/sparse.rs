use crate::lu::{minimum_degree_order, SparseLu};

pub const STRUCTURE_MODIFIED: u8 = 1;
pub const REFACTORIZE: u8 = 2;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Fact {
    DoFact,
    SamePatternSameRowPerm,
}

/// Column compressed square matrix with sorted row indices.
/// Mirrors `sparsematrix_t`, entries can only be added and are never removed until the matrix is zeroed.
pub struct SparseMatrix {
    size: usize,
    columns: Vec<usize>,
    row_indices: Vec<usize>,
    elements: Vec<f64>,
    flags: u8,
    fact: Fact,
    lu: Option<SparseLu>,
    // Pivot order of the last full factorization, kept for `SamePatternSameRowPerm`.
    previous: Option<SparseLu>,
    scratch: Vec<f64>,
}

impl Default for SparseMatrix {
    fn default() -> Self {
        Self {
            size: 0,
            columns: Vec::new(),
            row_indices: Vec::new(),
            elements: Vec::new(),
            flags: STRUCTURE_MODIFIED | REFACTORIZE,
            fact: Fact::DoFact,
            lu: None,
            previous: None,
            scratch: Vec::new(),
        }
    }
}

impl SparseMatrix {
    pub fn size(&self) -> usize { self.size }
    pub fn flags(&self) -> u8 { self.flags }
    pub fn fact(&self) -> Fact { self.fact }
    pub fn element_count(&self) -> usize { self.elements.len() }

    fn free_logical(&mut self) {
        self.fact = Fact::DoFact;
        self.lu = None;
    }

    pub fn resize(&mut self, size: usize) {
        if self.size == size {
            return;
        }
        self.size = size;
        self.free_logical();
        self.previous = None;
        self.row_indices = Vec::new();
        self.elements = Vec::new();
        self.columns = Vec::new();
        self.flags |= STRUCTURE_MODIFIED;
        if size == 0 {
            return;
        }
        self.columns = vec![0; size + 1];
        self.zero();
    }

    pub fn zero(&mut self) {
        self.fact = Fact::DoFact;
        self.flags |= REFACTORIZE | STRUCTURE_MODIFIED;
        self.free_logical();
        self.previous = None;
        self.row_indices.clear();
        self.elements.clear();
        self.columns.iter_mut().for_each(|c| *c = 0);
    }

    pub fn get(&self, row: usize, column: usize) -> f64 {
        let (start, end) = (self.columns[column], self.columns[column + 1]);
        for index in start..end {
            if self.row_indices[index] == row {
                return self.elements[index];
            }
            if self.row_indices[index] > row {
                break;
            }
        }
        0.0
    }

    /// Finds the element, inserting a zero entry (and flagging a structure change) when it doesn't exist.
    fn element_index(&mut self, row: usize, column: usize) -> usize {
        let (start, end) = (self.columns[column], self.columns[column + 1]);
        let position = match self.row_indices[start..end].binary_search(&row) {
            Ok(offset) => return start + offset,
            Err(offset) => start + offset,
        };
        self.flags |= STRUCTURE_MODIFIED;
        self.row_indices.insert(position, row);
        self.elements.insert(position, 0.0);
        for c in &mut self.columns[column + 1..] {
            *c += 1;
        }
        position
    }

    pub fn set(&mut self, row: usize, column: usize, value: f64) {
        let index = self.element_index(row, column);
        self.elements[index] = value;
        self.flags |= REFACTORIZE;
    }

    pub fn add(&mut self, row: usize, column: usize, value: f64) {
        let index = self.element_index(row, column);
        self.elements[index] += value;
        self.flags |= REFACTORIZE;
    }

    fn form_logical_a(&mut self) {
        self.free_logical();
        self.flags &= !STRUCTURE_MODIFIED;
        self.flags |= REFACTORIZE;
        self.fact = Fact::DoFact;
    }

    /// Makes the matrix usable for products, equivalent of `sparsematrix_supermatrix`.
    pub fn prepare(&mut self) {
        if self.flags & STRUCTURE_MODIFIED != 0 {
            self.form_logical_a();
        }
    }

    /// `y = alpha * A * x + beta * y` (SuperLU `sp_dgemv` without transposition).
    pub fn gemv(&self, alpha: f64, x: &[f64], beta: f64, y: &mut [f64]) {
        if beta != 1.0 {
            if beta == 0.0 {
                y.iter_mut().for_each(|v| *v = 0.0);
            } else {
                y.iter_mut().for_each(|v| *v *= beta);
            }
        }
        for column in 0..self.size {
            let scaled = alpha * x[column];
            for index in self.columns[column]..self.columns[column + 1] {
                y[self.row_indices[index]] += scaled * self.elements[index];
            }
        }
    }

    pub fn same_pattern(&mut self, value: bool) {
        self.fact = if value { Fact::SamePatternSameRowPerm } else { Fact::DoFact };
    }

    fn full_factorization(&mut self) -> Option<SparseLu> {
        let perm_c = minimum_degree_order(self.size, &self.columns, &self.row_indices);
        SparseLu::factorize(self.size, &self.columns, &self.row_indices, &self.elements, perm_c)
    }

    pub fn factorize(&mut self) {
        if self.flags & STRUCTURE_MODIFIED != 0 {
            self.form_logical_a();
        }
        if self.fact != Fact::SamePatternSameRowPerm {
            self.free_logical();
        }
        let mut factored = None;
        if self.fact == Fact::SamePatternSameRowPerm {
            if let Some(previous) = &self.previous {
                factored = SparseLu::refactorize(previous, &self.columns, &self.row_indices, &self.elements);
            }
            // The stored pivots are no longer usable, search for new ones.
            if factored.is_none() {
                factored = self.full_factorization();
            }
        } else {
            factored = self.full_factorization();
        }
        match factored {
            Some(lu) => {
                self.previous = Some(lu.clone());
                self.lu = Some(lu);
            }
            None => {
                self.previous = None;
                self.lu = None;
            }
        }
        self.flags &= !REFACTORIZE;
    }

    /// Solves `A x = b` in place. Returns false when the matrix is singular, `b` is then left untouched.
    pub fn solve(&mut self, b: &mut [f64]) -> bool {
        if self.flags & REFACTORIZE != 0 || self.lu.is_none() {
            self.factorize();
        }
        match &self.lu {
            Some(lu) => {
                lu.solve(b, &mut self.scratch);
                true
            }
            None => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dense_solve(a: &[Vec<f64>], b: &[f64]) -> Vec<f64> {
        let n = b.len();
        let mut m: Vec<Vec<f64>> = a.iter().zip(b).map(|(row, &rhs)| { let mut r = row.clone(); r.push(rhs); r }).collect();
        for col in 0..n {
            let pivot = (col..n).max_by(|&x, &y| m[x][col].abs().partial_cmp(&m[y][col].abs()).unwrap()).unwrap();
            m.swap(col, pivot);
            for row in col + 1..n {
                let f = m[row][col] / m[col][col];
                for k in col..=n { let v = m[col][k]; m[row][k] -= f * v; }
            }
        }
        let mut x = vec![0.0; n];
        for row in (0..n).rev() {
            let s: f64 = (row + 1..n).map(|k| m[row][k] * x[k]).sum();
            x[row] = (m[row][n] - s) / m[row][row];
        }
        x
    }

    fn random_system(n: usize, seed: &mut u64) -> (Vec<Vec<f64>>, Vec<f64>) {
        let mut next = || { *seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); ((*seed >> 33) as f64) / (1u64 << 31) as f64 };
        let mut a = vec![vec![0.0; n]; n];
        for i in 0..n {
            for j in 0..n {
                if i == j || next() < 0.2 { a[i][j] = next() * 2.0 - 1.0; }
            }
            a[i][i] += if i % 3 == 0 { 0.0 } else { 3.0 };
        }
        let b = (0..n).map(|_| next()).collect();
        (a, b)
    }

    fn fill(matrix: &mut SparseMatrix, a: &[Vec<f64>]) {
        for (i, row) in a.iter().enumerate() {
            for (j, &v) in row.iter().enumerate() {
                if v != 0.0 { matrix.add(i, j, v); }
            }
        }
    }

    #[test]
    fn matches_dense_solver() {
        let mut seed = 42;
        for n in [1usize, 2, 5, 17, 60] {
            let (a, b) = random_system(n, &mut seed);
            let mut matrix = SparseMatrix::default();
            matrix.resize(n);
            fill(&mut matrix, &a);
            matrix.prepare();
            let mut x = b.clone();
            assert!(matrix.solve(&mut x));
            let expected = dense_solve(&a, &b);
            for (got, want) in x.iter().zip(&expected) {
                assert!((got - want).abs() < 1e-9 * (1.0 + want.abs()), "n={n}: {got} vs {want}");
            }
        }
    }

    #[test]
    fn refactorization_reuses_pivots() {
        let mut matrix = SparseMatrix::default();
        matrix.resize(3);
        for (i, j, v) in [(0, 0, 4.0), (1, 1, 5.0), (2, 2, 6.0), (0, 1, 1.0), (1, 0, 1.0), (2, 1, -1.0)] {
            matrix.add(i, j, v);
        }
        matrix.prepare();
        let mut x = vec![1.0, 2.0, 3.0];
        assert!(matrix.solve(&mut x));
        matrix.same_pattern(true);
        matrix.add(0, 0, 1.0);
        matrix.add(2, 2, 2.0);
        let mut y = vec![1.0, 2.0, 3.0];
        assert!(matrix.solve(&mut y));
        assert_eq!(matrix.fact(), Fact::SamePatternSameRowPerm);
        let a = vec![vec![5.0, 1.0, 0.0], vec![1.0, 5.0, 0.0], vec![0.0, -1.0, 8.0]];
        let expected = dense_solve(&a, &[1.0, 2.0, 3.0]);
        for (got, want) in y.iter().zip(&expected) {
            assert!((got - want).abs() < 1e-12);
        }
    }

    #[test]
    fn singular_matrix_is_reported() {
        let mut matrix = SparseMatrix::default();
        matrix.resize(2);
        matrix.add(0, 0, 1.0);
        matrix.add(0, 1, 1.0);
        matrix.prepare();
        let mut x = vec![1.0, 1.0];
        assert!(!matrix.solve(&mut x));
        assert_eq!(x, vec![1.0, 1.0]);
    }
}
