package com.example.onstepcontroller;

/**
 * The outcome of running {@link ImageSolveEngine} on one frame: the detected star field and,
 * if the blind solve succeeded, the pointing solution (null otherwise). A single result type
 * shared by every image source -- live capture, imported photo, polar-alignment shots -- so
 * callers consume detection + solve the same way.
 */
final class ImageSolveResult {
    final StarDetector.StarField field;
    final PlateSolver.Solution solution; // null if the frame did not solve

    ImageSolveResult(StarDetector.StarField field, PlateSolver.Solution solution) {
        this.field = field;
        this.solution = solution;
    }

    boolean solved() {
        return solution != null;
    }
}
