package com.fuyun.patient.api;

/**
 * 患者上下文解析契约（CF-3 冻结载体之一：全院唯一解析入口，M02 Spec §3.3/§8「被下游依赖」面）。
 *
 * <p>归一语义（指针映射读侧收敛）：入参 patient_id 若为已合并从档，返回值收敛到主档
 * （{@code resolvedPatientId = 主档}）；业务模块必须以 resolvedPatientId 关联临床数据，
 * 禁止绕过解析服务以介质号直连（M02 红线 1）。拦截语义：冻结档案 blocked=true，业务模块据此
 * 拒绝挂号/入院等新就诊（M02 §5 patient 状态机）。实现为两级缓存读服务（P95 < 100ms，Spec §9）。
 */
public interface PatientContextResolver {

    /**
     * 解析患者上下文（标识→主索引归一后的档案视图）。
     *
     * @param patientId 患者主索引（可为从档 id，解析收敛主档）；来源：业务模块持有/解析所得
     * @return 解析视图（非空）；来源：两级缓存或 patient 主表
     * @throws com.fuyun.common.exception.BizException PAT-1001（404）patientId 无对应档案时触发；
     *                                                  建议处理策略：调用方按业务失败终止并提示
     */
    PatientContextView resolve(long patientId);
}
