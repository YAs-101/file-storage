package com.ys101.storage.config;

import com.ys101.storage.mapper.FileInfoMapper;
import com.ys101.storage.mapper.UploadTaskMapper;
import com.ys101.storage.storage.StorageRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动自检：把「库里有哪些后端的数据」和「本进程装了哪些后端」对一遍。
 *
 * <p>为什么需要这一步。多后端共存之后，配置只决定新数据写到哪，不决定老数据能不能读。
 * 一个最容易踩的场景：本地开发只配了 {@code storage.type=local}，而库里有 S3 时期的记录。
 * 服务能正常启动、上传下载新文件都正常，只有那些老文件点开是 1010——如果没有这样一条
 * 启动日志，排查要从接口一路倒推到配置，而答案其实在启动时就已知。
 *
 * <p>这里只报告不拦截：数据状态不是配置错误，因为「有几个后端的数据」属于历史事实，
 * 让人起不来服务是更糟的选择。真正该拦的是 {@link StorageRegistry} 构造时对默认后端的校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageStartupCheck implements ApplicationRunner {

    private final StorageRegistry registry;
    private final FileInfoMapper fileInfoMapper;
    private final UploadTaskMapper taskMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            report();
        } catch (RuntimeException e) {
            // 库还没建表、连接抖动都可能走到这里。这是自检，不该成为启动的阻塞点
            log.warn("存储台账自检跳过（查询数据库失败）: {}", e.getMessage());
        }
    }

    private void report() {
        List<Map<String, Object>> files = fileInfoMapper.statAvailableByStorage();
        List<Map<String, Object>> tasks = taskMapper.statInFlightByStorage();

        if ((files == null || files.isEmpty()) && (tasks == null || tasks.isEmpty())) {
            log.info("存储台账：库内暂无可用文件，默认后端={}", registry.defaultType());
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("存储台账（默认后端=").append(registry.defaultType())
                .append("，已装配=").append(registry.loadedTypes())
                .append("，已注册=").append(registry.registeredTypes()).append("）");

        long readable = 0;
        long unreadable = 0;
        Map<String, String> notes = new LinkedHashMap<>();

        for (Map<String, Object> row : files) {
            String raw = str(row, "storageType");
            String type = StorageRegistry.normalize(raw);
            String key = type.isEmpty() ? "(空)" : type;
            long cnt = num(row, "cnt");
            long bytes = num(row, "bytes");

            boolean available = !type.isEmpty()
                    && registry.registeredTypes().contains(type)
                    && !registry.brokenTypes().contains(type);
            if (available) {
                readable += cnt;
            } else {
                unreadable += cnt;
            }
            sb.append("\n  ").append(pad(key, 8)).append("可用 ")
                    .append(pad(String.valueOf(cnt), 4)).append(" 条 ")
                    .append(pad(mb(bytes), 10))
                    .append(available ? "" : "  <- 本进程不可读（后端未注册或装配失败）");
        }

        if (tasks != null && !tasks.isEmpty()) {
            for (Map<String, Object> row : tasks) {
                String type = StorageRegistry.normalize(str(row, "storageType"));
                long cnt = num(row, "cnt");
                sb.append("\n  进行中会话 ").append(pad(type.isEmpty() ? "(空)" : type, 8))
                        .append(cnt).append(" 个（分片已落该后端，切后端不影响它们）");
            }
        }

        log.info(sb.toString());

        if (unreadable > 0) {
            log.warn("有 {} 条可用记录所属的存储后端在本进程内不可装配，这些文件下载会返回 1010。"
                    + "如需要读到它们，请把对应后端的连接配置补上后重启", unreadable);
        } else {
            // 已注册不等于已装配：非默认后端是惰性装配的，第一次真读到数据时才建连接。
            // 这里说的是「不会因为后端没注册而读不到」，不是「连接一定通」
            log.info("存储台账核对通过：{} 条可用记录所属后端均已注册，首次访问时按需装配", readable);
        }
    }

    /** MyBatis 返回的 Map 键大小写随驱动而变，这里统一按忽略大小写与下划线的名字取值 */
    private static Object field(Map<String, Object> row, String name) {
        String want = name.replace("_", "").toLowerCase();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().replace("_", "").toLowerCase().equals(want)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String str(Map<String, Object> row, String name) {
        Object v = field(row, name);
        return v == null ? "" : v.toString();
    }

    private static long num(Map<String, Object> row, String name) {
        Object v = field(row, name);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
