package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.api.MedicationAuditCompletedPayload;
import com.fuyun.pharmacy.api.MedicationAuditRejectedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.entity.OrderMedication;
import com.fuyun.pharmacy.entity.ReviewTask;
import com.fuyun.pharmacy.internal.PharmacyDomainEvent;
import com.fuyun.pharmacy.mapper.OrderMedicationMapper;
import com.fuyun.pharmacy.mapper.ReviewTaskMapper;
import com.fuyun.pharmacy.service.IMedicationReviewService;
import com.fuyun.pharmacy.vo.ReviewTaskVO;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 住院用药审方服务实现（M06 审方薄切片）：消费面「首投落两表、重发仅一任务、驳回重提重开」
 * 与决策面「CAS 三态机 + 回执发布」。事件经事务内 ApplicationEventPublisher →
 * PharmacyEventPublisher AFTER_COMMIT 出 fy.topic（事务内禁 MQ 直发红线）。幂等双层：
 * eventId 构件幂等（与 eventType 无关，子键后缀天然免疫）+ uk_medication_order_no/
 * uk_review_medication 业务兜底。装配归 PharmacyWebConfig @Import。
 */
@Slf4j
public class MedicationReviewServiceImpl implements IMedicationReviewService {

    /** 审方任务状态字面量（与 ReviewTaskStatus code 逐字同源） */
    private static final String STATUS_PENDING = "PENDING";
    /** 审方任务状态字面量：通过（终态） */
    private static final String STATUS_APPROVED = "APPROVED";
    /** 审方任务状态字面量：驳回（重提路径触发复位重开） */
    private static final String STATUS_REJECTED = "REJECTED";

    private final OrderMedicationMapper medicationMapper;

    private final ReviewTaskMapper taskMapper;

    /** 应用事件发布器（决策面事务内发布审方回执，AFTER_COMMIT 出线），非空 */
    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import）。
     *
     * @param medicationMapper 用药快照 mapper，非空
     * @param taskMapper       审方任务 mapper（三态 CAS 通道），非空
     * @param events           应用事件发布器，非空
     */
    public MedicationReviewServiceImpl(
            OrderMedicationMapper medicationMapper, ReviewTaskMapper taskMapper, ApplicationEventPublisher events) {
        this.medicationMapper = medicationMapper;
        this.taskMapper = taskMapper;
        this.events = events;
    }

    /**
     * 医嘱开立消费落库：按 m04_order_no 存在性分流——首投落两表（快照 + PENDING 任务，uk
     * 兜底并发双投）；已存在按任务状态幂等收敛（PENDING/APPROVED 跳过；REJECTED 重提闭环）。
     */
    @Override
    @Transactional
    public void onOrderCreated(String m04OrderNo, String visitId, long patientId, String freqCode, String itemsJson) {
        // 数据库读操作：医嘱号存在性分流（uk_medication_order_no 语义的查询侧等价形态）
        OrderMedication existing = medicationMapper.selectOne(
                Wrappers.<OrderMedication>lambdaQuery().eq(OrderMedication::getM04OrderNo, m04OrderNo));
        if (existing != null) {
            handleRepublish(existing, itemsJson);
            return;
        }
        // 数据库写操作：快照 + 待审任务同事务落库（「重复消费仅一任务」由同事务原子性 + uk 双兜底）
        OrderMedication medication = new OrderMedication();
        medication.setM04OrderNo(m04OrderNo);
        medication.setVisitId(visitId);
        medication.setPatientId(patientId);
        medication.setFreqCode(freqCode);
        medication.setItems(itemsJson);
        medicationMapper.insert(medication);

        ReviewTask task = new ReviewTask();
        task.setOrderMedicationId(medication.getId());
        task.setStatus(STATUS_PENDING);
        taskMapper.insert(task);
        log.info("住院用药审方任务已生成：taskId={}，m04OrderNo={}，visitId={}", task.getId(), m04OrderNo, visitId);
    }

    /**
     * 重发收敛（同一 m04_order_no 的事件再投）：任务缺失或在审/已过审一律跳过（eventId 构件
     * 幂等之外的业务级「仅一任务」）；REJECTED 即 M04 重提闭环——刷新明细快照（重提可改方）
     * + 同任务 CAS 复位 PENDING（uk 一快照一任务，重开非新建）。
     *
     * @param medication 既有快照行，非空
     * @param itemsJson  本次事件的明细快照 JSON，非空
     */
    private void handleRepublish(OrderMedication medication, String itemsJson) {
        ReviewTask task = taskMapper.selectOne(
                Wrappers.<ReviewTask>lambdaQuery().eq(ReviewTask::getOrderMedicationId, medication.getId()));
        if (task == null || !STATUS_REJECTED.equals(task.getStatus())) {
            // 重复投递（eventId 已被构件幂等拦截，此处为业务级重发）或任务在审/已过审：仅一任务语义直接收敛
            log.info(
                    "审方任务幂等跳过（任务已存在非驳回态）：m04OrderNo={}，taskId={}，status={}",
                    medication.getM04OrderNo(),
                    task == null ? null : task.getId(),
                    task == null ? "任务行缺失（同事务原子性兜底，理论不可达）" : task.getStatus());
            return;
        }
        // 数据库写操作：重提明细快照刷新 + 任务复位重开（CAS 0 行=并发决策抢先，按现状收敛不上抛）
        medication.setItems(itemsJson);
        medicationMapper.updateById(medication);
        if (taskMapper.casReopen(task.getId()) != 1) {
            log.warn("审方任务重开并发被抢（他方已先复位/决策），按现状收敛：taskId={}", task.getId());
        }
        log.info("驳回后重提审方任务已重开：taskId={}，m04OrderNo={}", task.getId(), medication.getM04OrderNo());
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<ReviewTaskVO> list(String status, int page, int size) {
        // 数据库读操作：工作台分页（0 基转 MP 1 基 current，先到先审 id 升序）
        Page<ReviewTask> result = taskMapper.selectPage(new Page<>(page + 1, size), buildListWrapper(status));
        if (result.getRecords().isEmpty()) {
            return PageResult.of(List.of(), page, size, 0);
        }
        // 一跳批量装配快照（免行级 N+1；uk 一任务一快照，映射必然命中）
        Map<Long, OrderMedication> medications =
                medicationMapper
                        .selectByIds(result.getRecords().stream()
                                .map(ReviewTask::getOrderMedicationId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(OrderMedication::getId, Function.identity()));
        List<ReviewTaskVO> content = result.getRecords().stream()
                .map(task -> ReviewTaskVO.from(task, medications.get(task.getOrderMedicationId())))
                .toList();
        return PageResult.of(content, page, size, result.getTotal());
    }

    /**
     * 列表谓词组装（包级可见供单测对 getSqlSegment 做 contains 断言；status 非空等值过滤，
     * 空/空白不过滤全量；id 升序 FIFO——先到先审唯一顺序约束）。
     *
     * @param status 状态过滤 code，可空
     * @return 列表 wrapper，非空
     */
    Wrapper<ReviewTask> buildListWrapper(String status) {
        var wrapper = Wrappers.<ReviewTask>lambdaQuery();
        if (status != null && !status.isBlank()) {
            wrapper.eq(ReviewTask::getStatus, status);
        }
        return wrapper.orderByAsc(ReviewTask::getId);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void approve(long taskId, String opinion) {
        ReviewTask task = requireTask(taskId);
        OrderMedication medication = requireMedication(task);
        String operator = OperatorContextHolder.get();
        // 数据库写操作：PENDING→APPROVED CAS（APPROVED 再决/REJECTED 复位中/并发被抢 0 行同拒）
        if (taskMapper.casDecide(taskId, STATUS_APPROVED, operator, blankToNull(opinion), OffsetDateTime.now()) != 1) {
            throw new BizException(
                    PharmacyErrorCode.REVIEW_TASK_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "审方任务状态不允许通过（仅待审可决）：taskId=" + taskId);
        }
        // 事务内发应用事件（AFTER_COMMIT 出线）：审方通过回执（V800 id 53 冻结组件面）
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_MEDICATION_ORDER_AUDIT_COMPLETED,
                new MedicationAuditCompletedPayload(
                        medication.getM04OrderNo(), String.valueOf(taskId), operator, Instant.now())));
        log.info("住院医嘱审方通过：taskId={}，m04OrderNo={}，pharmacist={}", taskId, medication.getM04OrderNo(), operator);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void reject(long taskId, String opinion) {
        // 驳回必附药师意见（brief 冻结语义：缺意见 PH-1020——医生站重提修改依据，禁空驳）
        if (opinion == null || opinion.isBlank()) {
            throw new BizException(
                    PharmacyErrorCode.REVIEW_TASK_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "审方驳回必须附药师意见：taskId=" + taskId);
        }
        ReviewTask task = requireTask(taskId);
        OrderMedication medication = requireMedication(task);
        String operator = OperatorContextHolder.get();
        // 数据库写操作：PENDING→REJECTED CAS（0 行=非 PENDING/并发被抢）
        if (taskMapper.casDecide(taskId, STATUS_REJECTED, operator, opinion, OffsetDateTime.now()) != 1) {
            throw new BizException(
                    PharmacyErrorCode.REVIEW_TASK_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "审方任务状态不允许驳回（仅待审可决）：taskId=" + taskId);
        }
        // 事务内发应用事件：审方驳回回执（V800 id 54 冻结组件面，rejectReason 必附）
        events.publishEvent(new PharmacyDomainEvent(
                PharmacyMessagingConstants.EVENT_MEDICATION_ORDER_AUDIT_REJECTED,
                new MedicationAuditRejectedPayload(
                        medication.getM04OrderNo(), String.valueOf(taskId), opinion, operator, Instant.now())));
        log.info("住院医嘱审方驳回：taskId={}，m04OrderNo={}，pharmacist={}", taskId, medication.getM04OrderNo(), operator);
    }

    /**
     * 任务存在性守卫。
     *
     * @param taskId 任务 id，非空
     * @return 任务行，非空
     * @throws BizException PH-1019（404）任务不存在
     */
    private ReviewTask requireTask(long taskId) {
        ReviewTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(
                    PharmacyErrorCode.REVIEW_TASK_NOT_FOUND, HttpStatus.NOT_FOUND, "审方任务不存在：taskId=" + taskId);
        }
        return task;
    }

    /**
     * 快照存在性守卫（任务关联快照缺失=数据不一致，fail-closed 拒决策防空号回执）。
     *
     * @param task 任务行，非空
     * @return 快照行，非空
     * @throws BizException PH-1021（404）快照不存在
     */
    private OrderMedication requireMedication(ReviewTask task) {
        OrderMedication medication = medicationMapper.selectById(task.getOrderMedicationId());
        if (medication == null) {
            throw new BizException(
                    PharmacyErrorCode.MEDICATION_ORDER_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "住院用药快照不存在：taskId=" + task.getId() + "，orderMedicationId=" + task.getOrderMedicationId());
        }
        return medication;
    }

    /**
     * 空白意见归一（approve 场景可选意见：空白不入库，保持列 NULL 语义）。
     *
     * @param opinion 药师意见，可空
     * @return 归一后意见，可空
     */
    private static String blankToNull(String opinion) {
        return opinion == null || opinion.isBlank() ? null : opinion;
    }
}
