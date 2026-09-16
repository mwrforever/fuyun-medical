package com.fuyun.patient.gateway;

/**
 * 介质核验与身份提取统一适配器（M02 Spec §3.5 方案定稿：身份证读卡直读/电子健康卡注册系统/
 * 医保电子凭证经 M13 通道，全部收敛到同一服务位；外部不可用时按介质降级人工证件核实并标记未实名）。
 *
 * <p>PR-2 口径（P1 计划 :62）：读卡器接口位——联调以手工录入兜底，唯一实现为
 * {@link ManualMediaAdapter}（直通人工核实语义）；健康卡/医保通道适配器随对接方就绪新增实现，
 * 本接口不提前定义渠道方法（禁推测性设计）。
 */
public interface IdentityMediaGateway {

    /**
     * 以原始介质凭据完成实名核验并提取身份要素（建档入口统一调用）。
     *
     * @param mediumType 介质类型（IdentifierType 词表，如 ID_CARD/HEALTH_CARD）；来源：建档请求
     * @param rawValue   介质原始值（证件号原文/卡号/码值），可空（人工兜底场景允许仅录姓名等要素）；
     *                   来源：读卡器输出或窗口人工录入
     * @return 提取结果（verified=true 才允许置实名标记）；非 null
     */
    IdentityExtract verify(String mediumType, String rawValue);

    /**
     * 核验提取结果（record 透明载体）。
     *
     * @param verified       实名核验是否通过（false=降级人工核实，建档标记未实名）
     * @param identifierType 核验到的标识类型；来源：适配器
     * @param identifierValue 核验到的标识值原文（交由调用方加密落库）；可空
     * @param cardNo         卡面号（卡类介质）；可空
     * @param realName       是否实名（与 verified 同源冗余，建档语义直读）
     */
    record IdentityExtract(
            boolean verified, String identifierType, String identifierValue, String cardNo, boolean realName) {}
}
