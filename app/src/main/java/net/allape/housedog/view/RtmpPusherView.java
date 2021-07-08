package net.allape.housedog.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@SuppressLint("ViewConstructor")
public class RtmpPusherView extends androidx.appcompat.widget.AppCompatTextView {

    private static final String LOG_TAG = "RtmpPusherView";

    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    private final Activity activity;

    // 直播的URL
    private String url;
    // 直播的宽度
    private int width = 1920;
    // 直播的高度
    private int height = 1080;
    // 直播的帧率
    private int frameRate = 30;
    // 声音采样码率
    private int audioRateInHz = 44100;

    // 开始直播的时间 ms
    private long startTime = 0L;

    // 直播器
    private FFmpegFrameRecorder recorder;
    // 一帧
    private Frame frame;
    // 录音线程
    private AudioRecordRunnable audioRecordRunnable;

    private boolean networkAvailable = true;

    @RequiresApi(api = Build.VERSION_CODES.N)
    public RtmpPusherView(Activity activity) {
        super(activity);
        this.activity = activity;

        // 网络监听
        ConnectivityManager connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        // 网络监听
        connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                setText("network comes alive");
                networkAvailable = true;
            }
            @SuppressLint("SetTextI18n")
            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                setText("network goes down");
                networkAvailable = false;
            }
        });
    }

    /**
     * 设置参数 需手动调用{@link this#start()}
     */
    public void config(String url, int width, int height, int frameRate, int audioRateInHz) {
        this.url = url;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.audioRateInHz = audioRateInHz;
    }

    /**
     * 初始化并开始录像
     */
    public void start() {
        close();
        activity.runOnUiThread(() -> new Handler().post(() -> {
            // 帧
            frame = new Frame(width, height, Frame.DEPTH_UBYTE, 2);

            try {
                recorder = new FFmpegFrameRecorder(url, width, height, 1);
                recorder.setFormat("mp4");
                recorder.setFrameRate(frameRate);
                recorder.setSampleRate(audioRateInHz);
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                Log.v(LOG_TAG, "recorder: " + url + " width: " + width + " height " + height);
                recorder.start();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
                Log.e(LOG_TAG, e.getMessage());
            }

            // 录音
            audioRecordRunnable = new AudioRecordRunnable(audioRateInHz, recorder);
            Thread audioRecordRunnableThread = new Thread(audioRecordRunnable);
            audioRecordRunnableThread.start();

            startTime = System.currentTimeMillis();
        }));
    }

    /**
     * 添加一帧, YUV格式的
     * @param bytes YUV字节数组
     */
    public void pushYuvBytes(byte[] bytes) {
        ((ByteBuffer) frame.image[0].position(0)).put(bytes);
        push(frame);
    }

    /**
     * 添加一帧
     * @param frame 帧数据
     */
    @SuppressLint("SetTextI18n")
    public void push(Frame frame) {
        if (recorder == null) {
            Log.w(LOG_TAG, "recorder not initialized");
            return;
        } else if (!networkAvailable) {
            Log.w(LOG_TAG, "network not available");
            return;
        }
        try {
            long current = System.currentTimeMillis();
            recorder.setTimestamp(1000 * (current - startTime));
            recorder.record(frame);
            activity.runOnUiThread(() -> setText(FORMAT.format(new Date()) + ": write " + frame.toString()));
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
            Log.e(LOG_TAG, e.getMessage());
        }
    }

    /**
     * 关闭资源
     */
    public void close() {
        if (frame != null) {
            frame.close();
            frame = null;
        }
        if (audioRecordRunnable != null) {
            audioRecordRunnable.close();
            audioRecordRunnable = null;
        }
        if (recorder != null) {
            try {
                recorder.close();
            } catch (FrameRecorder.Exception e) {
                e.printStackTrace();
                Log.e(LOG_TAG, e.getMessage());
            }
            recorder = null;
        }
    }

    /**
     * 是否正在录制
     * @return true: 正在录制
     */
    public boolean isRecording() {
        return this.recorder != null;
    }

    static class AudioRecordRunnable implements Runnable {

        // 采样率
        private final int rateInHz;
        // 录音器
        private final FFmpegFrameRecorder recorder;

        // 是否在下个循环停止录音
        private boolean endAtNext = false;

        public AudioRecordRunnable(int rateInHz, FFmpegFrameRecorder recorder) {
            this.rateInHz = rateInHz;
            this.recorder = recorder;
        }

        @Override
        public void run() {
            // Set the thread priority
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            short[] audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(rateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, rateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioData = new short[bufferSize];

            Log.d(LOG_TAG, "audioRecord.startRecord()");
            audioRecord.startRecording();

            while (!endAtNext) {
                bufferReadResult = audioRecord.read(audioData, 0, audioData.length);
                if (bufferReadResult > 0) {
                    // Changes in this variable may not be picked up despite it being "volatile"
                    try {
                        recorder.recordSamples(ShortBuffer.wrap(audioData, 0, bufferReadResult));
                    } catch (FFmpegFrameRecorder.Exception e) {
                        Log.e(LOG_TAG, e.getMessage());
                        e.printStackTrace();
                    }
                }
            }

            Log.v(LOG_TAG,"AudioThread Finished");

            audioRecord.stop();
            audioRecord.release();

            Log.v(LOG_TAG,"audio record released");
        }

        public void close() {
            this.endAtNext = true;
        }

    }

}
