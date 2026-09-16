package com.fuyun.patient.enums;

/**
 * EMPI 匹配结论（M02 §3.1 分层组合）：强标识唯一命中且属性一致自动归一 / 命中矛盾或弱标识达阈值
 * 转人工 / 低于阈值新建。
 */
public enum MatchOutcome {
    /** 自动归一（强标识唯一命中且人口属性一致） */
    AUTO_MATCH,
    /** 疑似重复（命中矛盾或弱标识评分达阈值，转人工审核，不自动合并） */
    SUSPECT,
    /** 无匹配（低于阈值，新建档案） */
    NO_MATCH;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致）。
     *
     * @param code 结论值，非空；来源：匹配引擎出参与 patient_match_checkVO.outcome
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static MatchOutcome of(String code) {
        return valueOf(code);
    }
}
