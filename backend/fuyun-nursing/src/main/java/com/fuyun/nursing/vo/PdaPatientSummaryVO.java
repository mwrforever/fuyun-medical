package com.fuyun.nursing.vo;

import com.fuyun.patient.api.AllergyItem;
import java.util.List;

/**
 * PDA 患者摘要出参（GET /api/v1/nursing/pda/patient-summary，Task 10 冻结字段面）：
 * 标识三合一入口（腕带就诊编码/就诊卡号/证件号）解析后的床旁速览。
 *
 * <p><b>脱敏口径（P1 最小面）</b>：patientName 已按 {@code SensitiveMasker.maskName} 掩码出网
 * （保留姓氏）；证件号/手机号类字段<b>不返回</b>——PDA 床旁速览无出示原件场景，脱敏口径
 * 在模块 Spec 注记登记（Task 14）。不在区患者（门诊/已出院扫码）wardId/bedNo/nursingLevel
 * 与 latestVitals 为空、inFlightTaskCount=0——PDA 患者查询不限在区（Spec :149 P1 降级为
 * 基本信息 + 过敏 + 体征）。
 *
 * @param patientId         患者主索引（MERGED 收敛主档口径，解析链归一后值）
 * @param patientName       患者展示名（脱敏掩码形态，如 张*；不在区时为 null）
 * @param wardId            病区编码（不在区时为 null）
 * @param bedNo             床位号（不在区时为 null）
 * @param nursingLevel      护理级别（NursingLevel code，不在区时为 null）
 * @param allergies         当前有效过敏项（AllergyChecker 实时嵌查，按收敛主档取数；无过敏为空清单）
 * @param latestVitals      最近一次体征摘要（按测量时点升序取末位；无记录为 null）
 * @param inFlightTaskCount 在途任务计数（仅 PENDING/IN_PROGRESS；不在区恒 0）
 */
public record PdaPatientSummaryVO(
        Long patientId,
        String patientName,
        String wardId,
        String bedNo,
        String nursingLevel,
        List<AllergyItem> allergies,
        VitalSignVO latestVitals,
        int inFlightTaskCount) {}
