package com.fuyun.patient.api;

import java.util.Collection;
import java.util.List;

/**
 * 患者脱敏展示名查询契约（M03 队列叫号/候诊快照脱敏姓名出网的唯一跨模块读取面）：
 * patient api 敏感红线（姓名原文不出现于任何跨模块契约与事件载荷）的延伸落地——本契约只出
 * common SensitiveMasker.maskName 脱敏后的展示名（保留姓氏、其余打星，M02 脱敏规则同源），
 * 姓名原文不出本模块。实现侧批量精确投影（in 查询禁 N+1，A.4.3-14），调用方（M03 队列快照/
 * 叫号推送）零脱敏逻辑（展示名即出网形态）。
 *
 * <p>消费方：fuyun-outpatient 分诊台队列快照（GET /queues/{queueId}/tickets）与叫号 WS 推送
 * （/topic/outpatient/queue|doctor，Task 7）；后续门诊医生站候诊列表（Task 8 patientQueue）
 * 同源复用。实现装配归 PatientWebConfig @Import（PracticeCheckPort 同款「api 接口+provider 侧
 * 实现转调」范式）。
 */
public interface PatientNameQuery {

    /**
     * 批量查询患者脱敏展示名（in 精确投影，无命中的 id 不在结果中出现）。
     *
     * @param patientIds 患者主索引集合（归一主档 id），非 null；空集合直接返回空列表（零查询）
     * @return 脱敏展示名视图列表（patientId ↔ displayName 对）；姓名为空/单字的掩码原样返回
     *         （SensitiveMasker.maskName 语义）；无命中返回空列表
     */
    List<PatientDisplayName> displayNamesOf(Collection<Long> patientIds);
}
