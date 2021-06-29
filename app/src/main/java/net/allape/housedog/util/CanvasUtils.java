package net.allape.housedog.util;

import android.graphics.Color;
import android.graphics.Paint;

public class CanvasUtils {

    public static final Paint STROKE_RED = new Paint();
    static {
        STROKE_RED.setColor(Color.RED);
        STROKE_RED.setStyle(Paint.Style.STROKE);
        STROKE_RED.setStrokeWidth(10);
    }

    public static final Paint STROKE_GREEN = new Paint();
    static {
        STROKE_GREEN.setColor(Color.GREEN);
        STROKE_GREEN.setStyle(Paint.Style.STROKE);
        STROKE_GREEN.setStrokeWidth(10);
    }

}
