package com.fuyun.nursing.api;

/**
 * 联动任务创建端口（M14→M05 跨模块写面，P2 PR-3 Task 12）：iot LinkageExecutor NURSING_TASK
 * 动作的进程内直调入口——Spec「M14 联动动作经 POST /tasks 幂等创建」的端口化承载（进程内
 * 直调形态，不走 HTTP 自调，OrderExecutionConfirmPort 同款 spring-modulith api 面先例；
 * 消费方 fuyun-iot 仅依赖本 api 包，Modulith verify() 把关单向依赖）。
 *
 * <p><b>幂等语义（brief 冻结）</b>：幂等键=linkageNo，经 source=IOT_LINKAGE +
 * source_ref=linkageNo 业务级回查去重实现（nursing_task 两列 V805 既有，无 (source,
 * source_ref) 唯一索引且不追加——并发防御由 linkage 执行侧 attempt 语义保序）；同 linkageNo
 * 重放回查既有行返回原 taskNo（created=false，零新建零事件），首建经
 * {@code INursingTaskService.create} 既有面承载（发号/PENDING 默认态/nursing.task.created
 * 事件发布全部复用，零新事件零新队列）。
 *
 * <p><b>source_ref 词表留痕（V400 unbind_reason 先例）</b>：V805 source_ref 列注释词表
 * 「执行单号/告警号/规则号/评估单号」未含联动号——禁迁移不可改列注释，本端口以 linkageNo
 * 落该列属词表语义扩展（联动执行号即来源引用），留痕经本 javadoc + PR 描述转呈。
 *
 * <p>线程安全：无状态端口；实现侧写路径经 create 的 @Transactional 单事务收口。
 */
public interface NursingTaskLinkagePort {

    /**
     * 联动任务幂等创建：先按幂等键回查（source=IOT_LINKAGE + source_ref=linkageNo，deleted=0
     * 惯例由 @TableLogic 携带），命中即返回原 taskNo（created=false）；未命中经
     * {@code INursingTaskService.create} 创建 IOT_LINKAGE 行（created=true）。
     *
     * @param request 联动创建请求，非空；来源：iot LinkageExecutor（告警绑定快照 + 规则动作配置）
     * @return 创建结果（taskNo + created 区分位），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 linkageNo/patientId/visitId/
     *                 wardId 缺失或底层 code 校验拒绝）／NS-1016（409 计划时间补录越窗或任务号
     *                 唯一冲突——原样透传，iot 侧走失败重试路）
     */
    NursingTaskLinkageResult createTask(NursingTaskLinkageRequest request);
}
