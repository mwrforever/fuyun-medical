package com.fuyun.pharmacy.api;

import java.util.List;

/**
 * 药品字典变更留痕广播载荷（pharmacy.drug.changed，V702 id 30 冻结契约）。
 * broadcast 语义（Spec R6-10）：消费方不做缓存订阅，实时回查 GET /drugs/search。
 *
 * @param drugId     药品 id（string 承载雪花 id，D-18 同源）
 * @param drugCode   院内码
 * @param changeType 变更类型 CREATE|UPDATE|MAPPING（MAPPING=医保对照维护）
 */
public record DrugChangedPayload(String drugId, String drugCode, String changeType) {

    /** 组件名清单（Task 4 契约测试反射断言与 V702 id 30 desc 逐字同源的锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("drugId", "drugCode", "changeType");
}
