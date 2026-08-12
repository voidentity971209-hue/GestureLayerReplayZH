package android.graphics;

public final class PointF {
    public float x;
    public float y;
    public PointF(float x, float y) { this.x = x; this.y = y; }
    @Override public String toString() { return "(" + x + "," + y + ")"; }
}
