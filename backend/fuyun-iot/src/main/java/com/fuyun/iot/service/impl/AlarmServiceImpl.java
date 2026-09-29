package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.AlarmClosedPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.AlarmQueryRequest;
import com.fuyun.iot.dto.CloseAlarmRequest;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.service.IAlarmService;
import com.fuyun.iot.vo.AlarmVO;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警服务实现（iot.iot_alarm 生命周期写入口）：分页查询与人工处置（确认/关闭 CAS）。
 *
 * <p>错误码借承申报（MetricDictServiceImpl 先例，词表顺延前唯一 404/409 码位复用）：告警行
 * 不存在借承 IOT-1012（字面语义为告警规则不存在）、状态机违例借承 IOT-1013——消息显式区分
 * 告警行场景，如需专属码位走顺延提案修订词表。
 *
 * <p>关闭事件时机：CAS 成功后在<b>同一写事务</b>内 publishEvent（IotDomainPublisher AFTER_COMMIT
 * 出 MQ，宪法 A.4.2-7 事务内禁直发 MQ）；确认无事件（三事件族不含 acknowledged）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 *
 * <p>主表配对（宪法 A.4.3-20，EX-09 收拢）：extends ServiceImpl 声明主表 iot_alarm 继承面
 * （baseMapper 由容器注入基类字段）；既有构造器注入的 alarmMapper 与其并存，方法体维持原
 * mapper 通道（含 casAcknowledge/casClose 定制 CAS）不变，收拢完成态由后续演进消化。
 */
@Slf4j
public class AlarmServiceImpl extends ServiceImpl<IotAlarmMapper, IotAlarmEntity> implements IAlarmService {

    /** 审计留痕系统操作人（无登录上下文回退值，与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final IotAlarmMapper alarmMapper;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param alarmMapper 告警行 mapper，非空；查询与 CAS 通道
     * @param events      Spring 事件发布器，非空；关闭事件事务内发布入口
     */
    public AlarmServiceImpl(IotAlarmMapper alarmMapper, ApplicationEventPublisher events) {
        this.alarmMapper = alarmMapper;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AlarmVO> page(AlarmQueryRequest request) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：过滤分页（MP 分页 1 基 current 换算 0 基请求；idx_ward_status 准入，
        // @TableLogic 自动携带 deleted=0），last_triggered_at 降序稳定输出
        Page<IotAlarmEntity> result = alarmMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<IotAlarmEntity>lambdaQuery()
                        .eq(request.wardId() != null, IotAlarmEntity::getWardId, request.wardId())
                        .eq(request.alarmLevel() != null, IotAlarmEntity::getAlarmLevel, request.alarmLevel())
                        .eq(request.status() != null, IotAlarmEntity::getStatus, request.status())
                        .orderByDesc(IotAlarmEntity::getLastTriggeredAt));
        return PageResult.of(result.getRecords().stream().map(AlarmVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    @Transactional
    public AlarmVO acknowledge(String alarmNo) {
        IotAlarmEntity row = requireAlarm(alarmNo);
        // 数据库写操作：确认 CAS（仅 ACTIVE 可确认；并发双确认/终态 CAS 零行拒绝）
        if (alarmMapper.casAcknowledge(alarmNo, operator()) == 0) {
            log.warn("告警确认拒绝（仅 ACTIVE 可确认）：alarmNo={}，status={}", alarmNo, row.getStatus());
            throw new BizException(
                    IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅 ACTIVE 状态可确认（当前 " + row.getStatus() + "）：" + alarmNo);
        }
        log.info("告警已确认：alarmNo={}，operator={}", alarmNo, operator());
        return AlarmVO.from(requireAlarm(alarmNo));
    }

    @Override
    @Transactional
    public AlarmVO close(String alarmNo, CloseAlarmRequest request) {
        IotAlarmEntity row = requireAlarm(alarmNo);
        // 数据库写操作：关闭 CAS（ACTIVE/ACKNOWLEDGED → CLOSED 终态；已关闭幂等拒绝）
        if (alarmMapper.casClose(alarmNo, request.reason(), operator()) == 0) {
            log.warn("告警关闭拒绝（终态不可再关闭）：alarmNo={}，status={}", alarmNo, row.getStatus());
            throw new BizException(
                    IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅 ACTIVE/ACKNOWLEDGED 状态可关闭（当前 " + row.getStatus() + "）：" + alarmNo);
        }
        Instant now = Instant.now();
        // 消息发送：同一写事务内发布关闭事件（AFTER_COMMIT 出 MQ；回滚事务不发布）
        events.publishEvent(new IotDomainEvent(
                IotMessagingConstants.EVENT_ALARM_CLOSED,
                new AlarmClosedPayload(alarmNo, row.getDeviceId(), row.getWardId(), operator(), now, request.reason()),
                now,
                MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY)));
        log.info("告警已关闭并发布关闭事件：alarmNo={}，operator={}，reason={}", alarmNo, operator(), request.reason());
        return AlarmVO.from(requireAlarm(alarmNo));
    }

    /**
     * 告警行存在性校验（404 借承 IOT-1012，消息区分告警行场景——借承申报见类注释）。
     *
     * @param alarmNo 告警业务号，非空
     * @return 告警实体，非空
     */
    private IotAlarmEntity requireAlarm(String alarmNo) {
        // 数据库读操作：自然键 alarm_no 单查（@TableLogic 自动携带 deleted=0）
        IotAlarmEntity entity =
                alarmMapper.selectOne(Wrappers.<IotAlarmEntity>lambdaQuery().eq(IotAlarmEntity::getAlarmNo, alarmNo));
        if (entity == null) {
            throw new BizException(IotErrorCode.ALARM_RULE_NOT_FOUND, HttpStatus.NOT_FOUND, "告警不存在：" + alarmNo);
        }
        return entity;
    }

    /** 操作者取值（写路径审计留痕；无登录上下文回退 system，与审计列默认同源）。 */
    private static String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
