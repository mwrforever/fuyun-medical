package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotConsumerStatEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.enums.AlarmStatus;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.mapper.IotConsumerStatMapper;
import com.fuyun.iot.mapper.IotDataQualityStatMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotMetricMappingMapper;
import com.fuyun.iot.record.DeviceStatusSnapshotView;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.service.IDashboardService;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.iot.vo.BindingVO;
import com.fuyun.iot.vo.DashboardSummaryVO;
import com.fuyun.iot.vo.WardDeviceWallVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 运营大屏数据面服务实现（FU-M14-13，P2 PR-2 Task 11）：全院摘要六项聚合 + Redis 快照缓存
 * + 病区床位设备状态墙 + summary 变更触发推送。
 *
 * <p><b>聚合六项来源（brief 逐项）</b>：设备在线/离线/总数 = iot_device 状态计数（三次固定
 * selectCount，调用序 online→offline→total）；活跃告警 = iot_alarm.status=ACTIVE 计数；风暴态
 * 横幅 = SCAN {@code fy:iot:alarm:storm:*} 任一在挂（brief 注记取 SCAN 形态——风暴键量级受规则
 * 数约束可控，维护集合需改 Task 7 StormGuard 冻结面，不值）；积压水位 = iot_consumer_stat 本地
 * 消费组最新快照（只读不采样——采样属 monitor 端点惰性职责，大屏轮询频度下重复采样将放大落表
 * 噪音）；质量分 = iot_data_quality_stat 当日 UTC 平均分（AVG 聚合下推数据库，宪法 A.4.3-14）。
 *
 * <p><b>缓存与推送形态</b>：{@code fy:iot:snapshot:dashboard} TTL 5s read-through（String JSON
 * 承载，读/写/解析三分支失败均 warn 降级直算——缓存可重建不阻断）；变更触发推送 = 缓存承载的
 * 上次摘要与新算结果 record 相等判定（纯数据 record 无易变字段，相等即语义相等），实测推送面无
 * @Scheduled 心跳先例（既有 @Scheduled 均为 ShedLock 互斥业务 Job），真栈心跳形态留 Task 18。
 *
 * <p>无状态单例（Redis 承载缓存与快照面）；装配归 IotConfig @Import（com.fuyun.iot 不在组件
 * 扫描范围，宪法 B.1）；JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class DashboardServiceImpl implements IDashboardService {

    /** 大屏摘要快照键（brief 冻结：fy:iot:snapshot:dashboard，A.5-1 命名） */
    static final String DASHBOARD_SNAPSHOT_KEY = "fy:iot:snapshot:dashboard";

    /** 大屏摘要快照 TTL：5 秒（brief 冻结；大屏轮询频度下读路径不触库的口径） */
    static final Duration DASHBOARD_SNAPSHOT_TTL = Duration.ofSeconds(5);

    /** 风暴态键匹配模式（Task 7 StormGuard 置位面同源：fy:iot:alarm:storm:{ruleId}） */
    static final String STORM_KEY_PATTERN = "fy:iot:alarm:storm:*";

    /** 设备状态快照键前缀（Task 5 写入面同源） */
    private static final String DEVICE_STATUS_SNAPSHOT_PREFIX = "fy:iot:snapshot:device-status:";

    /** 遥测最新值快照键前缀（AlarmEngine 写入面同源） */
    private static final String LATEST_SNAPSHOT_PREFIX = "fy:iot:snapshot:latest:";

    /** 设备档案 mapper：在线/离线/总数计数与病区设备墙档案装载通道 */
    private final IotDeviceMapper deviceMapper;

    /** 告警行 mapper：活跃告警计数通道 */
    private final IotAlarmMapper alarmMapper;

    /** 消费积压快照 mapper：最新快照读取通道（只读） */
    private final IotConsumerStatMapper consumerStatMapper;

    /** 质量日统计 mapper：当日平均质量分聚合下推通道 */
    private final IotDataQualityStatMapper statMapper;

    /** 物模型属性映射 mapper：病区设备墙产品 → 指标编码展开通道（最新值快照键定位前置） */
    private final IotMetricMappingMapper metricMappingMapper;

    /** 绑定服务：病区生效绑定（快照五元组）查询面（Task 3 listByWard 单点口径复用） */
    private final IBindingService bindingService;

    /** String 模板（禁 JDK 序列化）：大屏快照缓存/设备状态与最新值快照读/风暴键 SCAN 唯一通道 */
    private final StringRedisTemplate redisTemplate;

    /** JSON 转换器：摘要快照序列化/反序列化与设备状态快照解析（Boot 容器实例） */
    private final ObjectMapper objectMapper;

    /** STOMP 推送服务：summary 变更触发的 /topic/iot/dashboard/global 推送出口 */
    private final ITelemetryPushService pushService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param deviceMapper        设备档案 mapper，非空
     * @param alarmMapper         告警行 mapper，非空
     * @param consumerStatMapper  消费积压快照 mapper，非空
     * @param statMapper          质量日统计 mapper，非空
     * @param metricMappingMapper 物模型属性映射 mapper，非空
     * @param bindingService      绑定服务，非空
     * @param redisTemplate       String 模板，非空；来源：Boot Redis 自动配置
     * @param objectMapper        JSON 转换器，非空；来源：Boot 容器 ObjectMapper
     * @param pushService         STOMP 推送服务，非空；来源：fuyun-app IotConfig 装配链
     */
    public DashboardServiceImpl(
            IotDeviceMapper deviceMapper,
            IotAlarmMapper alarmMapper,
            IotConsumerStatMapper consumerStatMapper,
            IotDataQualityStatMapper statMapper,
            IotMetricMappingMapper metricMappingMapper,
            IBindingService bindingService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ITelemetryPushService pushService) {
        this.deviceMapper = deviceMapper;
        this.alarmMapper = alarmMapper;
        this.consumerStatMapper = consumerStatMapper;
        this.statMapper = statMapper;
        this.metricMappingMapper = metricMappingMapper;
        this.bindingService = bindingService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.pushService = pushService;
    }

    @Override
    public DashboardSummaryVO summary() {
        // 读路径：缓存命中直返（读失败/损坏降级直算，缓存面缺席不阻断聚合主链）
        DashboardSummaryVO cached = readCachedSummary();
        if (cached != null) {
            return cached;
        }
        DashboardSummaryVO computed = computeSummary();
        writeCachedSummary(computed);
        return computed;
    }

    @Override
    public void refreshAndPushIfChanged() {
        DashboardSummaryVO previous = readCachedSummary();
        DashboardSummaryVO current = computeSummary();
        // 变更判定：纯数据 record 相等即语义相等；缓存缺席（首次基准）视为变更推送
        if (previous == null || !previous.equals(current)) {
            pushService.pushDashboardSummary(current);
            log.info(
                    "全院运营摘要变更推送完成：online={}，offline={}，activeAlarms={}，storm={}，backlog={}，quality={}",
                    current.onlineCount(),
                    current.offlineCount(),
                    current.activeAlarmCount(),
                    current.stormActive(),
                    current.backlogEstimate(),
                    current.qualityScore());
        }
        // 回写缓存（变更与否均写）：刷新 TTL 保持热缓存，且值与本次计算一致
        writeCachedSummary(current);
    }

    @Override
    public WardDeviceWallVO wardWall(Long wardId) {
        // 面①绑定五元组：病区生效绑定（BOUND）清单驱动状态墙行展开
        List<BindingVO> bindings = bindingService.listByWard(wardId);
        if (bindings.isEmpty()) {
            return new WardDeviceWallVO(wardId, List.of());
        }
        List<String> deviceIds =
                bindings.stream().map(BindingVO::deviceId).distinct().toList();
        // 数据库读操作：设备档案批级装载（宪法 A.4.3-14 拒循环内单查）——名称/回退状态/产品归属
        Map<String, IotDeviceEntity> deviceById = deviceMapper
                .selectList(Wrappers.<IotDeviceEntity>lambdaQuery().in(IotDeviceEntity::getDeviceId, deviceIds))
                .stream()
                .collect(LinkedHashMap::new, (m, d) -> m.put(d.getDeviceId(), d), Map::putAll);
        // 面②设备状态：快照批级 MGET（Task 5 写入面），缺席逐项回退档案 status
        Map<String, DeviceStatusSnapshotView> statusSnapshots =
                readStatusSnapshots(deviceById.keySet().stream().toList());
        // 面③最新值：产品映射展开指标 → 批级 MGET 快照（「值|毫秒」管道文本解析）
        Map<String, List<WardDeviceWallVO.LatestValue>> latestByDevice = readLatestValues(deviceById.values());
        List<WardDeviceWallVO.BedDeviceItem> items = new ArrayList<>(bindings.size());
        for (BindingVO binding : bindings) {
            IotDeviceEntity device = deviceById.get(binding.deviceId());
            DeviceStatusSnapshotView snapshot = statusSnapshots.get(binding.deviceId());
            items.add(new WardDeviceWallVO.BedDeviceItem(
                    binding.bedId(),
                    binding.deviceId(),
                    device == null ? null : device.getDeviceName(),
                    binding.patientId(),
                    binding.visitId(),
                    snapshot != null ? snapshot.status() : device == null ? null : device.getStatus(),
                    snapshot != null ? snapshot.lastOnlineAt() : device == null ? null : device.getLastOnlineAt(),
                    latestByDevice.getOrDefault(binding.deviceId(), List.of())));
        }
        log.info("病区床位设备状态墙装配完成：wardId={}，绑定行={}，设备档案命中={}", wardId, bindings.size(), deviceById.size());
        return new WardDeviceWallVO(wardId, List.copyOf(items));
    }

    /**
     * 读大屏摘要缓存（String JSON → record）：键缺席返回 null；JSON 损坏/Redis 异常 warn 降级
     * 返回 null（直算承接）。
     *
     * @return 缓存摘要；缺席或降级为 null
     */
    private DashboardSummaryVO readCachedSummary() {
        try {
            String json = redisTemplate.opsForValue().get(DASHBOARD_SNAPSHOT_KEY);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, DashboardSummaryVO.class);
        } catch (JsonProcessingException e) {
            log.warn("大屏摘要快照解析失败（降级直算覆盖）：原因={}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("大屏摘要快照读取失败（降级直算）：原因={}", e.getMessage());
            return null;
        }
    }

    /**
     * 写大屏摘要缓存（String JSON + 显式 TTL 5s，禁无 TTL 键红线）：Redis 异常/序列化异常仅
     * warn 降级不阻断调用方主链（缓存可重建）。
     *
     * @param summary 待缓存摘要，非空
     */
    private void writeCachedSummary(DashboardSummaryVO summary) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(DASHBOARD_SNAPSHOT_KEY, objectMapper.writeValueAsString(summary), DASHBOARD_SNAPSHOT_TTL);
        } catch (Exception e) {
            log.warn("大屏摘要快照写入失败（缓存降级不影响主链）：原因={}", e.getMessage());
        }
    }

    /**
     * 全院摘要六项聚合（无缓存直算）：来源逐项见类注释；聚合全部下推数据库（禁全量捞行内存计数，
     * 宪法 A.4.3-14）。
     *
     * @return 全院摘要视图，非空
     */
    private DashboardSummaryVO computeSummary() {
        // 设备三计数（调用序 online→offline→total，与单测连续打桩序一致）
        long online = safeCount(deviceMapper.selectCount(
                Wrappers.<IotDeviceEntity>lambdaQuery().eq(IotDeviceEntity::getStatus, DeviceStatus.ONLINE)));
        long offline = safeCount(deviceMapper.selectCount(
                Wrappers.<IotDeviceEntity>lambdaQuery().eq(IotDeviceEntity::getStatus, DeviceStatus.OFFLINE)));
        long total = safeCount(deviceMapper.selectCount(null));
        long activeAlarms =
                safeCount(alarmMapper.selectCount(Wrappers.<com.fuyun.iot.entity.IotAlarmEntity>lambdaQuery()
                        .eq(com.fuyun.iot.entity.IotAlarmEntity::getStatus, AlarmStatus.ACTIVE)));
        boolean stormActive = hasAnyStormKey();
        BigDecimal backlog = readBacklogEstimate();
        BigDecimal quality = readAverageQualityScore();
        return new DashboardSummaryVO(total, online, offline, activeAlarms, stormActive, backlog, quality);
    }

    /**
     * 风暴态横幅判定：SCAN {@code fy:iot:alarm:storm:*} 首个命中即 true（SCAN 增量游走不阻塞
     * Redis 主线程，风暴键量级受规则数约束）；Redis 异常降级 false——横幅缺失不阻断大屏（与
     * StormGuard 降级口径一致）。
     *
     * @return true=存在任一风暴键在挂
     */
    private boolean hasAnyStormKey() {
        try {
            Boolean hit = redisTemplate.execute((RedisCallback<Boolean>) connection -> {
                try (var cursor = connection.scan(ScanOptions.scanOptions()
                        .match(STORM_KEY_PATTERN)
                        .count(100)
                        .build())) {
                    return cursor.hasNext();
                } catch (Exception e) {
                    // SCAN 游走异常包装为运行时上抛，由外层统一降级
                    throw new IllegalStateException("风暴键扫描失败：" + e.getMessage(), e);
                }
            });
            return Boolean.TRUE.equals(hit);
        } catch (RuntimeException e) {
            log.warn("风暴态横幅扫描失败（降级为非风暴）：原因={}", e.getMessage());
            return false;
        }
    }

    /**
     * 读本地消费组最新积压水位（只读不采样——采样归 monitor 端点惰性职责，防大屏轮询放大落表噪音）。
     *
     * @return 最新快照 backlog_estimate；无快照或值为 null 返回 null
     */
    private BigDecimal readBacklogEstimate() {
        // 数据库读操作：本地消费组最新快照（sampled_at 降序首行）
        List<IotConsumerStatEntity> stats =
                consumerStatMapper.selectLatestPerGroup(List.of(IotMessagingConstants.LOCAL_CONSUMER_GROUP));
        if (stats.isEmpty()) {
            return null;
        }
        return stats.get(0).getBacklogEstimate();
    }

    /**
     * 读当日（UTC 日切）全院平均质量分（AVG 聚合下推数据库）：当日无统计行（惰性重算未触发）
     * 返回 null——无数据语义优于伪 0 分。
     *
     * @return 平均质量分 0~100；无统计行为 null
     */
    private BigDecimal readAverageQualityScore() {
        // 数据库读操作：单值 AVG 聚合（QueryWrapper 列名直书——单标量聚合不值得落 XML，A.4.3-15）
        List<Object> rows = statMapper.selectObjs(new QueryWrapper<com.fuyun.iot.entity.IotDataQualityStatEntity>()
                .select("AVG(quality_score)")
                .eq("stat_date", LocalDate.now(ZoneOffset.UTC)));
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return new BigDecimal(rows.get(0).toString());
    }

    /**
     * 批级读设备状态快照（MGET 单次往返）：快照 JSON 与 Task 5 写入面契约同形
     * （DeviceStatusSnapshotView）；解析失败逐项降级（返回缺席，调用方回退档案）。
     *
     * @param deviceIds 设备标识清单，非空
     * @return deviceId → 快照视图映射，非空；缺席/解析失败设备无映射
     */
    private Map<String, DeviceStatusSnapshotView> readStatusSnapshots(List<String> deviceIds) {
        List<String> keys =
                deviceIds.stream().map(DEVICE_STATUS_SNAPSHOT_PREFIX::concat).toList();
        Map<String, DeviceStatusSnapshotView> result = new HashMap<>(deviceIds.size() * 2);
        if (keys.isEmpty()) {
            return result;
        }
        try {
            List<String> jsons = redisTemplate.opsForValue().multiGet(keys);
            for (int i = 0; i < deviceIds.size(); i++) {
                String json = jsons == null ? null : jsons.get(i);
                if (json == null) {
                    continue;
                }
                result.put(deviceIds.get(i), objectMapper.readValue(json, DeviceStatusSnapshotView.class));
            }
        } catch (Exception e) {
            // 批级读整体失败同样降级为全部缺席（逐项回退设备档案状态）
            log.warn("设备状态快照批级读取失败（回退档案状态）：原因={}", e.getMessage());
        }
        return result;
    }

    /**
     * 批级读设备×指标最新值（产品映射展开快照键 + MGET 单次往返）：无产品/无映射设备直接空清单；
     * 「值|毫秒时间戳」管道文本解析（AlarmEngine 写入面同源），非数值/损坏行跳过不阻断整墙。
     *
     * @param devices 设备档案集合（productId 定位来源），非空
     * @return deviceId → 最新值清单映射，非空；无映射设备为空清单
     */
    private Map<String, List<WardDeviceWallVO.LatestValue>> readLatestValues(Iterable<IotDeviceEntity> devices) {
        // 数据库读操作：病区设备产品集合圈定 + 已映射指标批级装载（productId → metricCode 集合，
        // 单次 IN 查询，禁全表装载）
        List<String> productIds = new ArrayList<>();
        for (IotDeviceEntity device : devices) {
            if (device.getProductId() != null && !productIds.contains(device.getProductId())) {
                productIds.add(device.getProductId());
            }
        }
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> metricsByProduct = new HashMap<>();
        for (IotMetricMappingEntity mapping : metricMappingMapper.selectList(
                Wrappers.<IotMetricMappingEntity>lambdaQuery().in(IotMetricMappingEntity::getProductId, productIds))) {
            metricsByProduct
                    .computeIfAbsent(mapping.getProductId(), k -> new ArrayList<>())
                    .add(mapping.getMetricCode());
        }
        // 展开设备×指标快照键（保持遍历序，解析后按同序回填）
        List<String> deviceIds = new ArrayList<>();
        List<String> metricCodes = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (IotDeviceEntity device : devices) {
            List<String> metrics = device.getProductId() == null
                    ? List.of()
                    : metricsByProduct.getOrDefault(device.getProductId(), List.of());
            for (String metricCode : metrics) {
                deviceIds.add(device.getDeviceId());
                metricCodes.add(metricCode);
                keys.add(LATEST_SNAPSHOT_PREFIX + device.getDeviceId() + ":" + metricCode);
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, List<WardDeviceWallVO.LatestValue>> result = new HashMap<>();
        try {
            List<String> payloads = redisTemplate.opsForValue().multiGet(keys);
            for (int i = 0; i < keys.size(); i++) {
                WardDeviceWallVO.LatestValue value = parseLatestValue(
                        deviceIds.get(i), metricCodes.get(i), payloads == null ? null : payloads.get(i));
                if (value != null) {
                    result.computeIfAbsent(value.deviceId(), k -> new ArrayList<>())
                            .add(value);
                }
            }
        } catch (RuntimeException e) {
            // 最新值快照批级读失败降级为全部缺席（辅助面，不阻断状态墙主面）
            log.warn("最新值快照批级读取失败（状态墙最新值面缺席）：原因={}", e.getMessage());
        }
        return result;
    }

    /**
     * 解析单条最新值快照（「值|毫秒时间戳」管道文本，AlarmEngine 写入面同源）：缺席/形态损坏
     * 返回 null 跳过（快照为辅助面，单行损坏不阻断整墙装配）。
     *
     * @param deviceId   设备标识，非空
     * @param metricCode 指标编码，非空
     * @param payload    快照原文，可空（缺席）
     * @return 最新值视图；缺席/损坏为 null
     */
    private static WardDeviceWallVO.LatestValue parseLatestValue(String deviceId, String metricCode, String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        int separator = payload.indexOf('|');
        if (separator <= 0) {
            return null;
        }
        try {
            return new WardDeviceWallVO.LatestValue(
                    deviceId,
                    metricCode,
                    new BigDecimal(payload.substring(0, separator)),
                    OffsetDateTime.ofInstant(
                            Instant.ofEpochMilli(Long.parseLong(payload.substring(separator + 1))), ZoneOffset.UTC));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 计数 null 安全归一（MP selectCount 理论非空，mock/降级场景防御）。 */
    private static long safeCount(Long count) {
        return count == null ? 0 : count;
    }
}
