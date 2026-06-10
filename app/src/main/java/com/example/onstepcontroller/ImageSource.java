package com.example.onstepcontroller;

/**
 * Where a frame being solved came from. Distinguishes a live camera capture (reliable lens
 * FOV, genuine failures) from an imported photo (FOV prior may be missing or wrong, so a
 * failed solve offers a manual-FOV retry).
 */
enum ImageSource {
    CAMERA,
    IMPORT
}
