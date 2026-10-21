package com.ys101.storage.storage;

import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;
import com.ys101.storage.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 本地磁盘实现。
 *
 * <p>目录结构：
 * <pre>
 * {tempPath}/{uploadId}/{chunkIndex}.part     临时分片
 * {basePath}/{md5 前2位}/{md5 其余30位}        最终对象
 * </pre>
 * 两级前缀是为了避免所有对象挤在同一目录下——单目录文件数过万后，
 * 目录项查找会明显变慢，这在压测时能看出来。
 */
@Slf4j
public class LocalFileStorage implements FileStorage {

    public static final String TYPE = "LOCAL";

    private final Path dataRoot;
    private final Path tempRoot;

    public LocalFileStorage(StorageProperties props) {
        this.dataRoot = Paths.get(props.getLocal().getBasePath()).toAbsolutePath().normalize();
        this.tempRoot = Paths.get(props.getLocal().getTempPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataRoot);
            Files.createDirectories(tempRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建存储目录: " + dataRoot + " / " + tempRoot, e);
        }
        log.info("LocalFileStorage 就绪 dataRoot={} tempRoot={}", dataRoot, tempRoot);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void saveChunk(String uploadId, int chunkIndex, InputStream in) {
        Path target = chunkPath(uploadId, chunkIndex);
        try {
            Files.createDirectories(target.getParent());
            // REPLACE_EXISTING 保证重复上传同一分片是覆盖而不是追加
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException(ResultCode.STORAGE_ERROR, "写入分片失败: " + e.getMessage());
        }
    }

    @Override
    public boolean chunkExists(String uploadId, int chunkIndex) {
        return Files.isRegularFile(chunkPath(uploadId, chunkIndex));
    }

    @Override
    public long chunkSize(String uploadId, int chunkIndex) {
        Path p = chunkPath(uploadId, chunkIndex);
        try {
            return Files.isRegularFile(p) ? Files.size(p) : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    @Override
    public long mergeChunks(String uploadId, List<Integer> indexes, String objectKey) {
        Path out = objectPath(objectKey);
        try {
            Files.createDirectories(out.getParent());
            try (FileChannel outCh = FileChannel.open(out,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Integer idx : indexes) {
                    Path part = chunkPath(uploadId, idx);
                    if (!Files.isRegularFile(part)) {
                        throw new BizException(ResultCode.CHUNK_INCOMPLETE, "分片缺失: " + idx);
                    }
                    try (FileChannel in = FileChannel.open(part, StandardOpenOption.READ)) {
                        long size = in.size();
                        long pos = 0;
                        while (pos < size) {
                            pos += in.transferTo(pos, size - pos, outCh);
                        }
                    }
                }
                return outCh.size();
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(ResultCode.STORAGE_ERROR, "合并分片失败: " + e.getMessage());
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(objectPath(objectKey));
        } catch (IOException e) {
            throw new BizException(ResultCode.NOT_FOUND, "对象不存在");
        }
    }

    @Override
    public long objectSize(String objectKey) {
        try {
            Path p = objectPath(objectKey);
            return Files.isRegularFile(p) ? Files.size(p) : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        return Files.isRegularFile(objectPath(objectKey));
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            Files.deleteIfExists(objectPath(objectKey));
        } catch (IOException e) {
            log.warn("删除对象失败 key={} err={}", objectKey, e.getMessage());
        }
    }

    @Override
    public void deleteChunks(String uploadId) {
        Path dir = tempRoot.resolve(uploadId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 临时文件删不掉不影响业务，留给下一次清理
                        }
                    });
        } catch (IOException e) {
            log.warn("清理临时分片失败 uploadId={} err={}", uploadId, e.getMessage());
        }
    }

    private Path chunkPath(String uploadId, int chunkIndex) {
        return tempRoot.resolve(uploadId).resolve(chunkIndex + ".part");
    }

    private Path objectPath(String objectKey) {
        Path p = dataRoot.resolve(objectKey).normalize();
        // 防目录穿越：objectKey 是服务端生成的，但这里仍然校验一次
        if (!p.startsWith(dataRoot)) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法 objectKey");
        }
        return p;
    }
}
