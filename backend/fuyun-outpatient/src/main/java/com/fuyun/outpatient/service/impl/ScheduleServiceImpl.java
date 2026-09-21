package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.api.ScheduleStoppedPayload;
import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.ScheduleGenerateRequest;
import com.fuyun.outpatient.dto.SchedulePageQuery;
import com.fuyun.outpatient.dto.ScheduleTemplateSaveRequest;
import com.fuyun.outpatient.entity.ApptNumberPool;
import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.ScheduleTemplate;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.ScheduleStatus;
import com.fuyun.outpatient.enums.SessionType;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.ApptNumberPoolMapper;
import com.fuyun.outpatient.mapper.ScheduleMapper;
import com.fuyun.outpatient.mapper.ScheduleTemplateMapper;
import com.fuyun.outpatient.service.IScheduleService;
import com.fuyun.outpatient.vo.NumberPoolVO;
import com.fuyun.outpatient.vo.ScheduleTemplateVO;
import com.fuyun.outpatient.vo.ScheduleVO;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排班与号源池服务实现（M03 FU-M03-01，Task 4 号源池域写路径唯一入口）：放号生成（模板
 * week_pattern 位串×日期区间展开，窗口内已存在排班键预过滤幂等跳过、并发 uk 冲突整批回滚，
 * 池键预热）、停诊（schedule CAS+整池联动+schedule.stopped 事务内发布，经 OutpatientEventPublisher
 * AFTER_COMMIT 出 MQ——事务内禁 MQ 发送红线 A.4.2-7）、恢复（过期排班拒绝）、加号（total_quota
 * 增量 CAS+池键快路径同步 INCRBY）与余量查询。资金无涉红线（裁决 7）：本类零金额字段。线程安全：
 * 无状态单例。装配归 OutpatientWebConfig
 *
 * @Import；com.fuyun.outpatient.service.impl 包 = JaCoCo PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class ScheduleServiceImpl implements IScheduleService {

    /** 模板启用态词表值（status 列 ACTIVE/STOPPED；Task 4 无停用端点，字符串承载防魔法值散落） */
    private static final String TEMPLATE_STATUS_ACTIVE = "ACTIVE";

    /** 渠道配额 JSON 缺省值（线上/窗口/自助/预留百分比，与 V200 列默认逐字同源；P1 仅 PORTAL/WINDOW 通道计数） */
    private static final String DEFAULT_CHANNEL_QUOTA = "{\"PORTAL\":60,\"WINDOW\":30,\"KIOSK\":5,\"RESERVED\":5}";

    /** 单次加号数量上限（超出判 OP-1019；契约 @Max(50) 与服务端双层校验） */
    private static final int EXTRA_QUOTA_MAX = 50;

    /** 池键 TTL 锚点时刻：排班日次日 02:00（Redis↔池行每日对账窗口缓冲，A.5-1） */
    private static final LocalTime POOL_KEY_TTL_ANCHOR = LocalTime.of(2, 0);

    private final ScheduleTemplateMapper scheduleTemplateMapper;

    private final ScheduleMapper scheduleMapper;

    private final ApptNumberPoolMapper apptNumberPoolMapper;

    private final ApplicationEventPublisher events;

    private final PoolRedisGate poolRedisGate;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param scheduleTemplateMapper 排班模板 mapper，非空；模板登记/更新/清单/放号取母本
     * @param scheduleMapper         排班日历 mapper，非空；放号插入/清单/状态 CAS
     * @param apptNumberPoolMapper   号源池行 mapper，非空；池行插入/整池联动/加号 CAS/余量查询
     * @param events                 Spring 应用事件发布器，非空；事务内发布 schedule.stopped
     * @param poolRedisGate          号源 Redis 预扣闸，非空；放号池键预热 prime
     */
    public ScheduleServiceImpl(
            ScheduleTemplateMapper scheduleTemplateMapper,
            ScheduleMapper scheduleMapper,
            ApptNumberPoolMapper apptNumberPoolMapper,
            ApplicationEventPublisher events,
            PoolRedisGate poolRedisGate) {
        this.scheduleTemplateMapper = scheduleTemplateMapper;
        this.scheduleMapper = scheduleMapper;
        this.apptNumberPoolMapper = apptNumberPoolMapper;
        this.events = events;
        this.poolRedisGate = poolRedisGate;
    }

    @Override
    @Transactional
    public ScheduleTemplateVO saveTemplate(ScheduleTemplateSaveRequest request) {
        // 号段起止倒挂显式拒绝（OP-1019）：库端 CHECK 为最终防线，入参面先行给出可读 400
        if (!request.slotStart().isBefore(request.slotEnd())) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "号段起止非法：slotStart 须早于 slotEnd");
        }
        ScheduleTemplate template = new ScheduleTemplate();
        template.setDeptCode(request.deptCode());
        template.setDoctorId(request.doctorId());
        template.setEffFrom(request.effFrom());
        template.setEffTo(request.effTo());
        template.setWeekPattern(request.weekPattern());
        template.setSession(request.session());
        template.setApptType(request.apptType());
        template.setSlotStart(request.slotStart());
        template.setSlotEnd(request.slotEnd());
        template.setSlotQuota(request.slotQuota());
        template.setRoom(request.room());
        template.setReleaseDays(request.releaseDays());
        template.setReleaseTime(request.releaseTime());
        String operator = OperatorContextHolder.get();
        if (request.id() == null) {
            template.setStatus(TEMPLATE_STATUS_ACTIVE);
            template.setCreatedBy(operator);
            template.setUpdatedBy(operator);
            scheduleTemplateMapper.insert(template);
            log.info(
                    "排班模板已创建：templateId={}，deptCode={}，doctorId={}，weekPattern={}",
                    template.getId(),
                    template.getDeptCode(),
                    template.getDoctorId(),
                    template.getWeekPattern());
        } else {
            // 更新语义=按 id 全量覆盖请求面字段；0 行=模板不存在（OP-1004，无模板专属错误码）
            template.setId(request.id());
            template.setUpdatedBy(operator);
            int updated = scheduleTemplateMapper.updateById(template);
            if (updated == 0) {
                throw new BizException(
                        OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "排班模板不存在或已删除：templateId=" + request.id());
            }
            log.info(
                    "排班模板已更新：templateId={}，deptCode={}，doctorId={}",
                    template.getId(),
                    template.getDeptCode(),
                    template.getDoctorId());
        }
        return toTemplateVO(template);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ScheduleTemplateVO> listTemplates(int page, int size) {
        // API 契约 page 0 基（A.3-6），MP Page current 1 基且 <1 归一为 1——入参 +1 平移、出参回显 0 基
        Page<ScheduleTemplate> result = scheduleTemplateMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<ScheduleTemplate>lambdaQuery().orderByAsc(ScheduleTemplate::getId));
        return PageResult.of(
                result.getRecords().stream().map(this::toTemplateVO).toList(), page, size, result.getTotal());
    }

    @Override
    @Transactional
    public int generate(ScheduleGenerateRequest request) {
        // T+N 放号窗口：[endDate-(days-1), endDate]（endDate=窗口截止日）
        LocalDate end = request.endDate();
        LocalDate start = end.minusDays(request.days() - 1L);
        // 仅 ACTIVE 模板参与放号展开（停用模板不生成新排班）
        List<ScheduleTemplate> templates = scheduleTemplateMapper.selectList(
                Wrappers.<ScheduleTemplate>lambdaQuery().eq(ScheduleTemplate::getStatus, TEMPLATE_STATUS_ACTIVE));
        // 放号幂等预过滤：循环前一次取回窗口内已存在排班键——PG 事务 aborted 语义下任一语句失败即
        // 整事务拒续，禁止循环内「捕获 uk 冲突后继续」（重放场景必须在本循环前完成零插入跳过）
        Set<String> existingKeys = scheduleMapper
                .selectList(Wrappers.<Schedule>lambdaQuery()
                        .select(Schedule::getTemplateId, Schedule::getSchedDate, Schedule::getSession)
                        .ge(Schedule::getSchedDate, start)
                        .le(Schedule::getSchedDate, end))
                .stream()
                .map(s -> scheduleKey(s.getTemplateId(), s.getSchedDate(), s.getSession()))
                .collect(Collectors.toSet());
        String operator = OperatorContextHolder.get();
        int generated = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            for (ScheduleTemplate template : templates) {
                if (!matchesWeekAndValidity(template, date)) {
                    continue;
                }
                if (existingKeys.contains(scheduleKey(template.getId(), date, template.getSession()))) {
                    // uk_schedule 命中=同模板同日同时段已生成：预过滤幂等跳过（warn 留痕），不中断整批
                    log.warn(
                            "放号幂等跳过已存在排班：templateId={}，schedDate={}，session={}",
                            template.getId(),
                            date,
                            template.getSession());
                    continue;
                }
                Schedule schedule = new Schedule();
                schedule.setTemplateId(template.getId());
                schedule.setSchedDate(date);
                schedule.setSession(template.getSession());
                schedule.setDeptCode(template.getDeptCode());
                schedule.setDoctorId(template.getDoctorId());
                schedule.setApptType(template.getApptType());
                schedule.setTotalQuota(template.getSlotQuota());
                schedule.setUsedQuota(0);
                schedule.setRoom(template.getRoom());
                schedule.setCreatedBy(operator);
                schedule.setUpdatedBy(operator);
                try {
                    // 数据库写操作：排班日历行落库；此处 uk 冲突=预过滤后的并发放号抢先落行（窗口外竞态）
                    scheduleMapper.insert(schedule);
                } catch (DuplicateKeyException e) {
                    // PG 事务 aborted 语义：冲突后本事务后续语句全部拒续，不得继续循环——抛业务异常
                    // 整批回滚（@Transactional 生效），调用方重试即走预过滤幂等路径
                    log.warn(
                            "放号并发冲突整批回滚：templateId={}，schedDate={}，session={}",
                            template.getId(),
                            date,
                            template.getSession());
                    throw new BizException(
                            OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                            HttpStatus.CONFLICT,
                            "放号并发冲突，整批已回滚请重试：schedDate=" + date);
                }
                ApptNumberPool pool = new ApptNumberPool();
                pool.setScheduleId(schedule.getId());
                pool.setApptType(template.getApptType());
                pool.setSlotStart(template.getSlotStart());
                pool.setSlotEnd(template.getSlotEnd());
                pool.setTotalQuota(template.getSlotQuota());
                pool.setChannelQuota(DEFAULT_CHANNEL_QUOTA);
                pool.setCreatedBy(operator);
                pool.setUpdatedBy(operator);
                // 数据库写操作：号源池行落库（used_count/version 走库默认 0）
                apptNumberPoolMapper.insert(pool);
                // 缓存写操作：池键预热 SET total+TTL（预约抢号第一道闸自本调用起生效）
                poolRedisGate.prime(pool.getId(), pool.getTotalQuota(), poolKeyTtl(date));
                generated++;
            }
        }
        log.info("放号生成完成：endDate={}，days={}，模板数={}，生成排班={}", end, request.days(), templates.size(), generated);
        return generated;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ScheduleVO> listSchedules(SchedulePageQuery query) {
        // API 契约 page 0 基（A.3-6），MP Page current 1 基且 <1 归一为 1——入参 +1 平移、出参回显 0 基
        Page<Schedule> result = scheduleMapper.selectPage(
                new Page<>(query.page() + 1L, query.size()),
                Wrappers.<Schedule>lambdaQuery()
                        .eq(
                                query.deptCode() != null && !query.deptCode().isBlank(),
                                Schedule::getDeptCode,
                                query.deptCode())
                        .ge(query.dateFrom() != null, Schedule::getSchedDate, query.dateFrom())
                        .le(query.dateTo() != null, Schedule::getSchedDate, query.dateTo())
                        // 双列排序保证跨页唯一顺序（A.4.3-17）：同日多科室/多时段行序稳定
                        .orderByAsc(Schedule::getSchedDate)
                        .orderByAsc(Schedule::getId));
        return PageResult.of(
                result.getRecords().stream().map(this::toScheduleVO).toList(),
                query.page(),
                query.size(),
                result.getTotal());
    }

    @Override
    @Transactional
    public void stop(long scheduleId, String reason) {
        // 状态 CAS 先行：0 行=排班不存在或并发已停/状态违例，统一 OP-1004（成功后行必存在，读回仅取事件载荷）
        String operator = OperatorContextHolder.get();
        int updated = scheduleMapper.casStatus(
                scheduleId, ScheduleStatus.NORMAL.getCode(), ScheduleStatus.STOPPED.getCode(), operator);
        if (updated == 0) {
            throw new BizException(
                    OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "排班状态不允许停诊（不存在或已停诊）：scheduleId=" + scheduleId);
        }
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        // 整池联动：ACTIVE 池行批量 STOPPED（影响行数=停用池行数，日志留痕）
        int pools = apptNumberPoolMapper.markStoppedByScheduleId(scheduleId, operator);
        // 事务内发布应用事件（AFTER_COMMIT 出 MQ，A.4.2-7 事务红线）：已约患者改期/退费联动依据
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_SCHEDULE_STOPPED,
                new ScheduleStoppedPayload(
                        scheduleId,
                        schedule.getSchedDate().format(DateTimeFormatter.BASIC_ISO_DATE),
                        schedule.getDeptCode(),
                        schedule.getDoctorId(),
                        reason)));
        log.info(
                "停诊完成：scheduleId={}，schedDate={}，deptCode={}，doctorId={}，停用池行={}，operator={}",
                scheduleId,
                schedule.getSchedDate(),
                schedule.getDeptCode(),
                schedule.getDoctorId(),
                pools,
                operator);
    }

    @Override
    @Transactional
    public void resume(long scheduleId) {
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        // 过期排班不可恢复（sched_date 早于当日）：号源已无业务价值，恢复面仅对未来排班开放
        if (schedule == null || schedule.getSchedDate().isBefore(LocalDate.now())) {
            throw new BizException(
                    OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "排班不存在或已过期不可恢复：scheduleId=" + scheduleId);
        }
        String operator = OperatorContextHolder.get();
        int updated = scheduleMapper.casStatus(
                scheduleId, ScheduleStatus.STOPPED.getCode(), ScheduleStatus.NORMAL.getCode(), operator);
        if (updated == 0) {
            throw new BizException(
                    OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "排班状态不允许恢复（非停诊态）：scheduleId=" + scheduleId);
        }
        // 整池联动：STOPPED 池行批量迁回 ACTIVE（恢复无事件面，schedule.stopped 仅停诊方向发布）
        int pools = apptNumberPoolMapper.markActiveByScheduleId(scheduleId, operator);
        log.info(
                "停诊恢复完成：scheduleId={}，schedDate={}，deptCode={}，doctorId={}，恢复池行={}，operator={}",
                scheduleId,
                schedule.getSchedDate(),
                schedule.getDeptCode(),
                schedule.getDoctorId(),
                pools,
                operator);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NumberPoolVO> availablePools(String deptCode, LocalDate date, ApptType apptType) {
        // ACTIVE 且 used_count<total_quota 谓词由注解 SQL selectAvailable 承载，slot_start 升序
        List<ApptNumberPool> pools =
                apptNumberPoolMapper.selectAvailable(deptCode, date, apptType == null ? null : apptType.getCode());
        return pools.stream()
                .map(pool -> new NumberPoolVO(
                        pool.getId(),
                        pool.getScheduleId(),
                        pool.getApptType(),
                        pool.getSlotStart(),
                        pool.getSlotEnd(),
                        pool.getTotalQuota(),
                        pool.getUsedCount(),
                        pool.getTotalQuota() - pool.getUsedCount()))
                .toList();
    }

    @Override
    @Transactional
    public void extraQuota(long poolId, int count) {
        // 加号数量显式格式校验（W-22⑦ 口径）：契约 @Min(1)/@Max(50) 之外服务端再守一层
        if (count < 1 || count > EXTRA_QUOTA_MAX) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "加号数量须在 1~" + EXTRA_QUOTA_MAX + " 之间：" + count);
        }
        // 加号授权=total_quota 增量（CAS 条件更新，非 ACTIVE/不存在判 OP-1002）；加号占用计数走 extra_used（Task 5 挂号消费）
        int updated = apptNumberPoolMapper.casAddExtraQuota(poolId, count);
        if (updated == 0) {
            throw new BizException(
                    OutpatientErrorCode.POOL_NOT_FOUND, HttpStatus.NOT_FOUND, "号源池不存在或已停用：poolId=" + poolId);
        }
        // 快路径同步推进第一道闸（R1 修复，计划全局口径：预约侧余量不足即判 OP-1003 拒绝、不降级
        // ——池键滞后会造成真实可约号被误拒）：INCRBY 加号量并续期 TTL；键缺失由脚本原样跳过不造凭空键
        refreshPoolKeyAfterExtraQuota(poolId, count);
        log.info("加号完成：poolId={}，加号数量={}", poolId, count);
    }

    /**
     * 加号后池键同步刷新：INCRBY 加号量并续期 TTL（sched_date 次日 02:00 对账窗口锚，A.5-1）。
     * Redis 异常不回滚加号（库为权威库存，A.5-2 一致性红线口径）：error 留痕交日对账兜底收敛。
     *
     * @param poolId 池行主键；来源：加号端点路径参数（CAS 已命中，行必存在）
     * @param count  加号数量；来源：已过 1~50 守卫的端点入参
     */
    private void refreshPoolKeyAfterExtraQuota(long poolId, int count) {
        ApptNumberPool pool = apptNumberPoolMapper.selectById(poolId);
        Schedule schedule = scheduleMapper.selectById(pool.getScheduleId());
        try {
            int remain =
                    poolRedisGate.increase(poolId, count, pool.getTotalQuota(), poolKeyTtl(schedule.getSchedDate()));
            log.info("加号池键已刷新：poolId={}，增量={}，池键余量={}（-1=键缺失已跳过，待放号 prime 全量预热）", poolId, count, remain);
        } catch (DataAccessException e) {
            log.error("加号后池键刷新失败（日对账兜底）：poolId={}，加号数量={}，原因={}", poolId, count, e.getMessage(), e);
        }
    }

    /**
     * 模板×日期匹配判定：生效窗口含当日（eff_from &lt;= date &lt;= eff_to，eff_to 空=长期）且
     * week_pattern 对应星期位为 1。week_pattern 位序周一~周日与 DayOfWeek（周一=1~周日=7）对齐。
     *
     * @param template 排班模板，非空
     * @param date     候选排班日期，非空
     * @return true=该日应生成排班与池行
     */
    private boolean matchesWeekAndValidity(ScheduleTemplate template, LocalDate date) {
        if (template.getEffFrom().isAfter(date)
                || (template.getEffTo() != null && template.getEffTo().isBefore(date))) {
            return false;
        }
        return template.getWeekPattern().charAt(date.getDayOfWeek().getValue() - 1) == '1';
    }

    /**
     * 排班唯一键文本（templateId|schedDate|session，与 uk_schedule 三列同构）：放号幂等预过滤的
     * 判重键——集合 contains 判定替代循环内捕获冲突（PG 事务 aborted 语义禁循环续跑）。
     *
     * @param templateId 模板 id，非空
     * @param schedDate  排班日期，非空
     * @param session    门诊时段，非空
     * @return 判重键文本，非空
     */
    private String scheduleKey(Long templateId, LocalDate schedDate, SessionType session) {
        return templateId + "|" + schedDate + "|" + session;
    }

    /**
     * 池键 TTL 计算：排班日次日 02:00 对账窗口缓冲（A.5-1）——覆盖当日全业务时段并预留对账余量，
     * 过期键自然消除（禁无过期键）。
     *
     * @param schedDate 排班日期，非空
     * @return 距锚点时刻的时长（放号窗口面向未来，恒为正）
     */
    private Duration poolKeyTtl(LocalDate schedDate) {
        return Duration.between(LocalDateTime.now(), schedDate.plusDays(1).atTime(POOL_KEY_TTL_ANCHOR));
    }

    /**
     * 实体 → 模板出参投影。
     *
     * @param template 排班模板实体，非空
     * @return 模板出参，非空
     */
    private ScheduleTemplateVO toTemplateVO(ScheduleTemplate template) {
        return new ScheduleTemplateVO(
                template.getId(),
                template.getDeptCode(),
                template.getDoctorId(),
                template.getEffFrom(),
                template.getEffTo(),
                template.getWeekPattern(),
                template.getSession(),
                template.getApptType(),
                template.getSlotStart(),
                template.getSlotEnd(),
                template.getSlotQuota(),
                template.getRoom(),
                template.getReleaseDays(),
                template.getReleaseTime(),
                template.getStatus());
    }

    /**
     * 实体 → 排班出参投影。
     *
     * @param schedule 排班日历实体，非空
     * @return 排班出参，非空
     */
    private ScheduleVO toScheduleVO(Schedule schedule) {
        return new ScheduleVO(
                schedule.getId(),
                schedule.getTemplateId(),
                schedule.getSchedDate(),
                schedule.getSession(),
                schedule.getDeptCode(),
                schedule.getDoctorId(),
                schedule.getApptType(),
                schedule.getTotalQuota(),
                schedule.getUsedQuota(),
                schedule.getRoom(),
                schedule.getStatus(),
                schedule.getStopReason());
    }
}
