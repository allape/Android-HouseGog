package net.allape.housedog.view;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.NonNull;

import net.allape.housedog.util.CanvasUtils;
import net.allape.housedog.util.IplImageUtils;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_core.CvContour;
import org.bytedeco.opencv.opencv_core.CvMemStorage;
import org.bytedeco.opencv.opencv_core.CvSeq;
import org.bytedeco.opencv.opencv_core.IplImage;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import pub.devrel.easypermissions.EasyPermissions;

import static org.bytedeco.opencv.global.opencv_core.IPL_DEPTH_8U;
import static org.bytedeco.opencv.global.opencv_core.cvAbsDiff;
import static org.bytedeco.opencv.global.opencv_core.cvFlip;
import static org.bytedeco.opencv.global.opencv_core.cvReleaseImage;
import static org.bytedeco.opencv.global.opencv_core.cvTranspose;
import static org.bytedeco.opencv.global.opencv_imgproc.CV_CHAIN_APPROX_SIMPLE;
import static org.bytedeco.opencv.global.opencv_imgproc.CV_GRAY2BGRA;
import static org.bytedeco.opencv.global.opencv_imgproc.CV_RETR_LIST;
import static org.bytedeco.opencv.global.opencv_imgproc.CV_RGB2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.CV_THRESH_BINARY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvCvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.cvResize;
import static org.bytedeco.opencv.global.opencv_imgproc.cvThreshold;
import static org.bytedeco.opencv.helper.opencv_imgproc.cvFindContours;

@SuppressLint("ViewConstructor")
public class MotionDetectionView extends androidx.appcompat.widget.AppCompatImageView implements ImageReader.OnImageAvailableListener {

    private static final String LOG_TAG = "MotionDetectionView";

    // ????????????
    public static final int FORMAT = ImageFormat.YUV_420_888;

    // ??????????????????
    private final CameraManager cameraManager;
    // ?????????????????????????????????????????????
    private final Map<String, Size[]> supportedCameras;

    // ??????????????????
    private String cameraId;
    // ????????????
    private int templateType = CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG;
    // ??????????????????????????????
    private boolean flashLightEnabled = true;

    // ??????????????????
    private ImageReader frameReader;

    // ?????????????????????
    private CameraDevice camera;
    // ????????????session
    private CameraCaptureSession session;

    // ??????
    private int width;
    // ??????
    private int height;
    // ??????
    private int frameRate = 30;
    // ????????????(ms), ??????????????????????????????
    private long singleFrameDuration = 1000 / frameRate;
    // ??????????????????????????? ms
    private long lastTime = 0L;

    // ?????????????????????/??????????????????
    private int scale = 8;
    // ???????????????
    private int diffThreshold = 150;

    // ?????????
    private final RenderScript renderScript;
    // ???????????????
    private final CvMemStorage storage = CvMemStorage.create();
    // ????????????????????????
    private IplImage lastFrame;

    private final Activity activity;
    private final MotionDetectionListener listener;

    public MotionDetectionView(Activity activity, MotionDetectionListener listener) throws CameraAccessException, NoPermissionsException {
        super(activity);

        this.activity = activity;
        this.listener = listener;

        // ????????????
        boolean hasPermissions = EasyPermissions.hasPermissions(this.getContext(), Manifest.permission.CAMERA);
        if (!hasPermissions) {
            Toast.makeText(this.getContext(), "Please grant all permissions in app settings", Toast.LENGTH_LONG).show();
            throw new NoPermissionsException("No Permissions");
        }

        this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        this.renderScript = RenderScript.create(activity);

        // ?????????????????????
        String[] cameras = this.cameraManager.getCameraIdList();
        if (this.cameraManager.getCameraIdList().length == 0) {
            Toast.makeText(activity, "No Camera", Toast.LENGTH_SHORT).show();
            throw new NoCameraException();
        }
        this.cameraId = cameras[0];

        // ??????????????????????????????
        this.supportedCameras = new HashMap<>(cameras.length);
        for (String cameraId : cameras) {
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            Size[] sizes = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(FORMAT);
            this.supportedCameras.put(cameraId, sizes);

            // ???????????????????????????
            if (sizes.length > 0 && cameraId.equals(this.cameraId)) {
                Size middleSize = sizes[sizes.length / 2];
                this.width = middleSize.getWidth();
                this.height = middleSize.getHeight();
            }
        }
    }

    /**
     * ???????????????
     */
    public void close() {
        if (lastFrame != null) {
            lastFrame.close();
            lastFrame = null;
        }
        if (frameReader != null) {
            frameReader.close();
            frameReader = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
    }

    /**
     * ????????????, ????????????view???????????????
     */
    public void destroy() {
        close();
        if (renderScript != null) {
            renderScript.destroy();
        }
        if (storage != null) {
            storage.close();
        }
    }

    /**
     * ????????????????????? ???????????????{@link this#openCamera()}
     * @param id ?????????ID
     * @param templateType ????????????, ??????{@link CameraDevice#TEMPLATE_ZERO_SHUTTER_LAG}
     * @param flashLightEnabled ?????????????????????
     */
    public void changeCamera(String id, int templateType, boolean flashLightEnabled) {
        if (supportedCameras.get(id) == null) {
            throw new CameraNotExistException(id + " does NOT exist");
        }
        this.cameraId = id;
        this.templateType = templateType;
        this.flashLightEnabled = flashLightEnabled;
    }

    /**
     * ???????????????????????? ???????????????{@link this#openCamera()}
     * @param width ??????
     * @param height ??????
     * @param frameRate ??????
     * @param scale ??????????????????
     * @param diffThreshold ?????????????????????
     */
    public void resize(int width, int height, int frameRate, int scale, int diffThreshold) {
        close();

        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.singleFrameDuration = 1000 / frameRate;
        this.scale = scale;
        this.diffThreshold = diffThreshold;

        boolean usableResolution = false;
        Size[] sizes = supportedCameras.get(cameraId);
        assert sizes != null;
        for (Size size : sizes) {
            if (size.getWidth() == width && size.getHeight() == height) {
                usableResolution = true;
            }
        }
        if (!usableResolution) {
            String msg = width + " x " + height + " might cause some problems";
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
            Log.w(LOG_TAG, msg);
        }
    }

    /**
     * ???????????????
     */
    @SuppressLint("MissingPermission")
    public void openCamera() {
        close();
        activity.runOnUiThread(() -> {
            // ?????????????????????
            frameReader = ImageReader.newInstance(width, height, FORMAT, 3);
            frameReader.setOnImageAvailableListener(this, null);

            MotionDetectionView self = this;

            try {
                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        self.camera = camera;
                        try {
                            CaptureRequest.Builder req = camera.createCaptureRequest(templateType);
                            req.addTarget(frameReader.getSurface());
                            req.set(CaptureRequest.FLASH_MODE, flashLightEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                            camera.createCaptureSession(
                                    Collections.singletonList(frameReader.getSurface()),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(@NonNull CameraCaptureSession session) {
                                            try {
                                                session.setRepeatingRequest(req.build(), null, null);
                                                self.session = session;
                                            } catch (CameraAccessException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                        @Override
                                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                            Log.e(LOG_TAG, "Failed to create capture session");
                                        }
                                    }, null
                            );
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                            Log.e(LOG_TAG, e.getMessage());
                        }
                    }
                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        Log.e(LOG_TAG, "camera disconnected");
                    }
                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        switch (error) {
                            case ERROR_CAMERA_DEVICE: Log.e(LOG_TAG, "Fatal (device)"); break;
                            case ERROR_CAMERA_DISABLED: Log.e(LOG_TAG, "Device policy"); break;
                            case ERROR_CAMERA_IN_USE: Log.e(LOG_TAG, "Camera in use"); break;
                            case ERROR_CAMERA_SERVICE: Log.e(LOG_TAG, "Fatal (service)"); break;
                            case ERROR_MAX_CAMERAS_IN_USE: Log.e(LOG_TAG, "Maximum cameras in use"); break;
                            default: Log.e(LOG_TAG, "Unknown");
                        }
                    }
                }, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                Log.v(LOG_TAG, e.getMessage());
            }
        });
    }

    /**
     * ????????????
     * @return true/??????????????????
     */
    public boolean isOn() {
        return session != null;
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();

        if (System.currentTimeMillis() - lastTime < singleFrameDuration) {
            if (image != null) {
                image.close();
            }
            return;
        }

        try {
            if (image != null) {
                // ????????????
                ByteBuffer yChannel = image.getPlanes()[0].getBuffer();
                ByteBuffer vChannel = image.getPlanes()[2].getBuffer();

                int yChannelSize = yChannel.remaining();
                int vChannelSize = vChannel.remaining();

                byte[] bytes = new byte[yChannelSize + vChannelSize];

                yChannel.get(bytes, 0, yChannelSize);
                vChannel.get(bytes, yChannelSize, vChannelSize);

                lastTime = System.currentTimeMillis();

                // ????????????
                try {
                    IplImage sourceImage = IplImageUtils.fromYUVBytes(renderScript, width, height, bytes);
                    IplImage resizedImage = IplImage.create(width / scale, height / scale, sourceImage.depth(), sourceImage.nChannels());
                    cvResize(sourceImage, resizedImage);
                    IplImage rotatedImage = IplImage.create(resizedImage.height(), resizedImage.width(), resizedImage.depth(), resizedImage.nChannels());
                    cvTranspose(resizedImage, rotatedImage);
                    cvFlip(rotatedImage, rotatedImage, 1);
                    if (lastFrame != null) {
                        IplImage curr = IplImage.create(rotatedImage.width(), rotatedImage.height(), IPL_DEPTH_8U, 1);
                        cvCvtColor(rotatedImage, curr, CV_RGB2GRAY);

                        IplImage diff = IplImage.create(curr.width(), curr.height(), IPL_DEPTH_8U, 1);
                        cvAbsDiff(curr, lastFrame, diff);
                        cvThreshold(diff, diff, diffThreshold, 255, CV_THRESH_BINARY);

                        CvSeq contour = new CvSeq(null);
                        cvFindContours(diff, storage, contour, Loader.sizeof(CvContour.class), CV_RETR_LIST, CV_CHAIN_APPROX_SIMPLE);
                        boolean moved = !contour.isNull() && contour.elem_size() > 0;
                        contour.close();
                        storage.free_space();

                        IplImage rgbaImage = IplImage.create(rotatedImage.width(), rotatedImage.height(), IPL_DEPTH_8U, 4);
                        cvCvtColor(moved ? diff : curr, rgbaImage, CV_GRAY2BGRA);

                        Bitmap displayBitmap = IplImageUtils.IplImageToBitmap(rgbaImage, Bitmap.Config.ARGB_8888);

                        Canvas diffCanvas = new Canvas(displayBitmap);
                        diffCanvas.drawRect(
                                new Rect(0, 0, displayBitmap.getWidth(), displayBitmap.getHeight()),
                                moved ? CanvasUtils.STROKE_RED : CanvasUtils.STROKE_GREEN
                        );

                        setImageBitmap(displayBitmap);

                        rgbaImage.close();
                        cvReleaseImage(rgbaImage);

                        // ??????
                        listener.onFrame(bytes, sourceImage, lastFrame, curr, diff, moved);

                        diff.close();
                        cvReleaseImage(diff);
                        lastFrame.close();
                        cvReleaseImage(lastFrame);

                        lastFrame = curr;
                    } else {
                        lastFrame = IplImage.create(rotatedImage.width(), rotatedImage.height(), IPL_DEPTH_8U, 1);
                        cvCvtColor(rotatedImage, lastFrame, CV_RGB2GRAY);
                    }
                    rotatedImage.close();
                    cvReleaseImage(rotatedImage);
                    resizedImage.close();
                    cvReleaseImage(resizedImage);
                    sourceImage.close();
                    cvReleaseImage(sourceImage);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(LOG_TAG, e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }
    
    public interface MotionDetectionListener {
        /**
         * ???????????????, ????????????IplImage???close, ??????????????????????????????
         * @param rawBytes ????????????
         * @param source ???????????????????????????????????????
         * @param prev ????????????
         * @param curr ?????????
         * @param diff ?????????
         * @param moved ??????????????????????????????
         */
        void onFrame(byte[] rawBytes, IplImage source, IplImage prev, IplImage curr, IplImage diff, boolean moved);
    }

    static class MotionDetectionViewException extends RuntimeException {
        public MotionDetectionViewException() { }
        public MotionDetectionViewException(String message) {
            super(message);
        }
    }
    static class NoPermissionsException extends MotionDetectionViewException {
        public NoPermissionsException(String message) {
            super(message);
        }
    }
    static class NoCameraException extends MotionDetectionViewException { }
    static class CameraNotExistException extends MotionDetectionViewException {
        public CameraNotExistException(String message) {
            super(message);
        }
    }

}
