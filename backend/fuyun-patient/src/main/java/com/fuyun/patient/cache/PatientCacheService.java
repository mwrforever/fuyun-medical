package com.fuyun.patient.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.patient.api.PatientContextView;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 患者上下文两级缓存（M02 Spec §9：解析 P95 < 100ms，进程内 + Redis，事件失效 + 短 TTL 兜底）。
 *
 * <p>一致性口径（A.5-3 Cache-Aside）：本域写路径（冻结/解冻/更新/合并/标识变更）在本实例即时
 * evict；跨实例失效由本模块自消费事件（PatientCacheInvalidationListener，Task 13）承担；兜底
 * TTL L1=10s / L2=60s（Spec「缓存兜底短 TTL」）。Redis 键 fy:patient:ctx:{patientId}（A.5-1
 * 命名），值为 PatientContextView JSON（StringRedisTemplate，禁 JDK 序列化）。
 *
 * <p>领域缓存类归 cache/ 目录（A.5-16 复杂场景口径）；无状态单例（多实例部署前提，A.1-9）。
 */
@Slf4j
public class PatientCacheService {

    /** L1 进程内 TTL（秒）：单实例抖动窗口 */
    private static final long L1_TTL_SECONDS = 10;

    /** L2 Redis TTL：跨实例失效兜底窗口 */
    private static final Duration L2_TTL = Duration.ofSeconds(60);

    /** Redis 键前缀（fy:{module}:{biz}:{id} 命名） */
    private static final String KEY_PREFIX = "fy:patient:ctx:";

    /** L1 容量上限（LRU 淘汰；200 万患者规模下热档工作集远小于此） */
    private static final int L1_MAX_ENTRIES = 10_000;

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    /** L1 进程内缓存（LRU 访问序 + 值对 = JSON 文本与到期时刻） */
    private final java.util.LinkedHashMap<Long, L1Entry> l1Cache = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, L1Entry> eldest) {
            return size() > L1_MAX_ENTRIES;
        }
    };

    /** L1 值载体（JSON 文本 + 到期时刻毫秒） */
    private record L1Entry(String json, long expireAtMillis) {}

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param redisTemplate String 模板（A.5-1），非空
     * @param objectMapper  全局定制实例（Long→String 同源），非空
     */
    public PatientCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 读解析视图缓存（L1 命中 → L2 回填 → 未命中 empty；缓存层故障降级直查由调用方承担）。
     *
     * @param patientId 患者主索引，非空
     * @return 缓存视图；未命中 empty
     */
    public Optional<PatientContextView> getView(long patientId) {
        long now = System.currentTimeMillis();
        synchronized (l1Cache) {
            L1Entry entry = l1Cache.get(patientId);
            if (entry != null && entry.expireAtMillis() > now) {
                return deserialize(patientId, entry.json());
            }
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + patientId);
            if (json != null) {
                synchronized (l1Cache) {
                    l1Cache.put(patientId, new L1Entry(json, now + L1_TTL_SECONDS * 1000));
                }
                return deserialize(patientId, json);
            }
        } catch (RuntimeException e) {
            // 缓存层故障不阻断解析主链路（调用方降级直查库），error 留痕
            log.error("解析视图 L2 缓存读取失败，降级直查：patientId={}", patientId, e);
        }
        return Optional.empty();
    }

    /**
     * 写解析视图缓存（L1 + L2 双写；调用方保证写库成功后调用——Cache-Aside 顺序）。
     *
     * @param patientId 患者主索引，非空
     * @param view      归一视图，非空
     */
    public void putView(long patientId, PatientContextView view) {
        try {
            String json = objectMapper.writeValueAsString(view);
            redisTemplate.opsForValue().set(KEY_PREFIX + patientId, json, L2_TTL);
            synchronized (l1Cache) {
                l1Cache.put(patientId, new L1Entry(json, System.currentTimeMillis() + L1_TTL_SECONDS * 1000));
            }
        } catch (JsonProcessingException e) {
            log.error("解析视图序列化失败（短 TTL 兜底仍成立）：patientId={}", patientId, e);
        } catch (RuntimeException e) {
            log.error("解析视图缓存写入失败（短 TTL 兜底仍成立）：patientId={}", patientId, e);
        }
    }

    /**
     * 失效解析视图缓存（本域写路径即时调用；L1/L2 双清，删除失败靠短 TTL 兜底）。
     *
     * @param patientId 患者主索引，非空
     */
    public void evictView(long patientId) {
        synchronized (l1Cache) {
            l1Cache.remove(patientId);
        }
        try {
            redisTemplate.delete(KEY_PREFIX + patientId);
        } catch (RuntimeException e) {
            log.error("解析视图缓存失效删除失败（短 TTL 兜底）：patientId={}", patientId, e);
        }
    }

    /** JSON 反序列化（损坏值按未命中处理，防坏缓存常驻） */
    private Optional<PatientContextView> deserialize(long patientId, String json) {
        try {
            return Optional.of(objectMapper.readValue(json, PatientContextView.class));
        } catch (JsonProcessingException e) {
            log.error("解析视图缓存反序列化失败，按未命中处理：patientId={}", patientId, e);
            return Optional.empty();
        }
    }
}
