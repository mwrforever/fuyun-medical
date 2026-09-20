package com.fuyun.patient.api;

/**
 * 「诊疗关系查询」SPI 扩展点（D-16 三态硬门禁，02-patient.md:140 归 PR-5 注记兑现；2026-09-19 recon 裁决 10）。
 *
 * <p><b>冻结语义（与 {@link OngoingVisitQuery} 同款契约形态）</b>：Spring 容器内无任何本接口实现时，
 * unmask 第二道校验跳过并记录 warn——维持角色豁免单门禁现状；任一实现（M03 门诊在途诊疗关系）注册后
 * 自动收紧为「无豁免且无诊疗关系即 403」。修改须经消费方（M03）双向评审。
 */
public interface CareRelationQuery {

    /**
     * 判定操作者与患者是否存在在途诊疗关系（明文查阅第二道门禁依据）。
     *
     * @param patientId  患者主索引；来源：unmask 请求体
     * @param operatorId 操作者标识（登录名/工号）；来源：OperatorContextHolder
     * @return true=存在在途诊疗关系（放行并留痕）；false=无关系（403）
     */
    boolean hasCareRelation(long patientId, String operatorId);
}
