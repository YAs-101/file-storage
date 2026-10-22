package com.ys101.storage.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 把底层流截断到固定长度。
 *
 * <p>Range 读取时底层是同一个文件句柄，从 offset 开始读，
 * 但必须严格停在 offset+length，否则会把区间之后的字节也写进响应，
 * 客户端拼出来的文件就错了。这个包装类负责那道闸门。
 */
public class LimitedInputStream extends FilterInputStream {

    private long remaining;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = Math.max(limit, 0);
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = super.read();
        if (b >= 0) {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int n = super.read(b, off, (int) Math.min(len, remaining));
        if (n > 0) {
            remaining -= n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(Math.min(n, remaining));
        if (skipped > 0) {
            remaining -= skipped;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), remaining);
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
