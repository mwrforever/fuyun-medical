package com.fuyun.patient.gateway;

/**
 * 手工证件核实兜底适配器（读卡器/外部通道联调前的唯一实现，P1 计划 :62 口径）。
 *
 * <p>语义：窗口人员对照实体证件人工录入，核验结论按「有证件号即视同人工核实通过、
 * 实名标记以是否录入证件号为准」——无证件号时返回未实名（授权建档路径，红线：未实名标记必落）。
 * 无 stereotype 注解：Bean 注册归 PatientWebConfig（@Bean 以接口类型暴露，Step 7）。
 */
public class ManualMediaAdapter implements IdentityMediaGateway {

    /**
     * 人工核验直通：证件号录入即视为人工核实通过（实名），未录入即未实名。
     *
     * @param mediumType 介质类型，非空；来源：建档请求
     * @param rawValue   人工录入的证件号/卡号原文，可空；来源：窗口录入
     * @return 提取结果（identifierType 回填入参类型，identifierValue 原文回传交加密层）；非 null
     */
    @Override
    public IdentityExtract verify(String mediumType, String rawValue) {
        boolean hasValue = rawValue != null && !rawValue.isBlank();
        return new IdentityExtract(hasValue, mediumType, rawValue, null, hasValue);
    }
}
