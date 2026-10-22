package com.ys101.storage.util;

/**
 * 单区间 Range 头解析结果。
 *
 * <p>为什么只做单区间：多区间响应要求 body 用 multipart/byteranges 拼接，
 * 客户端（浏览器、curl、IDM）几乎都只用单区间，收益为零而实现复杂度不低。
 * RFC 7233 明确允许服务端忽略 Range 直接返回全量，所以遇到多区间就退化为全量。
 */
public record ByteRange(long start, long end) {

    /** 区间长度，end 是闭区间，所以 +1 */
    public long length() {
        return end - start + 1;
    }

    /**
     * 解析 Range 请求头。
     *
     * @param header 原始 Range 头，可为 null
     * @param total  资源总字节数
     * @return null 表示应当返回完整内容（无 Range、格式不认识、或为多区间）
     * @throws Unsatisfiable 区间格式合法但超出资源范围，调用方应返回 416
     */
    public static ByteRange parse(String header, long total) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String spec = h.substring(6).trim();
        if (spec.contains(",")) {
            return null;
        }

        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();

        long start;
        long end;
        if (startStr.isEmpty()) {
            // bytes=-500 表示取末尾 500 字节。这是最容易被漏写的一种形式，
            // 断点续传的客户端在「续传最后一段」时就会用到
            long suffix = parseLong(endStr);
            if (suffix <= 0) {
                return null;
            }
            start = Math.max(0, total - suffix);
            end = total - 1;
        } else {
            start = parseLong(startStr);
            if (start < 0) {
                return null;
            }
            end = endStr.isEmpty() ? total - 1 : parseLong(endStr);
            if (end < 0) {
                return null;
            }
            // 末端越界按 RFC 7233 钳到资源末尾，而不是直接报错：
            // 客户端多要几个字节是常态，拒绝会让续传逻辑变复杂
            end = Math.min(end, total - 1);
        }

        if (total <= 0 || start >= total || start > end) {
            throw new Unsatisfiable();
        }
        return new ByteRange(start, end);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 区间不可满足。不采集堆栈——它是正常的协议分支，不是异常路径 */
    public static final class Unsatisfiable extends RuntimeException {
        public Unsatisfiable() {
            super("range not satisfiable", null, false, false);
        }
    }
}
