package net.allape.housedog.activity;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;

import androidx.appcompat.app.AppCompatActivity;

import net.allape.housedog.beep.Beep;

import java.util.HashMap;
import java.util.Map;

public abstract class WatcherActivity extends AppCompatActivity {

    private static final String LOG_TAG = "WatcherActivity";

    // 检测摄像头尺寸的格式
    public static final int CAMERA_FRAME_FORMAT = ImageFormat.YUV_420_888;

    protected CameraManager cameraManager;
    protected AudioManager audioManager;

    protected Map<String, Size[]> supportedCameras;

    private Beep beep;

    /**
     * 音量最大
     */
    protected void outLoud() {
        audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
        );
//        audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND);
//        audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND);
    }

    /**
     * 播放默认声音
     */
    protected void playWarningSound() {
        if (!beep.isPlaying()) {
            outLoud();
            beep.play();
        }
    }

    /**
     * 暂停默认声音
     */
    protected void pauseWarningSound() {
        if (beep.isPlaying()) {
            beep.pause();
        }
    }

    /**
     * 是否在播放声音
     */
    protected boolean isWarningSoundPlaying() {
        return beep.isPlaying();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        beep = new Beep(1000);
        new Thread(beep).start();

        try {
            // 获取支持的摄像头尺寸
            String[] cameras = this.cameraManager.getCameraIdList();
            supportedCameras = new HashMap<>(cameras.length);
            for (String cameraId : cameras) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
                Size[] sizes = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(CAMERA_FRAME_FORMAT);
                this.supportedCameras.put(cameraId, sizes);
                for (Size size : sizes) {
                    Log.v(LOG_TAG, "camera[" + cameraId + "]: " + size.getWidth() + "x" + size.getHeight());
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
            Log.e(LOG_TAG, e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        beep.close();
    }
}
