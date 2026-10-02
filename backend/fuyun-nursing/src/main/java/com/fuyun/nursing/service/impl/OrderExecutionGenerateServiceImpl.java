package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.enums.ExecutionStatus;
import com.fuyun.nursing.enums.ExecutionType;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import com.fuyun.nursing.service.IOrderExecutionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行单生成域服务实现（V1106 order_execution，Task 4 三方法）：inpatient 医嘱事件族消费
 * 落单面——转抄临时单（类型快照+临时单一次落成）、计划批量单（快照过滤+逐 planNo 幂等落库）、
 * 终态撤销（未执行三态批量 CANCELLED CAS）。幂等红线：planNo 重复撞 uk_execution_plan 经
 * ON CONFLICT DO NOTHING 零副作用（数据库硬防重优先于先查后插——无查插间隙竞态）；乱序防御：
 * 计划先于转抄到达（快照缺行）warn+跳过。时区红线：计划时点=planDate+HH:mm 北京钟面组合
 * （TimeConstants.HEALTHCARE_TZ），禁裸 now()/容器时区。生成域不发布领域事件（order-plan
 * 消费侧，GC8 单向）。线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class OrderExecutionGenerateServiceImpl extends ServiceImpl<OrderExecutionMapper, OrderExecution>
        implements IOrderExecutionService {

    /** 系统链路等无登录上下文场景的操作者回退值（与 V1106 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 类型过滤集（GC18）：输血/手术/检查类转抄零落单——执行载体归输血核对单/手术/检查申请流程 */
    private static final Set<String> FILTERED_TRANSFER_TYPES = Set.of("blood", "surgery", "exam");

    private final NursingSeqGate seqGate;

    private final NursingWardPatientMapper wardPatientMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param executionMapper   执行单 mapper，非空；ServiceImpl 基座 mapper
     * @param seqGate           业务单号发号器，非空；执行单号 EX 段统一取号出口
     * @param wardPatientMapper 病区患者投影 mapper，非空；生成时归属（ward_id/bed_no）回填取数面
     */
    public OrderExecutionGenerateServiceImpl(
            OrderExecutionMapper executionMapper, NursingSeqGate seqGate, NursingWardPatientMapper wardPatientMapper) {
        this.seqGate = seqGate;
        this.wardPatientMapper = wardPatientMapper;
    }

    /**
     * 转抄临时单生成：守卫链见接口注。类型快照与临时单一次落成——行既是临时医嘱的执行载体，
     * 也是长期医嘱后续计划生成的类型快照锚（计划载荷不携类型）。
     *
     * @param m04OrderNo    M04 医嘱号，非空；来源：事件载荷
     * @param visitId       住院就诊号，非空；来源：事件载荷
     * @param patientId     患者主索引，正数；来源：事件载荷
     * @param transferType  转抄医嘱类型子键，非空；来源：事件载荷
     * @param transferredAt 转抄时点，非空；来源：事件载荷 firstTransferredAt
     */
    @Override
    @Transactional
    public void onOrderTransferred(
            String m04OrderNo, String visitId, long patientId, String transferType, Instant transferredAt) {
        // 类型过滤红线（GC18）：blood/surgery/exam 三类直接返回零落单（不触投影/取号）
        if (FILTERED_TRANSFER_TYPES.contains(transferType)) {
            log.info("转抄类型过滤零落单：m04OrderNo={}，visitId={}，transferType={}", m04OrderNo, visitId, transferType);
            return;
        }
        NursingWardPatient projection = wardProjectionOf(visitId);
        if (projection == null) {
            // ward_id NOT NULL 无值可落：跳过而非空值落单（归属错误比缺单更难纠，等入科/登记面补齐）
            log.warn("转抄落单跳过（病区患者投影缺行）：m04OrderNo={}，visitId={}，transferType={}", m04OrderNo, visitId, transferType);
            return;
        }
        OrderExecution row = baseRow(m04OrderNo, visitId, patientId, projection);
        row.setExecutionNo(seqGate.nextNo("EX"));
        // 临时单无计划号：m04_plan_no NULL 不受 uk_execution_plan 约束（PG 唯一索引对 NULL 互异）
        row.setM04PlanNo(null);
        // exec_item 快照占位契约：itemCode=m04 单号、itemName=类型子键（计划生成的类型过滤依据；
        // 真实项目明细待 Task 6 摆药签收衔接从 dispense 行回填）
        row.setExecItemCode(m04OrderNo);
        row.setExecItemName(transferType);
        row.setPlanTime(OffsetDateTime.ofInstant(transferredAt, TimeConstants.HEALTHCARE_TZ));
        // 数据库写操作：临时单幂等落库（临时行不走 uk_execution_plan 冲突面，重复转抄由前置幂等拦截）
        int inserted = baseMapper.insertIgnorePlanConflict(row);
        log.info(
                "转抄临时执行单生成：executionNo={}，m04OrderNo={}，visitId={}，wardId={}，transferType={}，inserted={}",
                row.getExecutionNo(),
                m04OrderNo,
                visitId,
                row.getWardId(),
                transferType,
                inserted);
    }

    /**
     * 计划批量生成：守卫链见接口注。逐 planNo 独立幂等落库——单条撞唯一索引不影响同批其余单。
     *
     * @param m04OrderNo M04 医嘱号，非空；来源：事件载荷
     * @param visitId    住院就诊号，非空；来源：事件载荷
     * @param patientId  患者主索引，正数；来源：事件载荷
     * @param planDate   计划日期，非空；来源：事件载荷
     * @param planNos    计划号集，非空；来源：事件载荷（与 planTimes 下标对齐）
     * @param planTimes  计划时点集，非空；来源：事件载荷（与 planNos 下标对齐）
     */
    @Override
    @Transactional
    public void onPlanGenerated(
            String m04OrderNo,
            String visitId,
            long patientId,
            LocalDate planDate,
            List<String> planNos,
            List<String> planTimes) {
        // 乱序防御：类型快照行（m04_plan_no NULL 的转抄载体）缺行=计划先于转抄到达，warn+跳过
        // （同面覆盖类型过滤零落单的 blood/surgery/exam——两类均为无快照行，跳过语义一致）
        OrderExecution snapshot = this.lambdaQuery()
                .eq(OrderExecution::getM04OrderNo, m04OrderNo)
                .isNull(OrderExecution::getM04PlanNo)
                .one();
        if (snapshot == null) {
            log.warn(
                    "计划生成乱序防御（类型快照缺行，跳过）：m04OrderNo={}，visitId={}，planDate={}，planNos={}",
                    m04OrderNo,
                    visitId,
                    planDate,
                    planNos);
            return;
        }
        // 空批守卫：事件契约空数组合法（无计划面），零循环零负担直返
        if (planNos.isEmpty()) {
            log.info("计划批量生成空批直返：m04OrderNo={}，visitId={}，planDate={}", m04OrderNo, visitId, planDate);
            return;
        }
        NursingWardPatient projection = wardProjectionOf(visitId);
        if (projection == null) {
            log.warn("计划落单跳过（病区患者投影缺行）：m04OrderNo={}，visitId={}，planDate={}", m04OrderNo, visitId, planDate);
            return;
        }
        int inserted = 0;
        int skipped = 0;
        for (int i = 0; i < planNos.size(); i++) {
            OrderExecution row = baseRow(m04OrderNo, visitId, patientId, projection);
            row.setExecutionNo(seqGate.nextNo("EX"));
            row.setM04PlanNo(planNos.get(i));
            // 快照占位字段继承转抄快照行（itemCode=医嘱号占位/itemName=类型快照），保持同医嘱同占位面
            row.setExecItemCode(snapshot.getExecItemCode());
            row.setExecItemName(snapshot.getExecItemName());
            row.setPlanTime(planTimeOf(planDate, planTimes.get(i)));
            // 数据库写操作：计划单幂等落库（撞 uk_execution_plan → ON CONFLICT DO NOTHING 0 行零副作用）
            if (baseMapper.insertIgnorePlanConflict(row) == 1) {
                inserted++;
            } else {
                skipped++;
            }
        }
        log.info(
                "计划批量执行单生成：m04OrderNo={}，visitId={}，planDate={}，生成={}，重复跳过={}",
                m04OrderNo,
                visitId,
                planDate,
                inserted,
                skipped);
    }

    /**
     * 医嘱终态撤销：停嘱/作废共用撤销 CAS 面，kind 仅日志语义区分。影响行数为日志口径，
     * 0 行幂等达成不构成失败（消费面禁因零行重试）。
     *
     * @param m04OrderNo M04 医嘱号，非空；来源：事件载荷
     * @param reason     撤销原因，非空；来源：事件载荷
     * @param kind       终态类型，非空
     */
    @Override
    @Transactional
    public void onOrderTerminal(String m04OrderNo, String reason, TerminalKind kind) {
        // 数据库写操作：未执行三态批量撤销 CAS（EXECUTING 不动——长期停嘱由 M04 计划侧联动）
        int rows = baseMapper.casCancelBatch(m04OrderNo, reason, operator());
        log.info("医嘱终态执行单撤销：m04OrderNo={}，kind={}，reason={}，撤销行数={}（EXECUTING 不动）", m04OrderNo, kind, reason, rows);
    }

    /**
     * 计划时点组合（时区红线）：计划日 + HH:mm 二十四小时制按北京钟面合成——M04 分解侧
     * 落库同为北京钟面口径，写读一致；禁容器时区漂移（UTC 容器会把 08:00 组合错偏 8 小时）。
     *
     * @param planDate 计划日期，非空
     * @param planTime 计划时点文本（HH:mm），非空
     * @return 北京钟面 OffsetDateTime 计划执行时刻，非空
     * @throws IllegalStateException planTime 文本非法（非 HH:mm 形态——上游契约违约帧，死信留痕）
     */
    private static OffsetDateTime planTimeOf(LocalDate planDate, String planTime) {
        try {
            return planDate.atTime(LocalTime.parse(planTime))
                    .atZone(TimeConstants.HEALTHCARE_TZ)
                    .toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "计划时点文本非法（非 HH:mm 二十四小时制）：planDate=" + planDate + "，planTime=" + planTime, e);
        }
    }

    /**
     * 生成行基件（两路共用）：归属自投影回填 + 生成默认面（GENERIC/CREATED/审计列）。
     *
     * @param m04OrderNo M04 医嘱号，非空
     * @param visitId    住院就诊号，非空
     * @param patientId  患者主索引，非空
     * @param projection 病区患者投影行（归属回填来源），非空
     * @return 已置公共列的执行单行，非空（executionNo/m04PlanNo/execItem/planTime 由调用路补齐）
     */
    private OrderExecution baseRow(String m04OrderNo, String visitId, long patientId, NursingWardPatient projection) {
        String operator = operator();
        OrderExecution row = new OrderExecution();
        row.setM04OrderNo(m04OrderNo);
        row.setVisitId(visitId);
        row.setPatientId(patientId);
        row.setWardId(projection.getWardId());
        row.setBedNo(projection.getBedNo());
        // 生成一律 GENERIC：M04 载荷无用法字段，静脉判定归 dispense 回填时点升格 INFUSION（Task 6）
        row.setExecutionType(ExecutionType.GENERIC.getCode());
        row.setStatus(ExecutionStatus.CREATED.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        return row;
    }

    /**
     * 病区患者投影行查询（归属回填取数面）：按 visitId 单行（uk_ward_patient_visit WHERE
     * deleted=0 唯一）；在区谓词由逻辑删承载（V1108 后无 status 列）。
     *
     * @param visitId 住院就诊号，非空
     * @return 在区投影行；缺行返回 null（调用方 warn+跳过，不以空值落单）
     */
    private NursingWardPatient wardProjectionOf(String visitId) {
        return wardPatientMapper.selectOne(
                Wrappers.lambdaQuery(NursingWardPatient.class).eq(NursingWardPatient::getVisitId, visitId));
    }

    /** 操作者取值（消费链路无登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
