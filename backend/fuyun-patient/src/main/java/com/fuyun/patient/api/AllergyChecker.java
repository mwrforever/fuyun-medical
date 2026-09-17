package com.fuyun.patient.api;

import java.util.List;

/**
 * 健康档案过敏项快速校验契约位（M02 Spec §143 内部服务接口；2026-09-16 范围确认：M06 到位前
 * 无调用方，实现与单测随 PR-2 齐备——Spec §7 明文契约，非推测性预留）。
 */
public interface AllergyChecker {

    /**
     * 取患者当前有效过敏项清单（审方预检/开单嵌查入口）。
     *
     * @param patientId 患者主索引；来源：调用方（M06/M05）经解析服务归一后的主档 id
     * @return 有效过敏项清单（无过敏为空清单，非 null）；来源：patient.health_item（ACTIVE + ALLERGY）
     */
    List<AllergyItem> listActiveAllergies(long patientId);
}
