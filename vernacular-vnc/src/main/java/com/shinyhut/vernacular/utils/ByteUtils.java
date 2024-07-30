package com.shinyhut.vernacular.utils;

import static java.lang.System.arraycopy;
import java.math.BigInteger;

public class ByteUtils {

    private ByteUtils() {
    }

    public static byte[] reverseBits(byte[] b) {
        byte[] result = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            result[i] = reverseBits(b[i]);
        }
        return result;
    }

    public static byte reverseBits(byte input) {
        byte result = 0x00;
        for (int i = 0; i < 8; i++) {
            result |= ((byte) ((input & (0x01 << i)) >>> i) << 7 - i);
        }
        return result;
    }

    public static boolean mask(int input, int mask) {
        return (input & mask) != 0;
    }

    public static boolean bitAt(int input, int position) {
        return (input & (0x01 << position)) != 0;
    }

    public static byte[] padLeft(byte[] input, int length) {
        byte[] padded = new byte[length];
        arraycopy(input, 0, padded, length - input.length, input.length);
        return padded;
    }

    public static byte[] padRight(byte[] input, int length) {
        byte[] padded = new byte[length];
        arraycopy(input, 0, padded, 0, input.length);
        return padded;
    }

    public static byte[] bigIntToBytes(BigInteger n, int bytes, boolean reverse) {
        var b = n.toByteArray();
        var l = Math.min(b.length, bytes);
        var res = new byte[bytes];
        System.arraycopy(b, b.length - l, res, bytes - l, l);
        if (reverse) {
            for(int i = 0; i < res.length / 2; i++) {
                byte temp = res[i];
                res[i] = res[res.length - i - 1];
                res[res.length - i - 1] = temp;
            }
        }
        return res;
    }
}
