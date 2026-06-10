# Off-device plate-solve check

Runs the **real app classes** (`StarDetector.detectForSolveCore` + `PlateSolver`) on still
JPEGs, so the camera plate-solving pipeline can be reviewed without a phone. The app only
captures live, so this is how to feed it the two test photos.

Compiles the actual `app/src/main/java/.../{PlateSolver,StarDetector}.java`; `SkyCatalog`
and `android/graphics/Bitmap` here are tiny stubs (the test calls the Android-free core).

Test photos are not committed (local scratch under `work/`, gitignored). Point the
trailing image args at your own sky JPEGs.

## Run (Windows, bundled JDK 17)

```
$JH = ".toolchain\jdk\jdk-17.0.18+8\bin"
& "$JH\javac.exe" -d scripts\platesolve\jtest\out `
  app\src\main\java\com\example\onstepcontroller\PlateSolver.java `
  app\src\main\java\com\example\onstepcontroller\StarDetector.java `
  scripts\platesolve\jtest\src\android\graphics\Bitmap.java `
  scripts\platesolve\jtest\src\com\example\onstepcontroller\SkyCatalog.java `
  scripts\platesolve\jtest\src\com\example\onstepcontroller\E2ETest.java
& "$JH\java.exe" -cp scripts\platesolve\jtest\out com.example.onstepcontroller.E2ETest `
  app\src\main\assets\catalog\stars.tsv `
  scripts\platesolve\work\img_05s.jpg scripts\platesolve\work\img_1s.jpg
```

Expected: both solve to Ursa Major / Leo. img_05s ~ RA 10.41h / Dec +35.95 deg,
img_1s ~ RA 10.99h / Dec +41.10 deg, focal scaling with resolution, rms 1-2 px.
