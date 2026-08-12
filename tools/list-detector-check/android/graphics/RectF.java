package android.graphics;

public final class RectF {
    public float left, top, right, bottom;
    public RectF() {}
    public RectF(float left, float top, float right, float bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public RectF(RectF other) { this(other.left, other.top, other.right, other.bottom); }
    public float width() { return right - left; }
    public float height() { return bottom - top; }
    public float centerX() { return (left + right) / 2f; }
    public boolean isEmpty() { return width() <= 0 || height() <= 0; }
    public boolean contains(float x, float y) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }
    @Override public String toString() {
        return "[" + left + "," + top + " - " + right + "," + bottom + "]";
    }
}
