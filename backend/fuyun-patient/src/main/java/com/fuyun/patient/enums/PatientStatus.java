package com.fuyun.patient.enums;

/**
 * 患者状态机（M02 Spec §5）：NORMAL ⇄ FROZEN（冻结/解冻）、NORMAL→MERGED（被合并）、
 * MERGED→NORMAL 仅能由 merge_record 置 REVERSED（拆分）触发；MERGED 档案不可发起新就诊。
 */
public enum PatientStatus {
    /** 正常 */
    NORMAL,
    /** 冻结（身份存疑/风控要求，解析返回拦截标记） */
    FROZEN,
    /** 已合并（从档；merged_into_patient_id 指向主档） */
    MERGED;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内状态值，非空；来源：patient.status 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static PatientStatus of(String code) {
        return valueOf(code);
    }
}
