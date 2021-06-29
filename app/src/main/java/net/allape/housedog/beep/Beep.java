package net.allape.housedog.beep;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class Beep implements Runnable {

    private static final int SAMPLE_RATE_IN_HZ = 44100;

    // 频率
    private final int frequency;

    // 是否在下一个周期退出
    private boolean closing = false;
    // 是否暂停
    private boolean paused = true;

    public Beep(int frequency) {
        this.frequency = frequency;
    }

    @Override
    public void run() {
        short[] buffer = new short[1024];
        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE_IN_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer.length,
                AudioTrack.MODE_STREAM
        );
        double increment = 2 * Math.PI * frequency / SAMPLE_RATE_IN_HZ;
        double angle = 0;
        double[] samples = new double[1024];

        track.play();

        while (!closing) {
            if (!paused) {
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = Math.sin(angle);
                    buffer[i] = (short) (samples[i] * Short.MAX_VALUE);
//                    angle = (angle + increment) % 3600;
                    angle = angle + increment;
                }
                track.write(buffer, 0, samples.length);
            } else {
                synchronized (this) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        track.release();
    }

    /**
     * 关闭并退出
     */
    public void close() {
        this.closing = true;
    }

    /**
     * 暂停播放
     */
    public void pause() {
        this.paused = true;
    }

    /**
     * 继续/开始播放
     */
    public void play() {
        this.paused = false;
        synchronized (this) {
            this.notify();
        }
    }

    /**
     * 是否在播放
     */
    public boolean isPlaying() {
        return !paused;
    }

}
