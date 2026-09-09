package com.fuyun.system.vo;

import com.fuyun.system.enums.DictVersionStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 字典版本出参（版本创建响应与 GET /dicts/{type} 契约型读共用，BRIEF-PR3-01 §3.2）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。status 为枚举类型，JSON 经
 * {@code @JsonValue} 输出存储 code（A.2-7 双向映射口径）。
 *
 * @param typeCode    所属字典类型编码，非空
 * @param version     版本号（同类型内自增，1 起），非空
 * @param status      版本状态，非空；DRAFT/PUBLISHED/DEPRECATED
 * @param publishedAt 发布时刻，可空（DRAFT 阶段为 null）
 * @param items       条目清单（按 sort 升序），非 null；创建响应为空清单（草稿尚无条目）
 */
public record DictVersionVO(
        String typeCode,
        Integer version,
        DictVersionStatus status,
        OffsetDateTime publishedAt,
        List<DictItemVO> items) {}
