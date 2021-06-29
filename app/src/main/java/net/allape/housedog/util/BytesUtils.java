package net.allape.housedog.util;

import androidx.annotation.Nullable;

public final class BytesUtils {

    /**
     * Byte array to hex string
     * @param bytes bytes
     * @return hex string
     */
    public static @Nullable String toHex(@Nullable byte[] bytes) {
        if (bytes == null) {
            return null;
        } else if (bytes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

}
