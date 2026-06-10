package com.example.onstepcontroller;

// Minimal stand-in for the app's SkyCatalog.Star, ONLY for the off-device solver test.
// Field names/access match the real class so PlateSolver compiles unchanged. This file is
// under scripts/ and is never part of the Android build.
final class SkyCatalog {
    static final class Star {
        final String name;
        final double raHours;
        final double decDegrees;
        final double magnitude;

        Star(String name, double raHours, double decDegrees, double magnitude) {
            this.name = name;
            this.raHours = raHours;
            this.decDegrees = decDegrees;
            this.magnitude = magnitude;
        }
    }
}
