package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.SaveAlarmRuleRequest;
import com.fuyun.iot.dto.SimulateAlarmRequest;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.service.IAlarmRuleService;
import com.fuyun.iot.vo.AlarmRuleVO;
import com.fuyun.iot.vo.SimulateResultVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警规则服务实现（iot.iot_alarm_rule 唯一写入口）：规则 CRUD（软删）与 simulate 历史回放。
 *
 * <p>抖动防护②（FU-M14-08 五项抑制之②，brief 冻结）：THRESHOLD 规则缺指标编码/比较方向/阈值/
 * 持续时长/恢复带、OFFLINE 规则缺离线判定秒即 IOT-1013 409 拒保存——码位取语义贴切者（规则形态
 * 不满足其类型契约属规则状态违例词表），告警引擎评估侧由此假定参数齐备零防御分叉。
 *
 * <p>simulate 重放语义（与 AlarmEngine 线上状态机同构的纯内存形态）：按设备分组、时点升序逐行
 * 判定——越限且无回合起点 → 起算；越限且持续达标 → 记触发并回合归零；值恢复 → 回合复位。
 * 回放行单窗口上限 5000（LIMIT 硬顶，防大时段全表扫描），截断以 warn 留痕。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class AlarmRuleServiceImpl implements IAlarmRuleService {

    /** 静默窗口默认秒（brief 冻结默认值；请求未携带时补齐） */
    private static final int DEFAULT_SILENCE_WINDOW_SECS = 300;

    /** 升级时限默认秒（brief 冻结默认值；请求未携带时补齐） */
    private static final int DEFAULT_ESCALATE_AFTER_SECS = 300;

    /** simulate 回放行单窗口上限（LIMIT 硬顶） */
    private static final int SIMULATE_ROW_LIMIT = 5000;

    private final IotAlarmRuleMapper ruleMapper;

    private final IotTelemetryMapper telemetryMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param ruleMapper      告警规则 mapper，非空
     * @param telemetryMapper 遥测明细 mapper，非空；simulate 回放行读取通道
     */
    public AlarmRuleServiceImpl(IotAlarmRuleMapper ruleMapper, IotTelemetryMapper telemetryMapper) {
        this.ruleMapper = ruleMapper;
        this.telemetryMapper = telemetryMapper;
    }

    /**
     * 规则全量清单（只读事务）：id 升序稳定输出，软删行经 @TableLogic 自动过滤；规则为管理台
     * 配置面数据（百级量级），按 brief 口径不分页；返回含禁用规则，启用过滤由告警引擎评估侧
     * 按 enabled 判定。
     *
     * @return 全部在册规则 VO（id 升序），非空；无规则时为空列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<AlarmRuleVO> list() {
        // 数据库读操作：全量规则清单（id 升序稳定输出；@TableLogic 自动携带 deleted=0）
        return ruleMapper
                .selectList(Wrappers.<IotAlarmRuleEntity>lambdaQuery().orderByAsc(IotAlarmRuleEntity::getId))
                .stream()
                .map(AlarmRuleVO::from)
                .toList();
    }

    /**
     * 规则登记：抖动防护②前置校验（规则型参数条件必填，缺失即拒）→ 字段覆写（静默窗/升级时限
     * 缺省补齐默认 300s、enabled 缺省 true）→ 落行（雪花 id 由 MP ASSIGN_ID 生成）→ info 留痕。
     *
     * @param request 保存请求（名称/类型/条件参数/级别），非空；来源：管理台规则表单
     * @return 已登记规则 VO（含生成的 ruleId），非空
     * @throws BizException 规则形态不满足其类型契约（THRESHOLD/OFFLINE/DEVICE_ALARM 各自必填项
     *                      缺失，IOT-1013 409 拒保存——引擎评估侧假定参数齐备零防御分叉）
     */
    @Override
    @Transactional
    public AlarmRuleVO create(SaveAlarmRuleRequest request) {
        // 抖动防护②：规则型参数条件必填校验，缺失拒保存（引擎评估侧假定参数齐备）
        validateRuleShape(request);
        IotAlarmRuleEntity entity = new IotAlarmRuleEntity();
        applyRequest(entity, request);
        // 数据库写操作：规则落行（雪花 id 由 MP ASSIGN_ID 生成）
        ruleMapper.insert(entity);
        log.info(
                "告警规则已登记：ruleId={}，ruleName={}，ruleType={}，metricCode={}，alarmLevel={}",
                entity.getId(),
                entity.getRuleName(),
                entity.getRuleType(),
                entity.getMetricCode(),
                entity.getAlarmLevel());
        return AlarmRuleVO.from(entity);
    }

    /**
     * 规则更新：存在性校验（404）→ 抖动防护②校验（409）→ 字段全量覆写（updated_at 由数据库
     * 触发器维护，应用层不触碰审计列）→ info 留痕。
     *
     * @param id      规则行 id，非空；来源：管理台规则列表
     * @param request 保存请求，非空；来源：管理台规则表单
     * @return 更新后规则 VO，非空
     * @throws BizException 规则不存在（IOT-1012 404）或形态不满足其类型契约（IOT-1013 409）
     */
    @Override
    @Transactional
    public AlarmRuleVO update(Long id, SaveAlarmRuleRequest request) {
        IotAlarmRuleEntity entity = requireRule(id);
        validateRuleShape(request);
        applyRequest(entity, request);
        // 数据库写操作：字段全量覆写（updated_at 由数据库触发器维护，应用层不触碰审计列）
        ruleMapper.updateById(entity);
        log.info(
                "告警规则已更新：ruleId={}，ruleName={}，ruleType={}",
                entity.getId(),
                entity.getRuleName(),
                entity.getRuleType());
        return AlarmRuleVO.from(entity);
    }

    /**
     * 规则软删（@TableLogic 逻辑删）：历史告警行 rule_id 留痕不受影响，引擎评估侧后续按未删
     * 规则集加载自然失效；硬删仅经数据库运维通道。
     *
     * @param id 规则行 id，非空；来源：管理台规则列表
     * @throws BizException 规则不存在（IOT-1012 404）
     */
    @Override
    @Transactional
    public void delete(Long id) {
        IotAlarmRuleEntity entity = requireRule(id);
        // 数据库写操作：软删（@TableLogic 逻辑删，历史告警行 rule_id 留痕不受影响）
        ruleMapper.deleteById(entity.getId());
        log.info("告警规则已软删：ruleId={}，ruleName={}", entity.getId(), entity.getRuleName());
    }

    /**
     * 历史回放模拟（只读事务，纯内存不落库）：回放窗口内规则匹配遥测行按设备分组、时点升序
     * 逐行判定（与 AlarmEngine 线上状态机同构）——首越限起算、持续达标记触发并回合归零、值恢复
     * 复位；回放行单窗口 LIMIT 5000 硬顶（防大时段全表扫描），截断以 warn 留痕。
     *
     * @param id      规则行 id，非空；仅 THRESHOLD 规则支持回放（透传/离线源无可回放遥测行）
     * @param request 回放时窗（Instant，from 必须早于 to），非空；来源：管理台模拟表单
     * @return 回放结果（扫描行数 + 触发明细时点升序），非空；零触发时明细为空列表
     * @throws BizException 规则不存在（IOT-1012 404）/非 THRESHOLD 规则（IOT-1013 409）/
     *                      时窗非法（TELEMETRY_QUERY_INVALID 400）
     */
    @Override
    @Transactional(readOnly = true)
    public SimulateResultVO simulate(Long id, SimulateAlarmRequest request) {
        IotAlarmRuleEntity rule = requireRule(id);
        if (rule.getRuleType() != AlarmRuleType.THRESHOLD) {
            // 仅阈值规则可历史回放（透传源无遥测行、离线源无指标行可回放）
            log.warn("模拟拒绝：非 THRESHOLD 规则不支持历史回放：ruleId={}，ruleType={}", id, rule.getRuleType());
            throw new BizException(
                    IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅 THRESHOLD 规则支持历史回放模拟：ruleId=" + id);
        }
        if (!request.from().isBefore(request.to())) {
            // 时窗非法复用遥测查询参数码位（词表语义：时窗/档位非法 400）
            log.warn("模拟拒绝：回放时窗非法：ruleId={}，from={}，to={}", id, request.from(), request.to());
            throw new BizException(
                    IotErrorCode.TELEMETRY_QUERY_INVALID, HttpStatus.BAD_REQUEST, "回放时窗非法（from 必须早于 to）");
        }
        // 数据库读操作：回放窗口内规则匹配遥测行（时点升序重放；单窗口 LIMIT 硬顶防大时段扫描）
        List<IotTelemetryEntity> rows = telemetryMapper.selectList(Wrappers.<IotTelemetryEntity>lambdaQuery()
                .eq(IotTelemetryEntity::getMetricCode, rule.getMetricCode())
                .eq(rule.getDeviceId() != null, IotTelemetryEntity::getDeviceId, rule.getDeviceId())
                .ge(
                        IotTelemetryEntity::getOccurredAt,
                        OffsetDateTime.ofInstant(request.from(), java.time.ZoneOffset.UTC))
                .le(IotTelemetryEntity::getOccurredAt, OffsetDateTime.ofInstant(request.to(), java.time.ZoneOffset.UTC))
                .orderByAsc(IotTelemetryEntity::getOccurredAt)
                .last("LIMIT " + SIMULATE_ROW_LIMIT));
        if (rows.size() >= SIMULATE_ROW_LIMIT) {
            log.warn("模拟回放触发单窗口行数上限截断：ruleId={}，limit={}", id, SIMULATE_ROW_LIMIT);
        }
        List<SimulateResultVO.SimulateTrigger> triggers = replayThreshold(rule, rows);
        log.info("模拟回放完成（不落库）：ruleId={}，scannedRows={}，triggers={}", id, rows.size(), triggers.size());
        return new SimulateResultVO(rows.size(), triggers);
    }

    /**
     * 规则存在性校验（404 借承 IOT-1012，消息区分规则行场景）。
     *
     * @param id 规则行 id，非空
     * @return 规则实体，非空
     */
    private IotAlarmRuleEntity requireRule(Long id) {
        IotAlarmRuleEntity entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BizException(IotErrorCode.ALARM_RULE_NOT_FOUND, HttpStatus.NOT_FOUND, "告警规则不存在：" + id);
        }
        return entity;
    }

    /**
     * 抖动防护②：规则型参数按类型条件必填校验，缺失即 IOT-1013 409 拒保存。
     *
     * @param request 保存请求，非空
     */
    private static void validateRuleShape(SaveAlarmRuleRequest request) {
        if (request.ruleType() == AlarmRuleType.THRESHOLD) {
            boolean shapeValid = isNotBlank(request.metricCode())
                    && request.compareOp() != null
                    && request.thresholdValue() != null
                    && request.durationSecs() != null
                    && request.durationSecs() > 0
                    && request.recoveryBand() != null;
            if (!shapeValid) {
                throw new BizException(
                        IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "THRESHOLD 规则必须同时配置指标编码/比较方向/阈值/持续时长与恢复带（抖动防护②拒保存）");
            }
            return;
        }
        if (request.ruleType() == AlarmRuleType.OFFLINE
                && (request.offlineSecs() == null || request.offlineSecs() <= 0)) {
            throw new BizException(
                    IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "OFFLINE 规则必须配置离线判定秒 offlineSecs（抖动防护②拒保存）");
        }
        if (request.ruleType() == AlarmRuleType.DEVICE_ALARM && !isNotBlank(request.metricCode())) {
            throw new BizException(
                    IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "DEVICE_ALARM 规则必须配置告警关联编码 metricCode（抖动防护②拒保存）");
        }
    }

    /**
     * 请求字段全量覆写到实体（登记/更新共用；缺省默认值在登记侧补齐，更新侧保持缺省补齐同口径）。
     *
     * @param entity  目标实体（登记为新行/更新为既有行），非空
     * @param request 保存请求，非空
     */
    private static void applyRequest(IotAlarmRuleEntity entity, SaveAlarmRuleRequest request) {
        entity.setRuleName(request.ruleName());
        entity.setRuleType(request.ruleType());
        entity.setDeviceId(request.deviceId());
        entity.setMetricCode(request.metricCode());
        entity.setCompareOp(request.compareOp());
        entity.setThresholdValue(request.thresholdValue());
        entity.setDurationSecs(request.durationSecs());
        entity.setRecoveryBand(request.recoveryBand());
        entity.setSilenceWindowSecs(
                request.silenceWindowSecs() == null ? DEFAULT_SILENCE_WINDOW_SECS : request.silenceWindowSecs());
        entity.setOfflineSecs(request.offlineSecs());
        entity.setAlarmLevel(request.alarmLevel());
        entity.setEscalateAfterSecs(
                request.escalateAfterSecs() == null ? DEFAULT_ESCALATE_AFTER_SECS : request.escalateAfterSecs());
        entity.setEnabled(request.enabled() == null ? Boolean.TRUE : request.enabled());
    }

    /**
     * 越限回合状态机回放（与 AlarmEngine 线上语义同构，纯内存）：按设备分组逐行判定，持续达标
     * 即记触发并回合归零；值恢复复位回合。
     *
     * @param rule 阈值规则，非空
     * @param rows 回放窗口内规则匹配遥测行（时点升序），非空
     * @return 触发明细（时点升序），非空
     */
    private static List<SimulateResultVO.SimulateTrigger> replayThreshold(
            IotAlarmRuleEntity rule, List<IotTelemetryEntity> rows) {
        // 设备分组保持时点升序（LinkedHashMap 保插入序；分组内天然升序）
        Map<String, List<IotTelemetryEntity>> rowsByDevice = new LinkedHashMap<>();
        for (IotTelemetryEntity row : rows) {
            if (row.getValue() == null) {
                // 非数值行不进回放（与线上阈值评估同口径）
                continue;
            }
            rowsByDevice
                    .computeIfAbsent(row.getDeviceId(), key -> new ArrayList<>())
                    .add(row);
        }
        List<SimulateResultVO.SimulateTrigger> triggers = new ArrayList<>();
        for (Map.Entry<String, List<IotTelemetryEntity>> entry : rowsByDevice.entrySet()) {
            Instant episodeStart = null;
            for (IotTelemetryEntity row : entry.getValue()) {
                if (!isBeyondThreshold(rule, row.getValue())) {
                    // 值恢复：回合复位
                    episodeStart = null;
                    continue;
                }
                Instant occurredAt = row.getOccurredAt().toInstant();
                if (episodeStart == null) {
                    // 首越限：回合起算
                    episodeStart = occurredAt;
                    continue;
                }
                int durationSecs = rule.getDurationSecs() == null ? 0 : rule.getDurationSecs();
                if (Duration.between(episodeStart, occurredAt).getSeconds() >= durationSecs) {
                    // 持续达标：记触发并回合归零（下回合重新起算）
                    triggers.add(new SimulateResultVO.SimulateTrigger(
                            entry.getKey(),
                            rule.getMetricCode(),
                            occurredAt,
                            row.getValue().toPlainString(),
                            rule.getAlarmLevel()));
                    episodeStart = null;
                }
            }
        }
        return triggers;
    }

    /**
     * 越限判定（与 AlarmEngine 同口径）：GT=值&gt;阈值；LT=值&lt;阈值。
     *
     * @param rule  阈值规则，非空
     * @param value 采集值，非空
     * @return true=越限
     */
    private static boolean isBeyondThreshold(IotAlarmRuleEntity rule, BigDecimal value) {
        if (rule.getCompareOp() == null || rule.getThresholdValue() == null) {
            return false;
        }
        int compared = value.compareTo(rule.getThresholdValue());
        return rule.getCompareOp() == ThresholdOp.GT ? compared > 0 : compared < 0;
    }

    /** 空白判定（null 或全空白）。 */
    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
