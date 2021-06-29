package net.allape.housedog.util;

import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;

import org.bytedeco.opencv.opencv_core.IplImage;

import static org.bytedeco.opencv.global.opencv_core.IPL_DEPTH_8U;

public class IplImageUtils {

    public static Bitmap IplImageToBitmap(IplImage iplImage, Bitmap.Config config) {
        Bitmap bitmap = Bitmap.createBitmap(iplImage.width(), iplImage.height(), config);
        bitmap.copyPixelsFromBuffer(iplImage.createBuffer());
        return bitmap;
    }

    public static IplImage bitmapToIplImage(Bitmap bitmap, int type, int channels) {
        IplImage iplImage = IplImage.create(bitmap.getWidth(), bitmap.getHeight(), type, channels);
        bitmap.copyPixelsToBuffer(iplImage.createBuffer());
        return iplImage;
    }

    public static IplImage fromYUVBytes(RenderScript renderScript, int width, int height, byte[] bytes) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Allocation allocationRgb = Allocation.createFromBitmap(renderScript, bitmap);
        final Allocation allocationYuv = Allocation.createSized(renderScript, Element.U8(renderScript), bytes.length);
        allocationYuv.copyFrom(bytes);
        ScriptIntrinsicYuvToRGB scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(renderScript, Element.U8_4(renderScript));
        scriptYuvToRgb.setInput(allocationYuv);
        scriptYuvToRgb.forEach(allocationRgb);

        allocationRgb.copyTo(bitmap);

        try {
            return bitmapToIplImage(bitmap, IPL_DEPTH_8U, 4);
        } finally {
            allocationYuv.destroy();
            allocationRgb.destroy();
            bitmap.recycle();
        }
    }

}
