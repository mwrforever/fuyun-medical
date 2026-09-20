package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.dto.PracticeGrantCreateRequest;
import com.fuyun.system.entity.PracticeGrant;
import com.fuyun.system.enums.PracticeGrantStatus;
import com.fuyun.system.internal.PracticeChangedEvent;
import com.fuyun.system.mapper.PracticeGrantMapper;
import com.fuyun.system.service.IPracticeService;
import com.fuyun.system.vo.PracticeCheckResponse;
import com.fuyun.system.vo.PracticeGrantVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执业授权校验与管理服务实现（M01 §7 practice/check + FU-M01-04 授权登记/停权/清单，V704
 * practice_grant 真实化）：check 按（employeeId, grantType）查 EFFECTIVE 授权并判定有效期
 * （响应契约自 P0 冻结不变）；管理方法为写路径——事务内发布 {@link PracticeChangedEvent}，
 * 由 SystemEventPublisher AFTER_COMMIT 监听在事务提交后才发 MQ（A.4.2-7 事务内禁消息发送）。
 * EXPIRED 过期为读侧派生态（无定时任务回写，PrivacyAuthServiceImpl.deriveStatus 先例）。
 * 授权类型词表：PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL
 * （全仓消费方逐字引用）。线程安全：无状态单例。装配归 SystemWebConfig @Import。
 */
@Slf4j
public class PracticeServiceImpl implements IPracticeService {

    private final PracticeGrantMapper practiceGrantMapper;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param practiceGrantMapper 执业授权 mapper，非空；生效查询/停权 CAS/登记插入
     * @param events              Spring 应用事件发布器，非空；事务内发布 practice.changed 触发事件
     */
    public PracticeServiceImpl(PracticeGrantMapper practiceGrantMapper, ApplicationEventPublisher events) {
        this.practiceGrantMapper = practiceGrantMapper;
        this.events = events;
    }

    /**
     * 执业授权校验：生效行命中判通过；未命中回查「EFFECTIVE 但已过期」行区分两态 reason（读侧
     * 派生，不回写库）。
     *
     * @param request 校验请求，非空；checkTime 可空（null 取服务端当前时刻）
     * @return 校验响应（入参回显 + passed + reason），非空；passed=true reason=执业授权有效：
     *         {grantType}；passed=false reason=授权已过期：{grantType} 或 无有效执业授权记录：{grantType}
     */
    @Override
    public PracticeCheckResponse check(PracticeCheckRequest request) {
        // 校验日期归一：checkTime 缺省取服务端当前时刻（有效期含当日语义：valid_from <= 今日 <= valid_to）
        LocalDate checkDate = (request.checkTime() != null ? request.checkTime() : OffsetDateTime.now()).toLocalDate();
        OffsetDateTime checkTime = request.checkTime() == null ? OffsetDateTime.now() : request.checkTime();
        PracticeGrant grant = practiceGrantMapper.selectEffective(request.employeeId(), request.grantType(), checkDate);
        if (grant != null) {
            log.info(
                    "执业授权校验通过：employeeId={}，grantType={}，checkDate={}",
                    request.employeeId(),
                    request.grantType(),
                    checkDate);
            return new PracticeCheckResponse(
                    String.valueOf(request.employeeId()),
                    request.grantType(),
                    checkTime,
                    true,
                    "执业授权有效：" + request.grantType());
        }
        // 未命中：回查 EFFECTIVE 但 valid_to 已过的行，区分「已过期」与「无记录」两态文案（读侧派生）
        PracticeGrant expired = practiceGrantMapper.selectOne(Wrappers.<PracticeGrant>lambdaQuery()
                .eq(PracticeGrant::getEmployeeId, request.employeeId())
                .eq(PracticeGrant::getGrantType, request.grantType())
                .eq(PracticeGrant::getStatus, PracticeGrantStatus.EFFECTIVE)
                .lt(PracticeGrant::getValidTo, checkDate)
                .last("LIMIT 1"));
        String reason = expired != null ? "授权已过期：" + request.grantType() : "无有效执业授权记录：" + request.grantType();
        log.info(
                "执业授权校验未通过：employeeId={}，grantType={}，checkDate={}，reason={}",
                request.employeeId(),
                request.grantType(),
                checkDate,
                reason);
        return new PracticeCheckResponse(
                String.valueOf(request.employeeId()), request.grantType(), checkTime, false, reason);
    }

    /**
     * 执业授权登记：落 EFFECTIVE 行并事务内发布 practice.changed（EFFECTIVE）。
     *
     * <p>重复 EFFECTIVE 冲突不做应用层预查——部分唯一索引 uk_practice_grant_active 为并发双登记
     * 的唯一权威，捕获唯一索引 violation 转 409 SYS-1022（库层兜底，A.5-2 一致性红线口径）。
     *
     * @param request 登记请求，非空；grantType 词表由 JSR-303 校验
     * @return 新登记授权行 ID（雪花 ID），非空
     * @throws BizException SYS-1022/409 同员工同类型已存在 EFFECTIVE 行
     */
    @Override
    @Transactional
    public long grant(PracticeGrantCreateRequest request) {
        PracticeGrant grant = new PracticeGrant();
        grant.setEmployeeId(request.employeeId());
        grant.setGrantType(request.grantType());
        grant.setLegalBasis(request.legalBasis());
        grant.setValidFrom(request.validFrom());
        grant.setValidTo(request.validTo());
        grant.setStatus(PracticeGrantStatus.EFFECTIVE);
        // 操作人应用层注入（A.4.2-9）；未登录态（上下文为空）置 null 由库默认 'system' 承接
        String operator = OperatorContextHolder.get();
        grant.setCreatedBy(operator);
        grant.setUpdatedBy(operator);
        try {
            practiceGrantMapper.insert(grant);
        } catch (DuplicateKeyException e) {
            log.warn("执业授权登记冲突（同员工同类型已有生效行）：employeeId={}，grantType={}", request.employeeId(), request.grantType());
            throw new BizException(SystemErrorCode.PRACTICE_GRANT_DUPLICATE, HttpStatus.CONFLICT, "同一员工同一授权类型已存在生效授权");
        }
        log.info(
                "执业授权登记完成：id={}，employeeId={}，grantType={}，validFrom={}，validTo={}",
                grant.getId(),
                request.employeeId(),
                request.grantType(),
                request.validFrom(),
                request.validTo());
        // 事务内仅发布 Spring 应用事件（B.3-1）：MQ 发送由 AFTER_COMMIT 监听承担，事务回滚则广播不出
        events.publishEvent(new PracticeChangedEvent(
                grant.getEmployeeId(), grant.getGrantType(), PracticeGrantStatus.EFFECTIVE.getCode()));
        return grant.getId();
    }

    /**
     * 执业授权停权：CAS 条件更新（仅 EFFECTIVE 可停）防并发双停与重复停权，成功后读回授权行
     * 发布 practice.changed（SUSPENDED）。reason 落 warn 日志留痕（状态迁移凭据归审计行）。
     *
     * @param id     授权行主键，非空
     * @param reason 停权理由（留痕），非空
     * @throws BizException SYS-1021/404 行不存在或已非 EFFECTIVE（并发已停/竞态落败同口径）
     */
    @Override
    @Transactional
    public void withdraw(long id, String reason) {
        String operator = OperatorContextHolder.get();
        // CAS 抢停权：影响行 0=行不存在或并发已停（竞态落败与 404 同口径拒绝，防重复停权事件）
        int updated = practiceGrantMapper.casWithdraw(id, operator);
        if (updated == 0) {
            log.warn("执业授权停权被拒（不存在或已非生效态）：id={}", id);
            throw new BizException(SystemErrorCode.PRACTICE_GRANT_NOT_FOUND, HttpStatus.NOT_FOUND, "执业授权记录不存在或已非生效状态");
        }
        PracticeGrant grant = practiceGrantMapper.selectById(id);
        log.warn(
                "执业授权停权完成：id={}，employeeId={}，grantType={}，operator={}，reason={}",
                id,
                grant == null ? null : grant.getEmployeeId(),
                grant == null ? null : grant.getGrantType(),
                operator,
                reason);
        // 事务内仅发布 Spring 应用事件：停权事实（SUSPENDED）经 AFTER_COMMIT 广播（V5 id 6 既有登记）
        if (grant != null) {
            events.publishEvent(new PracticeChangedEvent(
                    grant.getEmployeeId(), grant.getGrantType(), PracticeGrantStatus.SUSPENDED.getCode()));
        }
    }

    /**
     * 按员工查询授权清单（管理端展示）：valid_to 已过的 EFFECTIVE 行读侧派生 EXPIRED 展示，
     * 不回写库。
     *
     * @param employeeId 员工 ID，非空
     * @return 授权行展示清单（id 降序唯一序），无授权返回空清单，非空
     */
    @Override
    @Transactional(readOnly = true)
    public List<PracticeGrantVO> listByEmployee(long employeeId) {
        LocalDate today = LocalDate.now();
        // 逻辑删过滤由 @TableLogic 自动携带 deleted=0；id 降序保证清单唯一顺序（A.4.3-17）
        List<PracticeGrant> rows = practiceGrantMapper.selectList(Wrappers.<PracticeGrant>lambdaQuery()
                .eq(PracticeGrant::getEmployeeId, employeeId)
                .orderByDesc(PracticeGrant::getId));
        return rows.stream().map(row -> toVO(row, today)).toList();
    }

    /**
     * 实体转展示 VO：EFFECTIVE 且 valid_to 已过当日派生 EXPIRED 展示态（不回写库）。
     *
     * @param row   授权行，非空
     * @param today 展示基准日（服务端当日），非空
     * @return 展示 VO，非空
     */
    private PracticeGrantVO toVO(PracticeGrant row, LocalDate today) {
        boolean expired = row.getStatus() == PracticeGrantStatus.EFFECTIVE
                && row.getValidTo() != null
                && row.getValidTo().isBefore(today);
        String displayStatus = expired
                ? PracticeGrantStatus.EXPIRED.getCode()
                : row.getStatus().getCode();
        return new PracticeGrantVO(
                row.getId(),
                row.getEmployeeId(),
                row.getGrantType(),
                row.getLegalBasis(),
                row.getValidFrom(),
                row.getValidTo(),
                displayStatus,
                row.getApprovalRef());
    }
}
