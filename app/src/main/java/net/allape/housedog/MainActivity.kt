package net.allape.housedog

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import net.allape.housedog.activity.WatcherActivity
import net.allape.housedog.constant.MQDataSheet
import net.allape.housedog.view.MotionDetectionView
import net.allape.housedog.view.MqRpcView
import net.allape.housedog.view.RtmpPusherView
import org.bytedeco.javacv.Frame
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.IplImage
import pub.devrel.easypermissions.EasyPermissions
import java.nio.ByteBuffer
import kotlin.experimental.and
import kotlin.experimental.or

@RequiresApi(Build.VERSION_CODES.N)
class MainActivity : WatcherActivity(), MqRpcView.MQListener {

    private val _allRequiredPermissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.CAMERA,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_NETWORK_STATE,
    )

    private val _permissionRequestCode = 2021

    private val _logTag = "MainActivity"
    private val _cameraId = "0"
//    private val _cameraId = "1"
    private val _cameraTemplate = CameraDevice.TEMPLATE_RECORD

//    private val _width = 3264
//    private val _height = 2448
    private val _width = 1920
    private val _height = 1080
    private val _frameRate = 10
    private val _scale = 8
    private val _diffThreshold = 150

    private val _mqHost = BuildConfig.MQ_HOST
    private val _mqUsername = BuildConfig.MQ_USERNAME
    private val _mqPassword = BuildConfig.MQ_PASSWORD
    private val _mqQueueName = BuildConfig.MQ_QUEUE_NAME

    private val _rtmpUrl = BuildConfig.RTMP_URL
    private val _rtmpScale = 4
    private val _audioRateInHz = 44100

    // 是否开启自动报警
    private var autoWarning = false

    // 是否开启闪光灯
    private var flashLightOn = false

    // 运动检测
    private var motionDetectionView: MotionDetectionView? = null

    // 自动报警 开启/关闭
    private var autoWarningToggle: Button? = null

    // MQ
    private var mqRpcView: MqRpcView? = null

    // 直播
    private var rtmpPusherView: RtmpPusherView? = null

    // 直播按钮
    private var rtmpPusherButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 检查权限
        val hasPermissions = EasyPermissions.hasPermissions(this, *_allRequiredPermissions)
        if (hasPermissions) {
            init()
        } else {
            requestForPermissions()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == _permissionRequestCode) {
            for (grantResult in grantResults) {
                if (grantResult == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(
                        this,
                        "This APP requires ALL permissions to run properly",
                        Toast.LENGTH_LONG
                    ).show()
                    requestForPermissions()
                    return
                }
            }
        }
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        motionDetectionView!!.destroy()
        mqRpcView!!.close()
        stopStreaming()
    }

    override fun onMessage(message: ByteArray?): ByteArray {
        when (message!![0]) {
            MQDataSheet.Commands.MOTION_DETECTION -> {
                val args = if (message.size == 1) 0 else message[1]
                flashLightOn = args and MQDataSheet.MotionDetection.FLASH_LIGHT == MQDataSheet.MotionDetection.FLASH_LIGHT
                if (args and MQDataSheet.MotionDetection.CAMERA == MQDataSheet.MotionDetection.CAMERA) {
                    motionDetectionView!!.changeCamera(
                        _cameraId,
                        _cameraTemplate,
                        flashLightOn
                    )
                    motionDetectionView!!.openCamera()
                } else {
                    motionDetectionView!!.close()
                }
                if (args and MQDataSheet.MotionDetection.AUTO_WARNING == MQDataSheet.MotionDetection.AUTO_WARNING) {
                    startAutoWarning()
                } else {
                    stopAutoWarning()
                }
            }
            MQDataSheet.Commands.RTMP_VIDEO -> if (message.size == 1 || message[1] == 0.toByte()) {
                stopStreaming()
            } else {
                startStreaming()
            }
            MQDataSheet.Commands.WARNING_SOUND -> if (message.size == 1 || message[1] == 0.toByte()) {
                pauseWarningSound()
            } else {
                outLoud()
                playWarningSound()
            }
            MQDataSheet.Commands.INSPECT_STATUS -> {
                try {
                    // 等待摄像头响应, 避免马上查询的摄像头状态是错的
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                return byteArrayOf(
                    MQDataSheet.InspectStatus.Byte0,
                    ((if (motionDetectionView!!.isOn) MQDataSheet.MotionDetection.CAMERA else 0)
                            or (if (flashLightOn) MQDataSheet.MotionDetection.FLASH_LIGHT else 0)
                            or if (autoWarning) MQDataSheet.MotionDetection.AUTO_WARNING else 0),
                    ((if (rtmpPusherView!!.isRecording) MQDataSheet.InspectStatus.Byte2.RTMP_VIDEO else 0)
                            or if (isWarningSoundPlaying) MQDataSheet.InspectStatus.Byte2.WARNING_SOUND else 0)
                )
            }
        }
        return message
    }

    private fun init() {
        // 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 屏幕尺寸
        val size = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getSize(size)

        val wrapper: LinearLayout = findViewById(R.id.record_layout)

        // region  运动检测
        try {
            motionDetectionView = MotionDetectionView(this) { _, source, _, _, _, moved ->
                if (autoWarning) {
                    if (moved) {
                        playWarningSound()
                    } else {
                        pauseWarningSound()
                    }
                }
                if (rtmpPusherView != null && rtmpPusherView!!.isRecording) {
                    val before = System.currentTimeMillis()
                    val resizedSource: IplImage = IplImage.create(
                        source.width() / _rtmpScale,
                        source.height() / _rtmpScale,
                        source.depth(),
                        source.nChannels()
                    )
                    opencv_imgproc.cvResize(source, resizedSource)
                    val graySource = IplImage.create(
                        resizedSource.width(),
                        resizedSource.height(),
                        opencv_core.IPL_DEPTH_8U,
                        1
                    )
                    opencv_imgproc.cvCvtColor(
                        resizedSource,
                        graySource,
                        opencv_imgproc.CV_RGB2GRAY
                    )
                    val currBuffer = graySource.createBuffer<ByteBuffer>()
                    val currBytes = ByteArray(currBuffer.remaining())
                    currBuffer[currBytes]
                    val frame = Frame(
                        graySource.width(),
                        graySource.height(),
                        Frame.DEPTH_UBYTE,
                        1
                    )
                    (frame.image[0].position(0) as ByteBuffer).put(currBytes)
                    rtmpPusherView!!.push(frame)
                    frame.close()
                    Log.v(
                        _logTag,
                        "push time: " + (System.currentTimeMillis() - before)
                    )
                }
            }
            wrapper.addView(
                motionDetectionView, LinearLayout.LayoutParams(
                    size.x,
                    (size.x.toDouble() / _width * _height).toInt()
                )
            )
            motionDetectionView!!.resize(
                _width,
                _height,
                _frameRate,
                _scale,
                _diffThreshold
            )
            motionDetectionView!!.changeCamera(
                _cameraId,
                _cameraTemplate,
                flashLightOn
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        // 自动报警按钮
        autoWarningToggle = Button(this)
        autoWarningToggle!!.setOnClickListener {
            if (autoWarning) {
                stopAutoWarning()
            } else {
                startAutoWarning()
            }
        }
        stopAutoWarning()
        wrapper.addView(autoWarningToggle)
        // endregion

        // region  MQ
        mqRpcView = MqRpcView(
            this,
            _mqHost, _mqUsername, _mqPassword, _mqQueueName,
            this, true
        )
        wrapper.addView(mqRpcView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // endregion

        // region 直播
        rtmpPusherView = RtmpPusherView(this)
        rtmpPusherView!!.config(_rtmpUrl, _width / _rtmpScale, _height / _rtmpScale, _frameRate, _audioRateInHz)
        wrapper.addView(rtmpPusherView)

        rtmpPusherButton = Button(this)
        rtmpPusherButton!!.setOnClickListener{
            if (rtmpPusherView!!.isRecording) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }
        stopStreaming()
        wrapper.addView(rtmpPusherButton)
        // endregion

        openCamera()
    }

    private fun requestForPermissions() {
//        shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
        requestPermissions(_allRequiredPermissions, _permissionRequestCode)
    }

    private fun openCamera() {
        motionDetectionView!!.openCamera()
    }

    private fun startAutoWarning() {
        autoWarning = true
        autoWarningToggle!!.setText(R.string.AutoWarningOn)
    }

    private fun stopAutoWarning() {
        autoWarning = false
        autoWarningToggle!!.setText(R.string.AutoWarningOff)
        pauseWarningSound()
    }

    private fun startStreaming() {
//        motionDetectionView.setSkip(true);
        rtmpPusherView!!.start()
        rtmpPusherButton!!.setText(R.string.StopStreaming)
    }

    private fun stopStreaming() {
//        motionDetectionView.setSkip(false);
        rtmpPusherView!!.close()
        rtmpPusherButton!!.setText(R.string.StartStreaming)
    }
}