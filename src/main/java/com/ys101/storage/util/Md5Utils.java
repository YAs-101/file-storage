package com.ys101.storage.util;

import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Md5Utils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Md5Utils() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 MD5", e);
        }
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }

    /**
     * 流式计算 MD5，内存占用与文件大小无关。
     * 这是「1GB 文件校验不能把整个文件读进内存」的具体解法。
     */
    public static String of(InputStream in) {
        MessageDigest md = newDigest();
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.STORAGE_ERROR, "读取流计算 MD5 失败");
        }
        return toHex(md.digest());
    }

    public static String of(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return of(in);
        } catch (IOException e) {
            throw new BizException(ResultCode.STORAGE_ERROR, "读取文件计算 MD5 失败");
        }
    }
}
