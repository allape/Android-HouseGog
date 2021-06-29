package net.allape.housedog.constant;

/**
 * MQ消息通信
 */
public final class MQDataSheet {

    // 大端 byte
    // bytes[0]: 指令{@link Commands}
    // bytes[1:]: 指令参数

    /**
     * 第一个byte为指令
     */
    public static final class Commands {

        /**
         * 运动检测 {@link MotionDetection} >> 原样返回
         */
        public static final byte MOTION_DETECTION = 1;

        /**
         * 直播推流
         * bytes[1]: undefined或0x0/关闭直播, 其他/开启直播
         * >> 原样返回
         */
        public static final byte RTMP_VIDEO = 2;

        /**
         * 警报声音
         * bytes[1]: undefined或0x0/关闭报警声音, 其他/开始播放报警声音
         * >> 原样返回
         */
        public static final byte WARNING_SOUND = 3;

        /**
         * 状态查询 >>
         */
        public static final byte INSPECT_STATUS = 4;

    }

    /**
     * 运动检测参数
     */
    public static final class MotionDetection {

        /**
         * 是否开启摄像头
         */
        public static final byte CAMERA =           1;

        /**
         * 是否开启闪光灯
         */
        public static final byte FLASH_LIGHT =      1 << 1;

        /**
         * 是否开启自动报警
         */
        public static final byte AUTO_WARNING =     1 << 2;

    }

    /**
     * 状态查询返回值
     */
    public static final class InspectStatus {

        public static final byte Byte0 = Commands.INSPECT_STATUS;

        /**
         * {@link MotionDetection}
         * @deprecated
         */
        public static final byte Byte1 = MotionDetection.CAMERA | MotionDetection.FLASH_LIGHT | MotionDetection.AUTO_WARNING;

        public static final class Byte2 {

            /**
             * 是否在直播
             */
            public static final byte RTMP_VIDEO =           1;

            /**
             * 是否在播放警报声音
             */
            public static final byte WARNING_SOUND =        1 << 1;

        }

    }

}
