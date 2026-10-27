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

    public S3FileStorage(StorageProperties props) {
        StorageProperties.S3 cfg = props.getS3();
        this.bucket = cfg.getBucket();
        this.chunkPrefix = trimSlashes(cfg.getChunkPrefix());
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
    public long saveChunk(String uploadId, int chunkIndex, InputStream in) {
        // 为什么先落本地临时文件：PutObject 必须带 Content-Length。multipart 解析出来的
        // 流既没有 available() 的准确值，也不支持 mark/reset 回退去数一遍。猜长度两种下场：
        // 猜大了 S3 会一直等剩下的字节直到超时，猜小了直接截断。
        // 落临时文件得到准确长度，内存占用仍是常量（IOUtils 的 8 KB 拷贝缓冲），
        // 代价是每片多一次本地写——这是「不把 5 MB 分片读进堆」的必然选择。
        String key = chunkKey(uploadId, chunkIndex);
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
        try {
            List<CompletedPart> parts = new ArrayList<>(indexes.size());
            for (int i = 0; i < indexes.size(); i++) {
                // partNumber 从 1 开始且必须连续，跟分片序号不是同一个东西：
                // 分片序号由客户端决定，partNumber 由 S3 要求
                final int partNumber = i + 1;
                int idx = indexes.get(i);
                String src = chunkKey(uploadId, idx);
                requireChunk(src, idx);
                // UploadPartCopy：数据在存储内部搬运，不经过本进程。
                // 这是 S3 模式下合并开销消失的根本原因——本地实现要把整文件读写一遍
                UploadPartCopyResponse copied = s3.uploadPartCopy(b -> b
                        .bucket(bucket).key(objectKey)
                        .uploadId(s3UploadId)
                        .partNumber(partNumber)
                        .copySource(bucket + "/" + src));
                parts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(copied.copyPartResult().eTag())
                        .build());
            }
            // parts 必须按 partNumber 升序，否则 Complete 直接报 InvalidPartOrder
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(objectKey)
                    .uploadId(s3UploadId)
                    .multipartUpload(m -> m.parts(parts)));
            return objectSize(objectKey);
        } catch (RuntimeException e) {
            // 失败必须 abort：未完成的 multipart 在 S3 上不可见但占存储、会计费，
            // 而且没有 abort 就永远清不掉（生命周期规则是另一回事）
            abortQuietly(objectKey, s3UploadId);
            throw e;
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
}
