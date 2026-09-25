package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 床位五态词表（V903 bed.status 值域，04-inpatient Spec §5 状态机冻结）：
 * FREE 空床 ⇄ RESERVED 预占（预约入院/转科预占/全院签床）；FREE → OCCUPIED（直接分配）、
 * RESERVED → OCCUPIED（占床确认）；OCCUPIED → DISINFECTING（转出/出院后消毒流转）→ FREE
 * （消毒完成确认）；FREE ⇄ MAINTENANCE（维修）。防重复占床硬防线：仅 FREE/RESERVED 可占床
 * （CAS 条件更新 + 影响行数判定）；消毒/维修中分配一律拒绝（IP-1005）。
 */
public enum BedStatus {

    /** 空床（可预占/可分配/可转维修） */
    FREE("FREE"),

    /** 预占（预约入院/转科预占/全院一张床签床；未绑定 visit_id） */
    RESERVED("RESERVED"),

    /** 占床（在院患者占用；bed.visit_id 冗余承载占用主体） */
    OCCUPIED("OCCUPIED"),

    /** 消毒中（患者转出/出院后的终末消毒流转，禁分配） */
    DISINFECTING("DISINFECTING"),

    /** 维修中（床位维修停用，禁分配） */
    MAINTENANCE("MAINTENANCE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    BedStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方）
     */
    public static BedStatus fromCode(String code) {
        for (BedStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
