package com.ys101.storage.service;

import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;
import com.ys101.storage.config.StorageProperties;
import com.ys101.storage.entity.UploadTask;
import com.ys101.storage.mapper.UploadChunkMapper;
import com.ys101.storage.storage.FileStorage;
import com.ys101.storage.storage.MergeResult;
import com.ys101.storage.util.Md5Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合并产物校验。按后端能力分策略，而不是只认一种做法。
 *
 * <p>问题的由来：M1 的校验是「重读整文件算 MD5」，那时只有本地磁盘，读的是盘。
 * M2 换了 S3，同一行代码变成把整个对象从远端拉回来——1 GB 文件多一次全量出网，
 * 真实环境还计流量费。校验本身没变，成本变了 10 倍以上。
 *
 * <p>于是分两条路，由 {@link StorageProperties.VerifyMode} 决定，默认 AUTO：
 *
 * <ol>
 *   <li><b>有产物指纹的后端（S3）</b>：用 ETag 校验，零数据传输。S3 多段上传的 ETag
 *       是「各 part 的二进制 MD5 串接后再算 MD5，末尾加 {@code -N}」。part 由
 *       {@code UploadPartCopy} 从分片对象复制而来，所以 part 的 MD5 就是分片落盘时
 *       服务端自己算出的 {@code upload_chunk.chunk_md5}。本地把这串值按序号拼起来
 *       生算一次，和存储返回的 ETag 比——一致就说明「拼出来的对象确实由这些分片、
 *       按这个顺序组成」，没有缺片、错序、替换。</li>
 *   <li><b>给不出指纹的后端（本地磁盘）</b>：退回全量读回算 MD5，与声明值比。</li>
 * </ol>
 *
 * <p><b>两条路不等价，这一点必须说清。</b>指纹路线证明的是「拼装正确」，
 * 证明不了「整文件 MD5 等于客户端声明的值」——MD5 不可由分片 MD5 复合，
 * 后者只能靠全量读回确认。单段对象（小于一个分片）例外：单段 copy 的 ETag
 * 就是内容 MD5，此时两条路等价。需要完整语义时用 {@code ALWAYS} 强制全量读回。
 */
@Slf4j
@Component
public class MergeVerifier {

    /** 多段 ETag：32 位十六进制 + "-" + part 数 */
    private static final Pattern MULTIPART_ETAG = Pattern.compile("([0-9a-fA-F]{32})-(\\d+)");

    /** 单段 ETag：就是内容 MD5 本身 */
    private static final Pattern SINGLE_ETAG = Pattern.compile("[0-9a-fA-F]{32}");

    private final StorageProperties props;
    private final FileStorage storage;
    private final UploadChunkMapper chunkMapper;

    public MergeVerifier(StorageProperties props, FileStorage storage, UploadChunkMapper chunkMapper) {
        this.props = props;
        this.storage = storage;
        this.chunkMapper = chunkMapper;
    }

    /**
     * @param ok       校验是否通过；false 时调用方要删产物并回滚会话
     * @param md5      可以落库的 MD5。走指纹路线时它是客户端声明值本身
     * @param evidence 用了哪种证据，写进完成日志，事后排查不用猜
     * @param detail   失败原因，通过时为 null
     */
    public record Verification(boolean ok, String md5, String evidence, String detail) {
    }

    public Verification verify(UploadTask task, String objectKey, MergeResult merged) {
        String declared = declaredMd5(task);

        // 大小是最便宜的校验，任何模式都先做：ETag 只覆盖「分片是否原样拼起来」，
        // 覆盖不了「最后一片是不是少传了一截」这类截断
        if (merged.size() != task.getFileSize()) {
            return new Verification(false, declared, "大小比对",
                    "合并产物 " + merged.size() + " 字节，与声明 " + task.getFileSize() + " 不一致");
        }

        StorageProperties.VerifyMode mode = props.getVerifyMergedMd5() == null
                ? StorageProperties.VerifyMode.AUTO
                : props.getVerifyMergedMd5();

        if (mode == StorageProperties.VerifyMode.NEVER) {
            // 只用于压测对照：M1 的 L7-B 就靠它测出「重算占合并耗时 47%」
            return new Verification(true, declared, "未校验(never)", null);
        }
        if (mode == StorageProperties.VerifyMode.ALWAYS) {
            return byFullRead(declared, objectKey);
        }

        // AUTO：优先用后端给的指纹
        String etag = normalizeEtag(merged.etag());
        String expected = etag == null ? null : expectedEtag(task, etag);
        if (expected == null) {
            log.debug("后端未提供可核对的产物指纹 objectKey={} etag={}，退回全量读回", objectKey, etag);
            return byFullRead(declared, objectKey);
        }
        if (expected.equalsIgnoreCase(etag)) {
            return new Verification(true, declared, "ETag(" + etag + ")", null);
        }
        // 对不上有两种可能：真的拼坏了，或者这个后端的 ETag 压根不是内容指纹
        // （SSE-KMS 加密桶、部分私有实现都会这样）。只在异常路径付一次全量读来裁决，
        // 既不误杀合法后端，也不放过真的损坏
        log.warn("ETag 与本地推算不一致 objectKey={} etag={} expected={}，改用全量读回裁决",
                objectKey, etag, expected);
        return byFullRead(declared, objectKey);
    }

    private Verification byFullRead(String declared, String objectKey) {
        String actual;
        try (InputStream in = storage.open(objectKey)) {
            actual = Md5Utils.of(in);
        } catch (IOException e) {
            throw new BizException(ResultCode.STORAGE_ERROR, "校验合并结果失败: " + e.getMessage());
        }
        if (!actual.equalsIgnoreCase(declared)) {
            return new Verification(false, actual, "全量读回",
                    "合并后 MD5=" + actual + " 与声明 " + declared + " 不一致");
        }
        return new Verification(true, actual, "全量读回", null);
    }

    /**
     * 由本地信息推算存储应当返回的 ETag。推不出来返回 null，调用方据此退回全量读回。
     */
    private String expectedEtag(UploadTask task, String etag) {
        Matcher multi = MULTIPART_ETAG.matcher(etag);
        if (multi.matches()) {
            int partCount = Integer.parseInt(multi.group(2));
            List<String> chunkMd5s = chunkMapper.selectChunkMd5s(task.getUploadId());
            // 分片记录数和 part 数对不上，说明推算依据不完整（比如记录被清过），
            // 不能拿它当判据
            if (chunkMd5s.size() != partCount) {
                return null;
            }
            MessageDigest md = Md5Utils.newDigest();
            for (String hex : chunkMd5s) {
                if (hex == null || hex.isBlank()) {
                    return null;
                }
                // 必须转回二进制再拼：S3 是对 part 的 MD5 原始字节串接后再生算
                md.update(Md5Utils.fromHex(hex.trim()));
            }
            return Md5Utils.toHex(md.digest()) + "-" + partCount;
        }
        if (SINGLE_ETAG.matcher(etag).matches()) {
            // 单段对象：ETag 即内容 MD5，期望值就是声明的文件 MD5
            return declaredMd5(task);
        }
        return null;
    }

    private static String declaredMd5(UploadTask task) {
        String md5 = task.getFileMd5();
        return md5 == null ? "" : md5.trim().toLowerCase(Locale.ROOT);
    }

    /** SDK 一般已去掉引号，但不同实现回来的格式不一定，统一剥掉 */
    private static String normalizeEtag(String etag) {
        if (etag == null) {
            return null;
        }
        String v = etag.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }
}
