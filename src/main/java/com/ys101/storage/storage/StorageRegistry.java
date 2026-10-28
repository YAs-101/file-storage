package com.ys101.storage.storage;

import com.ys101.storage.common.BizException;
import com.ys101.storage.common.ResultCode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 多后端注册表：写入按配置，读取按记录。
 *
 * <p>存储抽象的第一层是「换实现」——一个 {@code storage.type} 决定全局用哪个后端，
 * 业务代码一行不改。但这一层有个前提：库里所有数据都在同一个后端上。数据是持久的、
 * 配置是易变的，切换配置的那一刻，老数据仍然留在老后端，于是「换实现」立刻退化成
 * 「老文件全部读不到」。
 *
 * <p>这一层补的就是这个缺口：
 * <ul>
 *   <li>{@link #defaultStorage()} 给写入路径用——新数据永远落在当前配置的后端；</li>
 *   <li>{@link #of(String)} 给读取与删除用——按 {@code file_info.storage_type} /
 *       {@code upload_task.storage_type} 找回数据真正所在的后端。</li>
 * </ul>
 *
 * <p>装配是惰性的：只有真的读到某个后端的数据时才会创建它的客户端。否则本地开发
 * 只跑 local 模式也要先连一次 S3，没起对象存储就直接起不来。
 * 唯一例外是默认后端——配置写错应该当场炸，而不是等到第一次上传。
 */
@Slf4j
public class StorageRegistry {

    private final String defaultType;
    private final Map<String, Supplier<FileStorage>> factories;
    private final Map<String, FileStorage> instances = new ConcurrentHashMap<>();
    /** 装配失败过的后端。S3 启动失败通常是端点写错或服务没起，反复重试只会让每个请求都卡几十秒 */
    private final Set<String> broken = ConcurrentHashMap.newKeySet();

    public StorageRegistry(String defaultType, Map<String, Supplier<FileStorage>> factories) {
        this.defaultType = normalize(defaultType);
        this.factories = new LinkedHashMap<>(factories);
        if (!this.factories.containsKey(this.defaultType)) {
            throw new IllegalStateException("未知 storage.type: " + defaultType
                    + "，可选 " + this.factories.keySet());
        }
        // 默认后端必须当场装好：配置项写错在启动时暴露，比在第一次上传时暴露便宜得多
        defaultStorage();
    }

    /** 当前配置的后端标识，写入路径用它，也用于新会话的归属标记 */
    public String defaultType() {
        return defaultType;
    }

    public FileStorage defaultStorage() {
        return of(defaultType);
    }

    /**
     * 取指定后端。
     *
     * @param type 记录的 storage_type；为空时按默认后端处理（老数据没有这个字段时的容错）
     * @throws BizException 该后端未注册或装配失败——这既不是「文件不存在」也不是「参数错误」，
     *                      所以单独用 1010 而不是 404，否则排障时会误以为数据丢了
     */
    public FileStorage of(String type) {
        String key = normalize(type);
        if (key.isEmpty()) {
            key = defaultType;
        }
        FileStorage cached = instances.get(key);
        if (cached != null) {
            return cached;
        }
        Supplier<FileStorage> factory = factories.get(key);
        if (factory == null) {
            throw unavailable(type, "未注册该存储后端");
        }
        if (broken.contains(key)) {
            throw unavailable(type, "该后端此前装配失败，需修复配置或连接后重启");
        }
        synchronized (this) {
            FileStorage again = instances.get(key);
            if (again != null) {
                return again;
            }
            try {
                FileStorage created = factory.get();
                instances.put(key, created);
                log.info("存储后端已装配 type={}（已有 {}）", key, instances.keySet());
                return created;
            } catch (RuntimeException e) {
                broken.add(key);
                log.error("存储后端装配失败 type={}，该后端的数据在本次进程内不可读: {}",
                        key, e.getMessage());
                throw unavailable(type, e.getMessage());
            }
        }
    }

    /** 已装配的后端类型，启动自检与健康检查用 */
    public Set<String> loadedTypes() {
        return Set.copyOf(instances.keySet());
    }

    /** 已注册但当前不可用的后端类型（装配失败过） */
    public Set<String> brokenTypes() {
        return Set.copyOf(broken);
    }

    public Set<String> registeredTypes() {
        return Set.copyOf(factories.keySet());
    }

    private BizException unavailable(String type, String detail) {
        return new BizException(ResultCode.STORAGE_UNAVAILABLE,
                "数据所属存储后端 " + type + " 不可用：" + detail);
    }

    /** 记录的 storage_type 统一大写比较，历史数据里大小写两种写法都出现过 */
    public static String normalize(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }
}
