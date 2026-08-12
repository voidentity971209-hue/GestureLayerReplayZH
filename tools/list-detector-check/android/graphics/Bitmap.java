package android.graphics;

import java.awt.image.BufferedImage;

public final class Bitmap {
    private final BufferedImage image;
    public Bitmap(BufferedImage image) { this.image = image; }
    public int getWidth() { return image.getWidth(); }
    public int getHeight() { return image.getHeight(); }
    public int getPixel(int x, int y) { return image.getRGB(x, y); }
}
