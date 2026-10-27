package com.ys101.storage.storage;

import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;
import com.ys101.storage.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * S3 兼容对象存储实现。
 *
 * <p>对象布局：
 * <pre>
 * {chunkPrefix}/{uploadId}/{chunkIndex}.part   临时分片
 * {objectKey}                                  最终对象（md5 前2位/其余30位，与本地实现一致）
 * </pre>
 *
 * <p>与本地实现最大的一处差别在 {@link #mergeChunks}：本地要把 N 个分片读一遍写一遍，
 * 1 GB 文件光搬运就吃掉 82% 的墙钟时间；这里用 UploadPartCopy 让数据在存储内部走，
 * 本进程只发请求、不碰字节。这也意味着 M1 的压测数字不能直接套到 S3 模式上。
 *
 * <p>分片对象和最终对象放在同一个桶里，靠前缀区分。不用多个桶：一个桶一条权限策略，
 * 多桶会让部署时的授权配置翻倍，而前缀隔离已经够用。
 */
@Slf4j
public class S3FileStorage implements FileStorage {

    public static final String TYPE = "S3";

    private final S3Client s3;
    private final String bucket;
    private final String chunkPrefix;
    private final Path tempRoot;
    private final int copyThreads;

    public S3FileStorage(StorageProperties props) {
        StorageProperties.S3 cfg = props.getS3();
        this.bucket = cfg.getBucket();
        this.chunkPrefix = trimSlashes(cfg.getChunkPrefix());
        this.copyThreads = Math.max(1, cfg.getMergeCopyThreads());
        // 本地临时目录在 S3 模式下仍然有用：PutObject 需要 Content-Length，
        // 而 multipart 交给我们的流不暴露长度，只能先落地拿准确大小。
        this.tempRoot = Paths.get(props.getLocal().getTempPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(tempRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建临时目录: " + tempRoot, e);
        }

        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(cfg.getEndpoint()))
                .region(Region.of(cfg.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                // 显式指定 HTTP 客户端：pom 里排掉了 netty-nio-client，不指定的话
                // SDK 找不到默认实现会在启动期抛 SdkClientException
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                // MinIO / S3Mock / Ceph 只认 path-style，接 AWS 官方桶再改 false
                .forcePathStyle(cfg.isPathStyle())
                // 默认 30 秒读超时对大文件下载偏紧：限速客户端拉 1 GB 会超时断开。
                // 单次尝试给 2 分钟，含重试的整体上限 5 分钟
                .overrideConfiguration(o -> o
                        .apiCallAttemptTimeout(Duration.ofMinutes(2))
                        .apiCallTimeout(Duration.ofMinutes(5)))
                .build();

        ensureBucket();
        log.info("S3FileStorage 就绪 endpoint={} bucket={} pathStyle={} chunkPrefix={}",
                cfg.getEndpoint(), bucket, cfg.isPathStyle(), chunkPrefix);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public long saveChunk(String uploadId, int chunkIndex, InputStream in, long contentLength) {
        String key = chunkKey(uploadId, chunkIndex);
        // 快路径：分片接口传的是原始二进制（application/octet-stream），不是 multipart，
        // 所以 Content-Length 就是分片真实大小。拿到它就能边读边发，一个字节都不落本地。
        // 实测这一步省掉的是「每片多一次本地写 + 一次本地读」，256MB 上传从 2.7s 回到 0.4s
        if (contentLength >= 0) {
            CountingInputStream counting = new CountingInputStream(in);
            try {
                s3.putObject(b -> b.bucket(bucket).key(key).contentLength(contentLength),
                        RequestBody.fromInputStream(counting, contentLength));
                return counting.count();
            } catch (S3Exception e) {
                throw new BizException(ResultCode.STORAGE_ERROR,
                        "上传分片到对象存储失败: " + e.getMessage());
            }
        }
        return spoolThenPut(key, uploadId, chunkIndex, in);
    }

    /**
     * 慢路径：拿不到 Content-Length 时（客户端用 chunked 传输）先落本地临时文件。
     *
     * <p>为什么必须落盘：PutObject 不接受未知长度。猜大了 S3 会一直等剩下的字节直到超时，
     * 猜小了直接截断。落临时文件是最省内存的拿长度方式，代价是每片多一次本地往返。
     */
    private long spoolThenPut(String key, String uploadId, int chunkIndex, InputStream in) {
        Path tmp = tempRoot.resolve("chunk-" + uploadId + "-" + chunkIndex + "-"
                + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(tmp.getParent());
            long written = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            // 同序号重传直接覆盖同名 key，PutObject 的覆盖语义保证分片级幂等
            s3.putObject(b -> b.bucket(bucket).key(key).contentLength(written),
                    RequestBody.fromFile(tmp));
            return written;
        } catch (IOException e) {
            throw new BizException(ResultCode.STORAGE_ERROR, "写入分片失败: " + e.getMessage());
        } catch (S3Exception e) {
            throw new BizException(ResultCode.STORAGE_ERROR,
                    "上传分片到对象存储失败: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件残留不影响正确性，留给系统清理
            }
        }
    }

    @Override
    public boolean chunkExists(String uploadId, int chunkIndex) {
        return headSize(chunkKey(uploadId, chunkIndex)) >= 0;
    }

    @Override
    public long chunkSize(String uploadId, int chunkIndex) {
        return headSize(chunkKey(uploadId, chunkIndex));
    }

    @Override
    public long mergeChunks(String uploadId, List<Integer> indexes, String objectKey) {
        if (indexes == null || indexes.isEmpty()) {
            throw new BizException(ResultCode.CHUNK_INCOMPLETE, "分片列表为空");
        }

        // 单片文件（小于 5 MB）直接服务端 copy。S3 允许只有一个 part 的 multipart，
        // 但为搬一个对象付 Create + UploadPartCopy + Complete 三次往返不值得
        if (indexes.size() == 1) {
            String src = chunkKey(uploadId, indexes.get(0));
            requireChunk(src, indexes.get(0));
            try {
                s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(src)
                        .destinationBucket(bucket).destinationKey(objectKey));
            } catch (S3Exception e) {
                throw new BizException(ResultCode.STORAGE_ERROR,
                        "合并分片失败: " + e.getMessage());
            }
            return objectSize(objectKey);
        }

        CreateMultipartUploadResponse created = s3.createMultipartUpload(
                b -> b.bucket(bucket).key(objectKey));
        String s3UploadId = created.uploadId();

        // partNumber 从 1 开始且必须连续，跟分片序号不是同一个东西：
        // 分片序号由客户端决定，partNumber 由 S3 要求
        int threads = Math.max(1, Math.min(copyThreads, indexes.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "s3-merge-copy");
            // 守护线程：合并被中断时不拖住 JVM 退出
            t.setDaemon(true);
            return t;
        });
        try {
            long tBegin = System.currentTimeMillis();
            // 并发发起 UploadPartCopy。串行发 52 个 5MB 分片实测要 9.5 秒，
            // 单次请求开销约 180ms，瓶颈在请求往返而不在数据量，所以并发能线性收敛。
            // futures 按提交顺序收集，取回来天然就是 partNumber 升序，
            // Complete 不会因为乱序报 InvalidPartOrder。
            List<Future<CompletedPart>> futures = new ArrayList<>(indexes.size());
            for (int i = 0; i < indexes.size(); i++) {
                final int partNumber = i + 1;
                final int idx = indexes.get(i);
                futures.add(pool.submit(() -> copyPart(objectKey, s3UploadId, uploadId, idx, partNumber)));
            }
            List<CompletedPart> parts = new ArrayList<>(indexes.size());
            for (Future<CompletedPart> f : futures) {
                parts.add(f.get());
            }
            long tCopied = System.currentTimeMillis();
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(objectKey)
                    .uploadId(s3UploadId)
                    .multipartUpload(m -> m.parts(parts)));
            long tCompleted = System.currentTimeMillis();
            // copy 与 complete 分开计时：copy 是并发请求数的问题，complete 是
            // 存储端拼接整对象的问题，混在一起看不出该调哪个参数
            log.info("S3 合并完成 key={} parts={} threads={} copy={}ms complete={}ms",
                    objectKey, indexes.size(), threads, tCopied - tBegin, tCompleted - tCopied);
            return objectSize(objectKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abortQuietly(objectKey, s3UploadId);
            throw new BizException(ResultCode.STORAGE_ERROR, "合并被中断");
        } catch (ExecutionException e) {
            // 失败必须 abort：未完成的 multipart 在 S3 上不可见但占存储、会计费，
            // 而且没有 abort 就永远清不掉（生命周期规则是另一回事）
            abortQuietly(objectKey, s3UploadId);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BizException(ResultCode.STORAGE_ERROR, "合并分片失败: " + cause);
        } catch (RuntimeException e) {
            abortQuietly(objectKey, s3UploadId);
            throw e;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 把单个分片对象复制成 multipart 的一个 part。
     *
     * <p>这里不再单独 HEAD 一次确认分片存在：uploadPartCopy 对缺失源本身会返回 404，
     * 多打一次请求只是把「每片两次往返」变成「一次」。404 翻译成 CHUNK_INCOMPLETE，
     * 与本地实现的「分片缺失」保持同一个错误码。
     */
    private CompletedPart copyPart(String objectKey, String s3UploadId,
                                   String uploadId, int chunkIndex, int partNumber) {
        String src = chunkKey(uploadId, chunkIndex);
        try {
            UploadPartCopyResponse copied = s3.uploadPartCopy(b -> b
                    .bucket(bucket).key(objectKey)
                    .uploadId(s3UploadId)
                    .partNumber(partNumber)
                    .copySource(bucket + "/" + src));
            return CompletedPart.builder()
                    .partNumber(partNumber)
                    .eTag(copied.copyPartResult().eTag())
                    .build();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BizException(ResultCode.CHUNK_INCOMPLETE, "分片缺失: " + chunkIndex);
            }
            throw new BizException(ResultCode.STORAGE_ERROR,
                    "复制分片 " + chunkIndex + " 失败: " + e.getMessage());
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return s3.getObject(b -> b.bucket(bucket).key(objectKey));
        } catch (S3Exception e) {
            throw translateReadError(e, "读取对象失败");
        }
    }

    @Override
    public InputStream openRange(String objectKey, long offset, long length) {
        if (length <= 0) {
            return InputStream.nullInputStream();
        }
        // bytes=start-end 是闭区间，所以 end 要减 1。
        // end 超过对象大小时 S3 自动截到末尾，与本地 LimitedInputStream 的行为一致。
        String range = "bytes=" + offset + "-" + (offset + length - 1);
        try {
            return s3.getObject(b -> b.bucket(bucket).key(objectKey).range(range));
        } catch (S3Exception e) {
            throw translateReadError(e, "读取对象区间失败");
        }
    }

    @Override
    public long objectSize(String objectKey) {
        return headSize(objectKey);
    }

    @Override
    public boolean objectExists(String objectKey) {
        return headSize(objectKey) >= 0;
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            // DeleteObject 本身是幂等的：对象不存在也返回 204
            s3.deleteObject(b -> b.bucket(bucket).key(objectKey));
        } catch (S3Exception e) {
            // 调用点在 afterCommit 里，失败已无法回滚，只能留痕
            log.warn("删除对象失败 key={} status={} err={}",
                    objectKey, e.statusCode(), e.getMessage());
        }
    }

    @Override
    public void deleteChunks(String uploadId) {
        String prefix = chunkPrefix + "/" + uploadId + "/";
        try {
            String continuationToken = null;
            int deleted = 0;
            while (true) {
                final String token = continuationToken;
                ListObjectsV2Response listed = s3.listObjectsV2(b -> {
                    b.bucket(bucket).prefix(prefix);
                    if (token != null) {
                        b.continuationToken(token);
                    }
                });
                List<S3Object> objects = listed.contents();
                if (objects.isEmpty()) {
                    break;
                }
                // DeleteObjects 一次最多 1000 个，1000 片以上的会话要分页删
                List<ObjectIdentifier> ids = objects.stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                s3.deleteObjects(b -> b.bucket(bucket).delete(Delete.builder().objects(ids).build()));
                deleted += ids.size();
                if (!Boolean.TRUE.equals(listed.isTruncated())) {
                    break;
                }
                continuationToken = listed.nextContinuationToken();
            }
            if (deleted > 0) {
                log.debug("清理临时分片 uploadId={} 删除 {} 个", uploadId, deleted);
            }
        } catch (S3Exception e) {
            // 临时分片是冗余数据，删不掉不影响业务正确性，留给生命周期规则兜底
            log.warn("清理临时分片失败 uploadId={} err={}", uploadId, e.getMessage());
        }
    }

    private void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("桶不存在，已创建 bucket={}", bucket);
                return;
            }
            throw new IllegalStateException(
                    "S3 桶不可用 bucket=" + bucket + " status=" + e.statusCode(), e);
        }
    }

    /** 对象或分片不存在时返回 -1，把「不存在」和「查询失败」区分开 */
    private long headSize(String key) {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(key)).contentLength();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return -1L;
            }
            throw new BizException(ResultCode.STORAGE_ERROR,
                    "查询对象失败: " + e.getMessage());
        }
    }

    private void requireChunk(String key, int chunkIndex) {
        if (headSize(key) < 0) {
            throw new BizException(ResultCode.CHUNK_INCOMPLETE, "分片缺失: " + chunkIndex);
        }
    }

    /**
     * 把存储层异常翻译成业务异常。对象丢失必须是 NOT_FOUND：
     * 下载链路靠这个码把「数据库说有、存储里没有」暴露成 404 而不是 500。
     */
    private BizException translateReadError(S3Exception e, String fallback) {
        if (e.statusCode() == 404) {
            return new BizException(ResultCode.NOT_FOUND, "对象不存在");
        }
        return new BizException(ResultCode.STORAGE_ERROR, fallback + ": " + e.getMessage());
    }

    private void abortQuietly(String objectKey, String s3UploadId) {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(objectKey).uploadId(s3UploadId));
        } catch (S3Exception e) {
            log.warn("abort multipart 失败 key={} uploadId={} err={}",
                    objectKey, s3UploadId, e.getMessage());
        }
    }

    private String chunkKey(String uploadId, int chunkIndex) {
        return chunkPrefix + "/" + uploadId + "/" + chunkIndex + ".part";
    }

    private static String trimSlashes(String s) {
        if (s == null || s.isBlank()) {
            return "chunks";
        }
        String v = s.trim();
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /**
     * 统计实际读出的字节数。
     *
     * <p>流式 PUT 时这个数就是真实写入量：调用方拿它和期望分片大小比对，
     * 才能发现「客户端声明的 Content-Length 与实际发的字节数不一致」这种情况。
     */
    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        long count() {
            return count;
        }
    }
}
