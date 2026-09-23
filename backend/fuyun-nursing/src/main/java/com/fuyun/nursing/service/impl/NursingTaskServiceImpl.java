package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.TaskCompletedPayload;
import com.fuyun.nursing.api.TaskCreatedPayload;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.NursingTaskCancelRequest;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.enums.TaskPriority;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.properties.NursingProperties;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 护理任务域服务实现（V805 nursing_task 业务面，任务最小载体）。创建七件套（code 校验 →
 * 补录红线 → 发号 → insert → created 事件）与完成/取消 CAS 终态流转（GC26 @Update 条件更新 +
 * 影响行数判定）+ completed 事件（事务内发布 AFTER_COMMIT 出站，GC8）；读时惰性逾期判定
 * （Spec :127 动作式逾期 + 偏差 3）：list/inFlightByVisit 对返回集内越阈值在途行执行
 * casMarkOverdue（overdue_flag=false 谓词仅首次递增），P1 不发布 nursing.task.overdue。
 * 业务时间服务器时间（GC25）；IN_PROGRESS 为 P1 声明态（无迁移入口，P2 任务工作台）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class NursingTaskServiceImpl extends ServiceImpl<NursingTaskMapper, NursingTask> implements INursingTaskService {

    /** 系统链路等无登录上下文场景的操作者回退值（与 V805 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 计划时间补录容忍窗（小时）：早于当前 24 小时以上定性历史任务补录，拒 NS-1016（本计划自增校验） */
    private static final int PLAN_TIME_TOLERANCE_HOURS = 24;

    private final NursingSeqGate seqGate;

    private final ApplicationEventPublisher events;

    private final NursingProperties properties;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param taskMapper 护理任务 mapper，非空；ServiceImpl 基座 mapper
     * @param seqGate    业务单号发号器，非空；任务号 TK 段统一取号出口
     * @param events     Spring 应用事件发布器，非空；事务内发布经 NursingEventPublisher
     *                   AFTER_COMMIT 出 MQ（GC8 红线，OutpatientEventPublisher 同款进程内桥）
     * @param properties 护理域参数，非空；taskOverdueMinutes 为逾期惰性判定阈值
     */
    public NursingTaskServiceImpl(
            NursingTaskMapper taskMapper,
            NursingSeqGate seqGate,
            ApplicationEventPublisher events,
            NursingProperties properties) {
        this.seqGate = seqGate;
        this.events = events;
        this.properties = properties;
    }

    /**
     * 护理任务创建（手工开立）：守卫链见接口注。计划时间补录红线（早于当前 24 小时以上
     * NS-1016）防历史任务倒灌逾期链；任务号唯一冲突兜底转 NS-1016 幂等拒绝（Task 4/5/6
     * 同口径，禁同事务重查）；created 事件载荷 taskNo/patientId/visitId/taskType/source。
     *
     * @param req 创建入参，非空；来源：操作者工作站表单 / Task 8 评估高危联动（服务面直调）
     * @return 任务出参（PENDING 态），非空
     * @throws BizException NS-1019（400 taskType/source/priority code 非法）/
     *                      NS-1016（409 计划时间补录越窗或任务号唯一冲突幂等拒绝）
     */
    @Override
    @Transactional
    public NursingTaskVO create(NursingTaskCreateRequest req) {
        // 守卫链①：任务类型 code 显式格式校验（禁裸值入库，W-22⑦ 先例）
        TaskType taskType = TaskType.fromCode(req.taskType());
        if (taskType == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "任务类型 code 非法：" + req.taskType());
        }
        // 守卫链②：来源/优先级 code 校验（空缺省 MANUAL/NORMAL，与 V805 列默认同源）
        TaskSource source = req.source() == null ? TaskSource.MANUAL : TaskSource.fromCode(req.source());
        if (source == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "任务来源 code 非法：" + req.source());
        }
        TaskPriority priority = req.priority() == null ? TaskPriority.NORMAL : TaskPriority.fromCode(req.priority());
        if (priority == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "优先级 code 非法：" + req.priority());
        }
        // 守卫链③：计划时间补录红线（早于当前 24 小时以上定性历史任务，禁倒灌逾期链）
        if (req.planTime().isBefore(OffsetDateTime.now().minusHours(PLAN_TIME_TOLERANCE_HOURS))) {
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "计划时间早于当前 24 小时以上，禁止补录历史任务：planTime=" + req.planTime());
        }
        String operator = operator();
        NursingTask row = new NursingTask();
        row.setTaskNo(seqGate.nextNo("TK"));
        row.setPatientId(req.patientId());
        row.setVisitId(req.visitId());
        row.setWardId(req.wardId());
        row.setBedNo(req.bedNo());
        row.setTaskType(taskType.getCode());
        row.setSource(source.getCode());
        row.setSourceRef(req.sourceRef());
        row.setPlanTime(req.planTime());
        row.setAssignedNurse(req.assignedNurse());
        row.setPriority(priority.getCode());
        row.setOverdueFlag(false);
        row.setEscalationCount(0);
        row.setStatus(TaskStatus.PENDING.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：任务落库（uk_nursing_task_no 部分唯一索引兜底防双写）
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 任务号唯一冲突兜底转业务拒绝（发号器异常回绕等极端并发场景，幂等拒绝不覆盖）
            throw new BizException(
                    NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "任务号唯一冲突（幂等拒绝）：taskNo=" + row.getTaskNo());
        }
        // 任务生成事件（事务内发布，AFTER_COMMIT 出站 GC8；M14 联动规则/M16 紧急呼叫幂等创建消费）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_TASK_CREATED,
                new TaskCreatedPayload(
                        row.getTaskNo(), row.getPatientId(), row.getVisitId(), row.getTaskType(), row.getSource())));
        log.info(
                "护理任务创建：taskNo={}，visitId={}，wardId={}，taskType={}，source={}，planTime={}，operator={}",
                row.getTaskNo(),
                row.getVisitId(),
                row.getWardId(),
                row.getTaskType(),
                row.getSource(),
                row.getPlanTime(),
                operator);
        return NursingTaskVO.from(row);
    }

    /**
     * 护理任务完成（PENDING/IN_PROGRESS → COMPLETED）：@Update CAS 单语句（GC26，0 行 →
     * NS-1011）→ 回读行数据 → 发布 nursing.task.completed（status=COMPLETED）。
     *
     * @param taskNo 任务业务号，非空；来源：路径参数
     * @return 完成后任务出参，非空
     * @throws BizException NS-1011（409 任务不存在或已终态，禁止完成）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    @Override
    @Transactional
    public NursingTaskVO complete(String taskNo) {
        String operator = operator();
        // 数据库写操作：完成 CAS（在途两态可完成；并发重复完成由行数判定兜底）
        if (baseMapper.casComplete(taskNo, operator) == 0) {
            throw new BizException(
                    NursingErrorCode.TASK_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "护理任务不存在或已终态，禁止完成：taskNo=" + taskNo);
        }
        NursingTask row = requireByTaskNo(taskNo);
        // 任务完成事件（事务内发布，AFTER_COMMIT 出站 GC8；消费方回写关联单据）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_TASK_COMPLETED,
                new TaskCompletedPayload(taskNo, TaskStatus.COMPLETED.getCode())));
        log.info("护理任务完成：taskNo={}，visitId={}，operator={}", taskNo, row.getVisitId(), operator);
        return NursingTaskVO.from(row);
    }

    /**
     * 护理任务取消（PENDING/IN_PROGRESS → CANCELLED）：取消原因强制留痕（空拒 NS-1019）→
     * CAS（0 行 → NS-1011）→ 回读 → 发布 nursing.task.completed（cancel 同发，status=CANCELLED）。
     *
     * @param taskNo 任务业务号，非空；来源：路径参数
     * @param req    取消入参（reason 必填留痕），非空；来源：操作者录入
     * @return 取消后任务出参，非空
     * @throws BizException NS-1019（400 取消原因为空）/ NS-1011（409 任务不存在或已终态，禁止取消）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    @Override
    @Transactional
    public NursingTaskVO cancel(String taskNo, NursingTaskCancelRequest req) {
        // 守卫链①：取消原因强制留痕（服务面校验覆盖模块内直调场景，Web 层由 @NotBlank 兜底）
        if (req.reason() == null || req.reason().isBlank()) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "取消原因不能为空：taskNo=" + taskNo);
        }
        String operator = operator();
        // 数据库写操作：取消 CAS（在途两态可取消；原因随 CAS 落 cancel_reason 留痕）
        if (baseMapper.casCancel(taskNo, req.reason(), operator) == 0) {
            throw new BizException(
                    NursingErrorCode.TASK_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "护理任务不存在或已终态，禁止取消：taskNo=" + taskNo);
        }
        NursingTask row = requireByTaskNo(taskNo);
        // 任务取消同发完成事件（终态广播单一事件字面量，载荷 status 区分完成/取消）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_TASK_COMPLETED,
                new TaskCompletedPayload(taskNo, TaskStatus.CANCELLED.getCode())));
        log.info("护理任务取消：taskNo={}，visitId={}，reason={}，operator={}", taskNo, row.getVisitId(), req.reason(), operator);
        return NursingTaskVO.from(row);
    }

    /**
     * 病区任务清单：wardId 必选，status/date 可选（当日窗口含头不含尾），DB 侧按计划时间升序；
     * 先查后标再返回——查询后对返回集内越阈值在途行做惰性逾期 CAS（单次置位递增），出参与库态一致。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @param status 状态过滤，可空（空=全状态）；来源：查询参数
     * @param date   计划日期过滤，可空（空=不限日期）；来源：查询参数
     * @return 任务出参清单（无行返回空清单，非 null）；按计划时间升序
     */
    @Override
    @Transactional
    public List<NursingTaskVO> list(String wardId, TaskStatus status, LocalDate date) {
        // 读路径含惰性逾期写（markOverdueLazily 触发 casMarkOverdue UPDATE），禁 readOnly——
        // PG 只读事务内 UPDATE 直接报错，且 readOnly 标记经 Spring 默认传播（REQUIRED）随外层事务生效
        LambdaQueryWrapper<NursingTask> wrapper =
                Wrappers.<NursingTask>lambdaQuery().eq(NursingTask::getWardId, wardId);
        if (status != null) {
            wrapper.eq(NursingTask::getStatus, status.getCode());
        }
        if (date != null) {
            // 当日窗口含头不含尾（TIMESTAMPTZ 按时刻比较，与体征/出入量查询同口径）
            wrapper.ge(NursingTask::getPlanTime, date.atStartOfDay())
                    .lt(NursingTask::getPlanTime, date.plusDays(1).atStartOfDay());
        }
        wrapper.orderByAsc(NursingTask::getPlanTime);
        // 数据库读操作：病区任务清单（计划时间升序；命中 idx_nursing_task_ward_status_plan；逻辑删自动过滤）
        List<NursingTask> rows = baseMapper.selectList(wrapper);
        markOverdueLazily(rows);
        return rows.stream().map(NursingTaskVO::from).toList();
    }

    /**
     * 患者在途任务清单（仅 PENDING/IN_PROGRESS，计划时间升序）：Task 9 交接班待续事项与
     * Task 3 详情卡「在途任务」段消费落点；先查后标再返回，惰性逾期 CAS 同 {@link #list}。
     *
     * @param visitId 住院就诊号，非空；来源：路径/载荷
     * @return 在途任务出参清单（无行返回空清单，非 null）；按计划时间升序
     */
    @Override
    @Transactional
    public List<NursingTaskVO> inFlightByVisit(String visitId) {
        // 读路径含惰性逾期写（markOverdueLazily 触发 casMarkOverdue UPDATE），禁 readOnly——
        // PG 只读事务内 UPDATE 直接报错，且 readOnly 标记经 Spring 默认传播（REQUIRED）随外层事务生效
        // 数据库读操作：在途任务（status IN 谓词滤除终态行；命中 idx_nursing_task_visit_status）
        List<NursingTask> rows = baseMapper.selectList(Wrappers.<NursingTask>lambdaQuery()
                .eq(NursingTask::getVisitId, visitId)
                .in(NursingTask::getStatus, TaskStatus.PENDING.getCode(), TaskStatus.IN_PROGRESS.getCode())
                .orderByAsc(NursingTask::getPlanTime));
        markOverdueLazily(rows);
        return rows.stream().map(NursingTaskVO::from).toList();
    }

    /**
     * 巡视打卡（Task 10 PDA 面落点）：建 PATROL 行直落 COMPLETED——assignedNurse=当前操作者、
     * planTime/completedAt=打卡时刻、sourceRef=扫码标识留痕（按标识形态分流，见
     * {@link #sourceRefTrail}：I 型腕带就诊编码原值，证件号/卡号形态落掩码值）。行生而终态
     * （未经历任务生命周期，不发 created/completed 事件——打卡记录语义，非任务流转）。
     *
     * @param patientId  患者主索引，非空；来源：PDA 标识解析（Task 10）
     * @param visitId    住院就诊号，非空；来源：PDA 标识解析（Task 10）
     * @param wardId     病区编码，非空；来源：PDA 当前登录病区（Task 10）
     * @param identifier 扫码标识（腕带就诊编码/就诊卡号/证件号三合一），非空；来源：PDA 扫码，
     *                   分流后落 sourceRef 留痕，明文禁入日志（identifierTail 摘要口径）
     * @return 打卡任务出参（COMPLETED 态），非空
     * @throws BizException NS-1016（409 任务号唯一冲突幂等拒绝——PDA 连点防重）
     */
    @Override
    @Transactional
    public NursingTaskVO patrol(long patientId, String visitId, String wardId, String identifier) {
        String operator = operator();
        // 打卡时刻一律服务器时间（GC25）：planTime=打卡时刻，生而终态无逾期判定语义
        OffsetDateTime patrolledAt = OffsetDateTime.now();
        NursingTask row = new NursingTask();
        row.setTaskNo(seqGate.nextNo("TK"));
        row.setPatientId(patientId);
        row.setVisitId(visitId);
        row.setWardId(wardId);
        row.setTaskType(TaskType.PATROL.getCode());
        row.setSource(TaskSource.MANUAL.getCode());
        // source_ref 留痕按标识形态分流：I 型腕带就诊编码（visitId 形态，非敏感）原值落库；
        // 证件号/就诊卡号形态属敏感字段，按「敏感字段应用层脱敏后落库」红线落掩码值
        row.setSourceRef(sourceRefTrail(identifier));
        row.setPlanTime(patrolledAt);
        row.setAssignedNurse(operator);
        row.setPriority(TaskPriority.NORMAL.getCode());
        row.setOverdueFlag(false);
        row.setEscalationCount(0);
        row.setStatus(TaskStatus.COMPLETED.getCode());
        row.setCompletedAt(patrolledAt);
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：打卡记录落库（uk_nursing_task_no 唯一索引兜底 PDA 连点防重）
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(
                    NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "任务号唯一冲突（幂等拒绝）：taskNo=" + row.getTaskNo());
        }
        // 日志脱敏：扫码标识按 identifierTail 摘要口径输出（与 PdaServiceImpl 同源），明文禁入日志
        log.info(
                "巡视打卡完成：taskNo={}，visitId={}，wardId={}，identifierTail={}，operator={}",
                row.getTaskNo(),
                visitId,
                wardId,
                identifierTail(identifier),
                operator);
        return NursingTaskVO.from(row);
    }

    /**
     * 巡视打卡标识留痕分流（source_ref 落库口径）：V805 列注释定义 source_ref 为「执行单号/
     * 告警号/规则号」类来源引用——I 型腕带就诊编码（CF-3 冻结结构，即 visitId 形态，非敏感）
     * 与该口径相容，原值留痕可追溯打卡介质；证件号/就诊卡号形态属敏感字段，按等保三级
     * 「敏感字段应用层加密（脱敏）后落库」红线落 identifierTail 掩码值，禁明文证件号落库。
     *
     * @param identifier 扫码标识明文，非空；来源：PDA 扫码（Task 10 三合一入口）
     * @return 可落 source_ref 的留痕值：I 型腕带码原值，其余形态为尾四位掩码值
     */
    private String sourceRefTrail(String identifier) {
        // I 型腕带就诊编码判定复用 patient api 冻结结构校验（类型码 I + 真实日历日期段，
        // 比 PdaServiceImpl 解析路由的宽松形态判定更严——宁掩码勿明文，误掩码仅损留痕信息量）
        if (VisitIdValidator.isValid(identifier)) {
            return identifier;
        }
        return identifierTail(identifier);
    }

    /**
     * 标识日志/留痕脱敏：仅保留后四位（≤4 位全星回退）——腕带/卡号/证件号明文禁入日志与
     * 敏感留痕（等保红线），口径与 PdaServiceImpl#identifierTail 同源。
     *
     * @param identifier 标识明文，可空（null 按全星处理）
     * @return 尾四位掩码文本（如 ****5678；≤4 位或 null 返回 ****）
     */
    private String identifierTail(String identifier) {
        if (identifier == null || identifier.length() <= 4) {
            return "****";
        }
        return "****" + identifier.substring(identifier.length() - 4);
    }

    /**
     * 读时惰性逾期判定（Spec :127 动作式逾期 + 偏差 3）：仅对返回集内 PENDING/IN_PROGRESS 且
     * 计划时间早于「当前时间 − taskOverdueMinutes」且未标记过的行执行 casMarkOverdue——
     * overdue_flag=false 谓词保证升级次数仅首次递增（读路径并发幂等）；CAS 命中后同步回写
     * 内存行，出参 VO 与库态一致。P1 不发布 nursing.task.overdue（V800 占位登记，发布随 P2
     * 延迟队列实装，禁在本方法私发）。
     *
     * @param rows 待判定的任务行清单（读路径返回集），非空；方法内按 CAS 结果原位回写
     */
    private void markOverdueLazily(List<NursingTask> rows) {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(properties.taskOverdueMinutes());
        for (NursingTask row : rows) {
            boolean active = TaskStatus.PENDING.getCode().equals(row.getStatus())
                    || TaskStatus.IN_PROGRESS.getCode().equals(row.getStatus());
            // 未标记 + 在途 + 越阈值三条件齐备才触发 CAS（已标记行零写触达）
            if (active
                    && !Boolean.TRUE.equals(row.getOverdueFlag())
                    && row.getPlanTime().isBefore(threshold)) {
                // 数据库写操作：逾期标记 CAS（overdue_flag=false 谓词仅首次递增，0 行=已被并发标记过）
                if (baseMapper.casMarkOverdue(row.getId()) == 1) {
                    row.setOverdueFlag(true);
                    row.setEscalationCount(row.getEscalationCount() + 1);
                }
            }
        }
    }

    /** 按任务号回读任务行（逻辑删由 @TableLogic 自动过滤；未命中定性 NS-1016）。 */
    private NursingTask requireByTaskNo(String taskNo) {
        NursingTask row =
                baseMapper.selectOne(Wrappers.<NursingTask>lambdaQuery().eq(NursingTask::getTaskNo, taskNo));
        if (row == null) {
            // CAS 与回读间被并发逻辑删的极端窗口：资源已不存在，禁继续出事件
            throw new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "护理任务不存在：taskNo=" + taskNo);
        }
        return row;
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
