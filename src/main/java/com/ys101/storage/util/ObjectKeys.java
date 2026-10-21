package com.ys101.storage.util;

/**
 * 对象键生成规则集中在这里，避免各处手写字符串拼错。
 */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    /**
     * md5 前 2 位作一级目录，其余 30 位作文件名。
     * 256 个一级目录足以把单目录文件数摊薄，压测时目录遍历耗时不随文件数线性上升。
     */
    public static String ofMd5(String md5) {
        String m = md5.toLowerCase();
        return m.substring(0, 2) + "/" + m.substring(2);
    }
}
