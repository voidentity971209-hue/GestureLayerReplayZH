package com.example.gesturereplayzh;

import android.graphics.Bitmap;
import android.graphics.RectF;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Method;

public final class ListDetectorCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 5) {
            throw new IllegalArgumentException("image path [left top right bottom]");
        }
        Bitmap bitmap = new Bitmap(ImageIO.read(new File(args[0])));
        PokemonListDetector.Result result = PokemonListDetector.find(bitmap);
        System.out.println("panel=" + result.panel);
        System.out.println("confidence=" + result.confidence);
        System.out.println("collapsed=" + result.isCollapsed(bitmap.getWidth()));
        System.out.println("toggle=" + result.toggle);
        System.out.println("candidates=" + result.candidates);
        Method score = PokemonListDetector.class.getDeclaredMethod(
                "panelScore", Bitmap.class, RectF.class);
        score.setAccessible(true);
        if (args.length == 5) {
            RectF probe = new RectF(
                    Float.parseFloat(args[1]), Float.parseFloat(args[2]),
                    Float.parseFloat(args[3]), Float.parseFloat(args[4]));
            System.out.println("probe=" + probe + " score=" + score.invoke(null, bitmap, probe));
        }
    }
}
