package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.constants.NursingTimeConstants;
import com.fuyun.nursing.entity.NurseAssignment;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.AssignmentType;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.mapper.NurseAssignmentMapper;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.service.INurseBoardService;
import com.fuyun.nursing.vo.NurseBoardVO;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

/**
 * 护士站大屏快照服务实现（FU-M05-08，Task 11）：board 四段聚合 + Redis TTL 5s read-through
 * 缓存（{@code fy:nursing:snapshot:board:{wardId}}，fy:iot:snapshot:dashboard 同款形态——读/
 * 写/解析三分支失败均 warn 降级直算不阻断）。
 *
 * <p><b>四段聚合口径（brief 冻结）</b>：beds=病区在册投影行（床号/入科时点升序）× 当日有效
 * assignments（BED 型管床匹配优先、PRIMARY 型责任组按患者回退——nursing 无姓名解析面，
 * assigneeName 以 M01 用户标识出网；同床跨班次多行按班次字典序取首行，当班精化归后续版本）
 * × risk_flags 展示镜像；overdueTasks=overdue_flag=true 且 PENDING 在途任务（plan_time 升序
 * 有界 50 行）；admissions=近 24h 时间线（ADMIT=在册行 admitted_at，DISCHARGE=逻辑删行
 * updated_at 近似出院时点经 selectDischargedSince 绕过 @TableLogic 读取，时点降序有界）；
 * criticalValues=固定空数组（M07 检验危急值模块未建——缺位降级明示，勿删组件占位）。
 *
 * <p>业务时钟取北京钟面（NursingTimeConstants.HEALTHCARE_TZ——时区红线：当日有效窗口与
 * generatedAt 均按北京钟面承载，禁裸 now()）。无状态单例；装配归 NursingWebConfig @Import；
 * JaCoCo 核心包（nursing.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class NurseBoardServiceImpl implements INurseBoardService {

    /** 大屏快照键前缀（brief 冻结：fy:nursing:snapshot:board:{wardId}，GC13 命名） */
    static final String BOARD_SNAPSHOT_KEY_PREFIX = "fy:nursing:snapshot:board:";

    /** 大屏快照 TTL：5 秒（brief 冻结；大屏轮询频度下读路径不触库的口径） */
    static final Duration BOARD_SNAPSHOT_TTL = Duration.ofSeconds(5);

    /** 逾期任务清单行数上界（大屏有界纪律——iot 帧明细截断同族） */
    private static final int OVERDUE_TASKS_LIMIT = 50;

    /** 出入院时间线行数上界（合并 ADMIT/DISCHARGE 后统一截断） */
    private static final int ADMISSIONS_LIMIT = 50;

    /** 出入院动态时间窗：近 24 小时（brief 冻结） */
    private static final int ADMISSIONS_WINDOW_HOURS = 24;

    /** 分配生效当日窗口的班次字典序兜底上限（防御性上限——异常数据下不失控） */
    private static final int ASSIGNMENTS_LIMIT = 500;

    private final NursingWardPatientMapper wardPatientMapper;

    private final NurseAssignmentMapper assignmentMapper;

    private final NursingTaskMapper taskMapper;

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param wardPatientMapper 病区患者投影 mapper，非空；床位墙与出入院动态两段取数面
     * @param assignmentMapper  责任护士分配 mapper，非空；床位墙 assigneeName 取数面
     * @param taskMapper        护理任务 mapper，非空；逾期清单段取数面
     * @param redisTemplate     String 模板（禁 JDK 序列化），非空；快照缓存唯一通道
     * @param objectMapper      JSON 转换器，非空；快照序列化/反序列化（Boot 容器实例）
     */
    public NurseBoardServiceImpl(
            NursingWardPatientMapper wardPatientMapper,
            NurseAssignmentMapper assignmentMapper,
            NursingTaskMapper taskMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.wardPatientMapper = wardPatientMapper;
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 大屏快照聚合入口：缓存命中直返（读失败/损坏降级直算），穿透直算四段并回写缓存。
     */
    @Override
    public NurseBoardVO board(String wardId) {
        if (wardId == null || wardId.isBlank()) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "病区编码必填（wardId）");
        }
        // 读路径：缓存命中直返（缓存面可重建，缺席/损坏不阻断聚合主链）
        NurseBoardVO cached = readCachedBoard(wardId);
        if (cached != null) {
            return cached;
        }
        NurseBoardVO computed = aggregate(wardId);
        writeCachedBoard(wardId, computed);
        return computed;
    }

    /**
     * 四段直算聚合（无缓存）：床位墙×分配装配 + 逾期清单 + 近 24h 出入院时间线 + 危急值空段。
     *
     * @param wardId 病区编码，非空（调用前已守卫）
     * @return 大屏快照，非空
     */
    private NurseBoardVO aggregate(String wardId) {
        OffsetDateTime now = OffsetDateTime.now(NursingTimeConstants.HEALTHCARE_TZ);
        List<NurseBoardVO.BedRow> beds = aggregateBeds(wardId);
        List<NurseBoardVO.OverdueTaskRow> overdueTasks = aggregateOverdueTasks(wardId);
        List<NurseBoardVO.AdmissionRow> admissions = aggregateAdmissions(wardId, now);
        log.info(
                "护士站大屏快照聚合完成：wardId={}，beds={}，overdueTasks={}，admissions={}（criticalValues 恒空——M07 缺位注记）",
                wardId,
                beds.size(),
                overdueTasks.size(),
                admissions.size());
        // 危急值段固定空数组：M07 检验危急值模块未建，组件占位待回填（brief 冻结降级明示）
        return new NurseBoardVO(wardId, beds, overdueTasks, admissions, List.of(), now);
    }

    /**
     * 床位总览墙段聚合：在册投影行（床号/入科时点升序）× 当日有效分配（BED 型床位匹配优先、
     * PRIMARY 型患者回退）× 风险标记镜像。分配窗取北京钟面医疗日（时区红线）。
     *
     * @param wardId 病区编码，非空
     * @return 床位墙行清单（无在册行返回空清单），非空
     */
    private List<NurseBoardVO.BedRow> aggregateBeds(String wardId) {
        // 数据库读操作：在册投影行一览（床位序；逻辑删行与他病区行由条件排除）
        List<NursingWardPatient> rows = wardPatientMapper.selectList(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getWardId, wardId)
                .orderByAsc(NursingWardPatient::getBedNo)
                .orderByAsc(NursingWardPatient::getAdmittedAt));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> bedAssignee = new HashMap<>();
        Map<Long, String> patientAssignee = new HashMap<>();
        resolveAssignees(wardId, bedAssignee, patientAssignee);
        return rows.stream()
                .map(row -> new NurseBoardVO.BedRow(
                        row.getBedNo(),
                        row.getVisitId(),
                        row.getPatientId(),
                        row.getNursingLevel(),
                        row.getAdmittedAt(),
                        // 管床匹配优先，责任组按患者回退，均未指派为 null（未指派语义交前端承载）
                        bedAssignee.getOrDefault(row.getBedNo(), patientAssignee.get(row.getPatientId())),
                        row.getRiskFlags()))
                .toList();
    }

    /**
     * 当日有效分配装载与双维归并：BED 型按床号、PRIMARY 型按患者建映射（同维多行 putIfAbsent
     * 取首行——班次字典序排序下 DAY 班优先，跨班次多行的确定性口径）。
     *
     * @param wardId          病区编码，非空
     * @param bedAssignee     归并输出：床号 → 护士标识（BED 型），非空
     * @param patientAssignee 归并输出：患者主索引 → 护士标识（PRIMARY 型），非空
     */
    private void resolveAssignees(String wardId, Map<String, String> bedAssignee, Map<Long, String> patientAssignee) {
        LocalDate today = LocalDate.now(NursingTimeConstants.HEALTHCARE_TZ);
        // 数据库读操作：当日生效分配清单（ACTIVE + 有效窗口含今日；班次字典序承载同床多行确定性）
        List<NurseAssignment> assignments = assignmentMapper.selectList(Wrappers.<NurseAssignment>lambdaQuery()
                .eq(NurseAssignment::getWardId, wardId)
                .eq(NurseAssignment::getStatus, "ACTIVE")
                .le(NurseAssignment::getValidFrom, today)
                .and(w -> w.isNull(NurseAssignment::getValidTo).or().ge(NurseAssignment::getValidTo, today))
                .orderByAsc(NurseAssignment::getBedNo)
                .orderByAsc(NurseAssignment::getShiftCode)
                .last("LIMIT " + ASSIGNMENTS_LIMIT));
        for (NurseAssignment assignment : assignments) {
            if (AssignmentType.BED.getCode().equals(assignment.getAssignmentType()) && assignment.getBedNo() != null) {
                bedAssignee.putIfAbsent(assignment.getBedNo(), assignment.getNurseId());
            } else if (AssignmentType.PRIMARY.getCode().equals(assignment.getAssignmentType())
                    && assignment.getPatientId() != null) {
                patientAssignee.putIfAbsent(assignment.getPatientId(), assignment.getNurseId());
            }
        }
    }

    /**
     * 任务逾期清单段聚合：overdue_flag=true 且 PENDING 在途任务（brief 冻结过滤键——IN_PROGRESS
     * 逾期行不进大屏清单），计划时间升序有界 50 行。
     *
     * @param wardId 病区编码，非空
     * @return 逾期行清单，非空
     */
    private List<NurseBoardVO.OverdueTaskRow> aggregateOverdueTasks(String wardId) {
        // 数据库读操作：逾期在途任务扫描（升序有界，大屏看板纪律）
        List<NursingTask> overdue = taskMapper.selectList(Wrappers.<NursingTask>lambdaQuery()
                .eq(NursingTask::getWardId, wardId)
                .eq(NursingTask::getOverdueFlag, true)
                .eq(NursingTask::getStatus, TaskStatus.PENDING.getCode())
                .orderByAsc(NursingTask::getPlanTime)
                .last("LIMIT " + OVERDUE_TASKS_LIMIT));
        return overdue.stream()
                .map(row -> new NurseBoardVO.OverdueTaskRow(
                        row.getTaskNo(), row.getTaskType(), row.getPlanTime(), row.getEscalationCount()))
                .toList();
    }

    /**
     * 出入院动态段聚合：近 24h 时间线——ADMIT 取在册行 admitted_at，DISCHARGE 取逻辑删行
     * updated_at（近似出院时点，selectDischargedSince 绕过逻辑删过滤）；合并后时点降序截 50 行。
     *
     * @param wardId 病区编码，非空
     * @param now    聚合基准时钟（北京钟面），非空
     * @return 时间线行清单，非空
     */
    private List<NurseBoardVO.AdmissionRow> aggregateAdmissions(String wardId, OffsetDateTime now) {
        OffsetDateTime since = now.minusHours(ADMISSIONS_WINDOW_HOURS);
        List<NurseBoardVO.AdmissionRow> timeline = new ArrayList<>();
        // 数据库读操作：近时窗入科行（在册面，@TableLogic 自动过滤逻辑删行）
        List<NursingWardPatient> admitted = wardPatientMapper.selectList(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getWardId, wardId)
                .ge(NursingWardPatient::getAdmittedAt, since));
        for (NursingWardPatient row : admitted) {
            timeline.add(new NurseBoardVO.AdmissionRow(
                    row.getVisitId(), row.getBedNo(), row.getAdmittedAt(), NurseBoardVO.TYPE_ADMIT));
        }
        // 数据库读操作：近时窗出院行（逻辑删面，注解 SQL 显式 deleted=1 读取）
        for (NursingWardPatient row : wardPatientMapper.selectDischargedSince(wardId, since)) {
            timeline.add(new NurseBoardVO.AdmissionRow(
                    row.getVisitId(), row.getBedNo(), row.getUpdatedAt(), NurseBoardVO.TYPE_DISCHARGE));
        }
        // 时点降序（最近动态在前），空时点行殿后防御（admitted_at 理论非空，防御性 Comparator）
        timeline.sort(
                Comparator.comparing(NurseBoardVO.AdmissionRow::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return timeline.size() > ADMISSIONS_LIMIT ? List.copyOf(timeline.subList(0, ADMISSIONS_LIMIT)) : timeline;
    }

    /**
     * 读大屏快照缓存（String JSON → record）：键缺席返回 null；JSON 损坏/Redis 异常 warn 降级
     * 返回 null（直算承接，缓存可重建不阻断）。
     *
     * @param wardId 病区编码，非空
     * @return 缓存快照；缺席或降级为 null
     */
    private NurseBoardVO readCachedBoard(String wardId) {
        try {
            String json = redisTemplate.opsForValue().get(BOARD_SNAPSHOT_KEY_PREFIX + wardId);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, NurseBoardVO.class);
        } catch (JsonProcessingException e) {
            log.warn("大屏快照解析失败（降级直算覆盖）：wardId={}，原因={}", wardId, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("大屏快照读取失败（降级直算）：wardId={}，原因={}", wardId, e.getMessage());
            return null;
        }
    }

    /**
     * 写大屏快照缓存（String JSON + 显式 TTL 5s，禁无 TTL 键红线）：Redis 异常/序列化异常仅
     * warn 降级不阻断调用方主链（缓存可重建）。
     *
     * @param wardId 病区编码，非空
     * @param board  待缓存快照，非空
     */
    private void writeCachedBoard(String wardId, NurseBoardVO board) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            BOARD_SNAPSHOT_KEY_PREFIX + wardId,
                            objectMapper.writeValueAsString(board),
                            BOARD_SNAPSHOT_TTL);
        } catch (Exception e) {
            log.warn("大屏快照写入失败（缓存降级不影响主链）：wardId={}，原因={}", wardId, e.getMessage());
        }
    }
}
