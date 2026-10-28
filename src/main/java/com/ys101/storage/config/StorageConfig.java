package com.ys101.storage.config;

import com.ys101.storage.common.BizException;
import com.ys101.storage.storage.FileStorage;
import com.ys101.storage.storage.LocalFileStorage;
import com.ys101.storage.storage.S3FileStorage;
import com.ys101.storage.storage.StorageRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 装配存储后端。
 *
 * <p>M2 之前这里直接产出一个 {@link FileStorage}：{@code storage.type} 写哪个就装哪个。
 * 那套做法在「换实现」的语义上没问题，但切过去之后老数据就再也读不回来了——数据是
 * 持久的，配置是易变的。现在改成产出一个 {@link StorageRegistry}：两个实现都注册进去，
 * 谁被创建由「实际要读的数据在哪个后端」决定。
 *
 * <p>注册不等于创建。S3 的客户端要到第一次访问 S3 数据时才会建立，否则本地开发
 * 只跑 local 模式也得先把对象存储起来，否则服务起不来。
 */
@Slf4j
@Configuration
public class StorageConfig {

    @Bean
    public StorageRegistry storageRegistry(StorageProperties props) {
        Map<String, Supplier<FileStorage>> factories = new LinkedHashMap<>();
        factories.put(LocalFileStorage.TYPE, () -> new LocalFileStorage(props));
        factories.put(S3FileStorage.TYPE, () -> new S3FileStorage(props));

        String configured = props.getType() == null ? "local" : props.getType().trim();
        log.info("存储后端注册 default={} 可选={}", configured, factories.keySet());

        try {
            return new StorageRegistry(configured, factories);
        } catch (BizException e) {
            // 默认后端装不上就不该起服务：起来之后每个上传都会失败，而且错误散落在业务日志里
            throw new IllegalStateException("默认存储后端装配失败（storage.type=" + configured
                    + "）：" + e.getMessage(), e);
        }
    }
}
