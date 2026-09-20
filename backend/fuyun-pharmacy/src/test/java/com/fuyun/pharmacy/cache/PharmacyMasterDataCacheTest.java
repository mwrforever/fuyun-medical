package com.fuyun.pharmacy.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 患者归一映射缓存单测：merged 写映射（携键 TTL 写时续期）、split 失效逆映射、resolve 未命中
 * 原样返回、dict 水位三参 set 携 TTL（A.5-1 fy: 键命名 + 禁无过期键）。
 */
@ExtendWith(MockitoExtension.class)
class PharmacyMasterDataCacheTest {

    /** 与主类 KEY_TTL 同源（24h，A.5-1 TTL 兜底窗；私有常量不外引，字面量双锚防漂移） */
    private static final Duration KEY_TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, String, String> hashOperations;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("resolveSurvivor：映射命中返回主档，未命中原样返回（读侧归一无副作用）")
    void resolveSurvivorFallsBackToInputWhenUnmapped() {
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("fy:pharmacy:patient:merge-map", "12")).thenReturn(null);
        PharmacyMasterDataCache cache = new PharmacyMasterDataCache(redisTemplate);

        assertThat(cache.resolveSurvivor(12L)).isEqualTo(12L);
    }

    @Test
    @DisplayName("onMerged/onSplit：写从档→主档映射携键 TTL 写时续期，拆分恢复删除逆映射键")
    void mergedWritesAndSplitRemovesMapping() {
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
        PharmacyMasterDataCache cache = new PharmacyMasterDataCache(redisTemplate);

        cache.onMerged(12L, 7L);
        cache.onSplit(12L);

        verify(hashOperations).put("fy:pharmacy:patient:merge-map", "12", "7");
        // A.5-1 TTL 兜底：写时续期（hash 持续覆写属活性键，TTL 仅承载事件流中断后的过期兜底）
        verify(redisTemplate).expire("fy:pharmacy:patient:merge-map", KEY_TTL);
        verify(hashOperations).delete("fy:pharmacy:patient:merge-map", "12");
    }

    @Test
    @DisplayName("dict 版本水位：给药途径/频次两类三参 set 携 TTL，他类忽略（P3 审方字典缓存失效链路预置）")
    void dictVersionWatermarkRefreshedForMedicationTypesOnly() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        PharmacyMasterDataCache cache = new PharmacyMasterDataCache(redisTemplate);

        cache.refreshDictVersion("medication.route", "3");
        cache.refreshDictVersion("gender", "2");

        // A.5-1：长期字典水位键也必须 TTL 兜底（禁两参无过期 set）
        verify(valueOperations).set("fy:pharmacy:dict:version:medication.route", "3", KEY_TTL);
        verify(valueOperations, never()).set(eq("fy:pharmacy:dict:version:gender"), anyString(), any(Duration.class));
    }
}
