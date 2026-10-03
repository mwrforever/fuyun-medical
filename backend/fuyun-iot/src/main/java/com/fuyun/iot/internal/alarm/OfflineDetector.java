package com.fuyun.iot.internal.alarm;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 离线探测器（FU-M14-08 离线规则源）：ONLINE 设备 last_online_at 距今超规则 offline_secs 即
 * 数据断流候选（设备状态仍 ONLINE 但遥测停流——IoTDA 状态帧与遥测链路解耦的缝隙面）。
 *
 * <p>惰性扫描形态：无独立定时器，随 AlarmEngine.evaluate 调起（评估频度=遥测批次频度，覆盖
 * 断流发现需求）。W-60 收敛（P2 PR-3 Task 12）：原「逐规则循环单查」（每规则一条设备查询）
 * 收敛为<b>单条 UNION ALL 动态 SQL 一次触达</b>——每规则分支独立超时窗口与候选上限
 * （{@link IotDeviceMapper#selectOfflineCandidates}），Java 侧按 rule_id 分组配对、显式升序
 * 排序与截断判定，判定结果与逐规则查询逐语义等价（宪法 A.4.3-14 批量查询纪律）。候选单规则
 * 上限 200 行（LIMIT 硬顶，防大面积断流时引擎评估风暴），截断以 warn 留痕。
 *
 * <p><b>等价性申报（D-21，行为保持）</b>：①每分支谓词/窗口/上限与原逐规则查询逐字同构
 * （含 offlineSecs NULL 归零口径）；②组内升序排序改为显式（last_online_at 升序 + device_id
 * 次序钉死并列序——原 DB 排序对并列无契约，显式化非行为变更）；③截断 warn 判定同源
 * （分支行数 ≥ 上限即留痕）。判定基准时刻由原应用侧 Instant.now() 收敛为 SQL 语句时钟
 * （now() 同语句恒定、分支间一致；NTP 同步下秒级偏差相对分钟级离线阈值无语义影响）。
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
     * @param deviceMapper 设备档案 mapper，非空；断流候选单条动态扫描通道
     */
    public OfflineDetector(IotAlarmRuleMapper ruleMapper, IotDeviceMapper deviceMapper) {
        this.ruleMapper = ruleMapper;
        this.deviceMapper = deviceMapper;
    }

    /**
     * 执行一轮断流候选探测：装载启用 OFFLINE 规则 → 单条 UNION ALL 扫描一次取回各规则分支
     * 候选（状态 ONLINE + last_online_at 超规则窗口 + 规则指定设备号收窄，分支间互相独立）
     * → 按 rule_id 分组配对、组内显式升序与截断判定（等价语义见类注）。
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
        // 数据库读操作：单条 UNION ALL 动态扫描（W-60：一次触达替代逐规则循环单查；分支上限
        // 与截断判定同源传参，同设备命中多规则按分支重复出现——与逐规则逐次查询配对同构）
        List<IotDeviceMapper.OfflineCandidateRow> rows = deviceMapper.selectOfflineCandidates(rules, SCAN_LIMIT);
        // 按 rule_id 分组配对（分支标记列承载，不依赖行序）；规则迭代序保持装载序（候选清单
        // 组间顺序与原逐规则循环一致）
        Map<Long, List<IotDeviceMapper.OfflineCandidateRow>> rowsByRule =
                rows.stream().collect(Collectors.groupingBy(IotDeviceMapper.OfflineCandidateRow::getRuleId));
        List<Candidate> candidates = new ArrayList<>();
        for (IotAlarmRuleEntity rule : rules) {
            List<IotDeviceMapper.OfflineCandidateRow> group = rowsByRule.getOrDefault(rule.getId(), List.of());
            // 组内显式升序：断流（last_online_at 最小）最久者优先处置；device_id 次序钉死并列序
            // （UNION ALL 分支间输出序无契约保证，排序语义由本侧承载）
            List<IotDeviceMapper.OfflineCandidateRow> ordered = group.stream()
                    .sorted(Comparator.comparing(IotDeviceMapper.OfflineCandidateRow::getLastOnlineAt)
                            .thenComparing(IotDeviceMapper.OfflineCandidateRow::getDeviceId))
                    .toList();
            if (ordered.size() >= SCAN_LIMIT) {
                // 候选截断留痕：大面积断流场景引擎仅处置上限内候选（泄压防告警风暴）
                log.warn("离线候选触发单规则上限截断：ruleId={}，limit={}", rule.getId(), SCAN_LIMIT);
            }
            // LIMIT 硬顶双保险：分支行数受 SQL LIMIT 约束，此处 limit 截断为同源常量防线
            ordered.stream().limit(SCAN_LIMIT).forEach(row -> candidates.add(new Candidate(rule, row)));
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
     * @param device 断流候选设备（ONLINE + last_online_at 超时），非空；运行时为
     *               {@link IotDeviceMapper.OfflineCandidateRow}（档案全列 + 分支标记）
     */
    public record Candidate(IotAlarmRuleEntity rule, IotDeviceEntity device) {}
}
