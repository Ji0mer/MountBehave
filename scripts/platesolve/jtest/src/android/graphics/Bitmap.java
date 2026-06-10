package android.graphics;

// Minimal stub so the app's StarDetector.java compiles off-device. The end-to-end test
// calls only detectForSolveCore(int[],...), which never touches Bitmap; these are unused.
public final class Bitmap {
    public int getWidth() { throw new UnsupportedOperationException(); }
    public int getHeight() { throw new UnsupportedOperationException(); }
    public boolean isRecycled() { return false; }
    public void recycle() { }
    public void getPixels(int[] p, int o, int s, int x, int y, int w, int h) {
        throw new UnsupportedOperationException();
    }
    public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) {
        throw new UnsupportedOperationException();
    }
}
