package com.ys101.storage.config;

import com.ys101.storage.storage.FileStorage;
import com.ys101.storage.storage.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class StorageConfig {

    /**
     * 按 storage.type 装配实现。M2 增加 S3 分支时，业务代码一行都不用改——
     * 这是存储抽象存在的唯一理由，也是面试会追问的点。
     */
    @Bean
    public FileStorage fileStorage(StorageProperties props) {
        String type = props.getType() == null ? "local" : props.getType().trim().toLowerCase();
        switch (type) {
            case "local":
                return new LocalFileStorage(props);
            case "s3":
                throw new IllegalStateException("storage.type=s3 将在 M2 实现，当前请使用 local");
            default:
                throw new IllegalStateException("未知 storage.type: " + props.getType());
        }
    }
}
