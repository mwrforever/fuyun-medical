package com.fuyun.iot.internal.alarm;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 离线探测器（FU-M14-08 离线规则源）：ONLINE 设备 last_online_at 距今超规则 offline_secs 即
 * 数据断流候选（设备状态仍 ONLINE 但遥测停流——IoTDA 状态帧与遥测链路解耦的缝隙面）。
 *
 * <p>惰性扫描形态：无独立定时器，随 AlarmEngine.evaluate 调起（评估频度=遥测批次频度，覆盖
 * 断流发现需求）；每规则单次设备查询（规则量小、offline_secs 参数各异不合并，禁循环内单查
 * 红线以「每规则一批」口径申报——同规则内设备集合一次 SQL 条件承载）。候选单规则上限 200 行
 * （LIMIT 硬顶，防大面积断流时引擎评估风暴），截断以 warn 留痕。
 *
 * <p>无状态单例；装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 */
@Slf4j
public class OfflineDetector {

    /** 单规则候选上限：LIMIT 硬顶（防大面积断流触发告警风暴——抑制④的前置泄压） */
    static final int SCAN_LIMIT = 200;

    private final IotAlarmRuleMapper ruleMapper;

    private final IotDeviceMapper deviceMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param ruleMapper   告警规则 mapper，非空；OFFLINE 型启用规则装载通道
     * @param deviceMapper 设备档案 mapper，非空；断流候选设备查询通道
     */
    public OfflineDetector(IotAlarmRuleMapper ruleMapper, IotDeviceMapper deviceMapper) {
        this.ruleMapper = ruleMapper;
        this.deviceMapper = deviceMapper;
    }

    /**
     * 执行一轮断流候选探测：装载启用 OFFLINE 规则，逐规则按超时条件圈定候选设备
     * （状态 ONLINE + last_online_at 距今超 offline_secs + 规则指定设备号收窄）。
     *
     * @return 断流候选清单（规则与设备配对），非空；无规则或无候选为空清单
     */
    public List<Candidate> detect() {
        // 数据库读操作：OFFLINE 型启用规则单查（@TableLogic 自动携带 deleted=0）
        List<IotAlarmRuleEntity> rules = ruleMapper.selectList(Wrappers.<IotAlarmRuleEntity>lambdaQuery()
                .eq(IotAlarmRuleEntity::getRuleType, AlarmRuleType.OFFLINE)
                .eq(IotAlarmRuleEntity::getEnabled, true));
        if (rules.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        Instant now = Instant.now();
        for (IotAlarmRuleEntity rule : rules) {
            // 每规则一批设备查询（offline_secs 参数各异不合并；规则量小，A.4.3-14 以批级口径申报）
            Instant cutoff = now.minusSeconds(rule.getOfflineSecs() == null ? 0 : rule.getOfflineSecs());
            List<IotDeviceEntity> devices = deviceMapper.selectList(Wrappers.<IotDeviceEntity>lambdaQuery()
                    .eq(IotDeviceEntity::getStatus, DeviceStatus.ONLINE)
                    .lt(IotDeviceEntity::getLastOnlineAt, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                    .eq(rule.getDeviceId() != null, IotDeviceEntity::getDeviceId, rule.getDeviceId())
                    .last("LIMIT " + SCAN_LIMIT));
            if (devices.size() >= SCAN_LIMIT) {
                // 候选截断留痕：大面积断流场景引擎仅处置上限内候选（泄压防告警风暴）
                log.warn("离线候选触发单规则上限截断：ruleId={}，limit={}", rule.getId(), SCAN_LIMIT);
            }
            for (IotDeviceEntity device : devices) {
                candidates.add(new Candidate(rule, device));
            }
        }
        if (!candidates.isEmpty()) {
            log.info("离线探测完成：rules={}，candidates={}", rules.size(), candidates.size());
        }
        return candidates;
    }

    /**
     * 断流候选（规则与设备配对）：离线告警的触发输入。
     *
     * @param rule   命中的 OFFLINE 规则，非空
     * @param device 断流候选设备（ONLINE + last_online_at 超时），非空
     */
    public record Candidate(IotAlarmRuleEntity rule, IotDeviceEntity device) {}
}
