package com.fuyun.pharmacy.cache;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * M06 主数据读侧缓存（患者合并归一映射 + 给药途径/频次字典版本水位）：Redis 承载、幂等刷新。
 * patient.merged 成对订阅 split（M-25）；拆分恢复仅失效映射键（已归一查询随新映射回切由业务
 * 重放承载——逆映射数据回切随 P2 拆分快照方案，本缓存不承载）。
 */
@RequiredArgsConstructor
public class PharmacyMasterDataCache {

    /** 患者合并映射 hash 键（field=从档 id 文本，value=主档 id 文本） */
    private static final String MERGE_MAP_KEY = "pharmacy:patient:merge-map";

    /** 字典版本水位键前缀（suffix=dict type） */
    private static final String DICT_VERSION_PREFIX = "pharmacy:dict:version:";

    /** PR-4 消费的字典类型白名单（给药途径/用药频次，与 V607 预置 type_code 逐字同源；
     *  预置种子已 PUBLISHED 在库，条目级消费随 P3 接续） */
    private static final List<String> MEDICATION_DICT_TYPES = List.of("medication.route", "medication.frequency");

    private final StringRedisTemplate redisTemplate;

    /**
     * 读侧归一：从档 id → 主档 id（未命中原样返回）。
     *
     * @param patientId 请求侧患者 id
     * @return 归一后患者 id
     */
    public long resolveSurvivor(long patientId) {
        // 显式见证变量定绑泛型（opsForHash 链式调用无法回传赋值目标类型，HK/HV 退化为 Object 编译失败）
        HashOperations<String, String, String> hash = redisTemplate.opsForHash();
        String survivor = hash.get(MERGE_MAP_KEY, String.valueOf(patientId));
        return survivor == null ? patientId : Long.parseLong(survivor);
    }

    /**
     * 患者合并回执：写从档→主档映射（幂等覆写）。
     *
     * @param mergedPatientId   从档 id（载荷 mergedPatientId）
     * @param survivorPatientId 主档 id（载荷 survivorPatientId）
     */
    public void onMerged(long mergedPatientId, long survivorPatientId) {
        redisTemplate
                .opsForHash()
                .put(MERGE_MAP_KEY, String.valueOf(mergedPatientId), String.valueOf(survivorPatientId));
    }

    /**
     * 患者拆分回执：删除恢复从档的映射键（逆映射失效）。
     *
     * @param restoredPatientId 恢复 NORMAL 的原从档 id（载荷 restoredPatientId）
     */
    public void onSplit(long restoredPatientId) {
        redisTemplate.opsForHash().delete(MERGE_MAP_KEY, String.valueOf(restoredPatientId));
    }

    /**
     * 字典版本水位刷新（仅白名单两类；他类广播忽略）。
     *
     * @param type    字典类型 code
     * @param version 版本文本
     */
    public void refreshDictVersion(String type, String version) {
        if (MEDICATION_DICT_TYPES.contains(type)) {
            redisTemplate.opsForValue().set(DICT_VERSION_PREFIX + type, version);
        }
    }
}
