package com.fuyun.ward.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.api.payload.TelemetryAnomalyPayload;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.service.IVitalSignBoardService;
import com.fuyun.ward.vo.VitalAnomalyVO;
import com.fuyun.ward.vo.VitalBoardVO;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 体征看板服务实现（FU-M16-02 编排视图）：床垫 presence 视图锚 + 采集质量注记
 * （TelemetryAnomalyEventListener 消费 iot.telemetry.anomaly 落 Redis 快照，本服务读时出注记）。
 *
 * <p><b>编排面缺位申报</b>：设备→病区映射归 iot 绑定档案（ward 禁跨模块读表，Port 仅 series
 * 单设备单指标查询面——实测结论），anomaly 注记按 deviceId 维度全量输出（病区过滤待绑定面
 * PR-3 闭合）；在床/离床逐设备状态与落卡权威归 M05（PR-3，GC17⑥——本 PR 仅视图骨架）。
 *
 * <p>装配归 WardWebConfig @Import（com.fuyun.ward 不在组件扫描范围，宪法 B.1）。
 */
@Slf4j
public class VitalSignBoardServiceImpl implements IVitalSignBoardService {

    /** 编排缺位说明（前端注记展示） */
    private static final String BOARD_NOTE =
            "在床/离床逐设备状态与采集质量病区过滤缺位：设备→病区映射归绑定面（PR-3 闭合），" + "本视图出 deviceId 维度 anomaly 注记；落卡权威归 M05（PR-3，GC17⑥）";

    /** 采集质量注记键匹配模式：fy:ward:vital:anomaly:* */
    private static final String ANOMALY_KEY_PATTERN = WardMessagingConstants.VITAL_ANOMALY_KEY_PREFIX + "*";

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1）。
     *
     * @param redisTemplate String 模板（A.5-1），非空；anomaly 注记快照读取通道
     * @param objectMapper  JSON 转换器，非空；注记快照反解析（全局定制实例——Instant 反序列化依赖）
     */
    public VitalSignBoardServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 病区体征看板（纯读视图）：SCAN 遍历 anomaly 注记快照键（禁 KEYS 全量——生产红线），
     * 逐键读取反解析出注记清单，附 presence 指标锚与编排缺位说明。
     *
     * <p>边界条件：注记按 deviceId 维度全量输出（病区过滤待绑定面 PR-3 闭合——wardId 当前
     * 仅随视图透出不参与过滤）；单键快照脏数据跳过不阻断看板（warn 留痕，TTL 24h 自然过期
     * 自愈）；在床/离床逐设备状态与落卡权威归 M05（本视图仅骨架）。
     *
     * @param wardId 病区 ID，非空；来源：看板端点路径变量（当前不参与注记过滤，仅随视图透出）
     * @return 看板视图（presence 指标码 + anomaly 注记清单 + 缺位说明；无注记时清单为空）
     */
    @Override
    @Transactional(readOnly = true)
    public VitalBoardVO board(Long wardId) {
        // 缓存操作（读）：SCAN 遍历注记键（禁 KEYS 全量——生产红线），逐键读取快照反解析
        List<VitalAnomalyVO> anomalies = new ArrayList<>();
        try (var cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(ANOMALY_KEY_PATTERN).count(100).build())) {
            List<String> keys = new ArrayList<>();
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
            for (String key : keys) {
                String value = redisTemplate.opsForValue().get(key);
                if (value == null) {
                    continue;
                }
                try {
                    anomalies.add(toAnomalyVO(objectMapper.readValue(value, TelemetryAnomalyPayload.class)));
                } catch (Exception e) {
                    // 快照脏数据不阻断看板：warn 留痕跳过（TTL 24h 自然过期自愈）
                    log.warn("体征采集质量注记反解析失败（跳过）：key={}", key);
                }
            }
        }
        log.debug("体征看板出网：wardId={}，anomalyCount={}", wardId, anomalies.size());
        return new VitalBoardVO(wardId, WardMessagingConstants.PRESENCE_METRIC_CODE, anomalies, BOARD_NOTE);
    }

    /**
     * 载荷 → 注记视图（Instant → UTC OffsetDateTime 稳定转换）。
     *
     * @param payload 断流异常载荷，非空
     * @return 注记视图，非空
     */
    private static VitalAnomalyVO toAnomalyVO(TelemetryAnomalyPayload payload) {
        return new VitalAnomalyVO(
                payload.deviceId(),
                payload.metricCode(),
                payload.anomalyType(),
                payload.lastOccurredAt() == null
                        ? null
                        : payload.lastOccurredAt().atOffset(ZoneOffset.UTC),
                payload.detectedAt() == null ? null : payload.detectedAt().atOffset(ZoneOffset.UTC));
    }
}
