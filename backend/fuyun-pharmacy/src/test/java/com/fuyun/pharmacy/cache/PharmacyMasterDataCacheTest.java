package com.fuyun.pharmacy.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 患者归一映射缓存单测：merged 写映射、split 失效逆映射、resolve 未命中原样返回。 */
@ExtendWith(MockitoExtension.class)
class PharmacyMasterDataCacheTest {

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
        when(hashOperations.get("pharmacy:patient:merge-map", "12")).thenReturn(null);
        PharmacyMasterDataCache cache = new PharmacyMasterDataCache(redisTemplate);

        assertThat(cache.resolveSurvivor(12L)).isEqualTo(12L);
    }

    @Test
    @DisplayName("onMerged/onSplit：写从档→主档映射，拆分恢复删除逆映射键")
    void mergedWritesAndSplitRemovesMapping() {
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
        PharmacyMasterDataCache cache = new PharmacyMasterDataCache(redisTemplate);

        cache.onMerged(12L, 7L);
        cache.onSplit(12L);

        verify(hashOperations).put("pharmacy:patient:merge-map", "12", "7");
        verify(hashOperations).delete("pharmacy:patient:merge-map", "12");
    }

    @Test
    @DisplayName("dict 版本水位：给药途径/频次两类刷新，他类忽略（P3 审方字典缓存失效链路预置）")
    void dictVersionWatermarkRefreshedForMedicationTypesOnly() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        PharmacyMasterDataCache cache = new PharmacyMasterDataCache(redisTemplate);

        cache.refreshDictVersion("medication.route", "3");
        cache.refreshDictVersion("gender", "2");

        verify(valueOperations).set("pharmacy:dict:version:medication.route", "3");
        verify(valueOperations, org.mockito.Mockito.never())
                .set(
                        org.mockito.ArgumentMatchers.eq("pharmacy:dict:version:gender"),
                        org.mockito.ArgumentMatchers.anyString());
    }
}
