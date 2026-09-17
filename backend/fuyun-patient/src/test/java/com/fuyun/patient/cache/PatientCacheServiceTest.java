package com.fuyun.patient.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.patient.api.PatientContextView;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 两级缓存单测：L1 命中/L2 回填/evict 双清/Redis 故障降级（写读异常不阻断业务）。 */
@ExtendWith(MockitoExtension.class)
class PatientCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PatientCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new PatientCacheService(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("put 后 getView 命中 L1（TTL 内不产生额外 Redis 读）")
    void l1HitAvoidsRedisRead() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService.putView(1L, new PatientContextView(1L, 1L, "NORMAL", false, ""));
        assertThat(cacheService.getView(1L)).isPresent();
        verify(redisTemplate).opsForValue();
    }

    @Test
    @DisplayName("L2 存量值回填并正确反序列化（跨实例共享语义）")
    void l2ValueIsReadBackAndDeserialized() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(eq("fy:patient:ctx:2")))
                .thenReturn(
                        "{\"patientId\":2,\"resolvedPatientId\":1,\"status\":\"MERGED\",\"blocked\":false,\"blockReason\":\"\"}");
        Optional<PatientContextView> view = cacheService.getView(2L);
        assertThat(view).isPresent();
        assertThat(view.get().resolvedPatientId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("evict 双清：L1 移除且 Redis 键删除")
    void evictClearsBothLevels() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService.putView(3L, new PatientContextView(3L, 3L, "NORMAL", false, ""));
        cacheService.evictView(3L);
        verify(redisTemplate).delete("fy:patient:ctx:3");
        assertThat(cacheService.getView(3L)).isEmpty();
    }

    @Test
    @DisplayName("Redis 读故障降级返回 empty（解析主链路直查库，不抛异常）")
    void redisFailureDegradesGracefully() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        assertThat(cacheService.getView(4L)).isEmpty();
    }
}
