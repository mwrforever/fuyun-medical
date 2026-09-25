package com.fuyun.pharmacy.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.vo.ReviewTaskVO;

/**
 * 住院用药审方服务（M06 审方薄切片）：消费面落两表（快照 + 待审任务）与工作台决策面
 * （通过/驳回 + 回执发布）。审方规则引擎/毒麻/抗菌药完整化/双签 P3——薄切片全部人工审。
 */
public interface IMedicationReviewService {

    /**
     * 医嘱开立消费落库（listener 委托入口）：首投落 order_medication 快照 + review_task 待审；
     * 重复投递/重发按「仅一任务」幂等收敛（PENDING/APPROVED 跳过；REJECTED 重提闭环=同任务
     * 复位重开 + 明细快照刷新）。
     *
     * @param m04OrderNo 住院医嘱号，非空；来源：事件载荷（消费侧已守卫必填）
     * @param visitId    住院就诊号（I 型 14 位），非空
     * @param patientId  患者主索引，非空
     * @param freqCode   频次编码，可空（临时医嘱）
     * @param itemsJson  医嘱项明细快照 JSON 数组文本，非空（消费侧整段透传）
     */
    void onOrderCreated(String m04OrderNo, String visitId, long patientId, String freqCode, String itemsJson);

    /**
     * 审方工作台列表（先到先审 FIFO 序）：任务行联装配药快照号面与明细。
     *
     * @param status 状态过滤（ReviewTaskStatus code），可空——空不过滤
     * @param page   页码（0 基）
     * @param size   单页条数
     * @return 分页出参（content 可为空清单）
     */
    PageResult<ReviewTaskVO> list(String status, int page, int size);

    /**
     * 审方通过（PENDING→APPROVED）：CAS 迁移 + 发布 pharmacy.medication-order.audit-completed
     * （V800 id 53；target=m04 医嘱号，M04 置医嘱可执行）。
     *
     * @param taskId  任务 id，非空
     * @param opinion 药师意见，可空（通过场景可选）
     * @throws com.fuyun.common.exception.BizException PH-1019（404 任务不存在）/
     *                 PH-1021（404 快照缺失）/PH-1020（409 非 PENDING 或并发被抢）
     */
    void approve(long taskId, String opinion);

    /**
     * 审方驳回（PENDING→REJECTED，意见必填）：CAS 迁移 + 发布 pharmacy.medication-order.
     * audit-rejected（V800 id 54；rejectReason=药师意见必附，M04 置驳回态走医生站重提）。
     *
     * @param taskId  任务 id，非空
     * @param opinion 药师意见，非空（缺/空白即 PH-1020——brief 冻结语义）
     * @throws com.fuyun.common.exception.BizException PH-1020（409 缺意见/非 PENDING/并发被抢）/
     *                 PH-1019（404 任务不存在）/PH-1021（404 快照缺失）
     */
    void reject(long taskId, String opinion);
}
