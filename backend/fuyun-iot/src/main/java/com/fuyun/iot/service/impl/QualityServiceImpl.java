package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.TelemetryAnomalyPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.QualityStatQueryRequest;
import com.fuyun.iot.entity.IotConsumerStatEntity;
import com.fuyun.iot.entity.IotDataQualityStatEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.IotAmqpMetrics;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.mapper.IotConsumerStatMapper;
import com.fuyun.iot.mapper.IotDataQualityStatMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.mapper.IotMetricMappingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.record.DailyQualityCountRow;
import com.fuyun.iot.record.DeviceMetricLastRow;
import com.fuyun.iot.service.IQualityService;
import com.fuyun.iot.vo.ConsumerStatVO;
import com.fuyun.iot.vo.DataQualityStatVO;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据质量监控服务实现（FU-M14-11，P2 PR-2 Task 10）：质量日统计惰性重算落库 + 消费积压快照
 * 采样落表 + 遥测断流判定事件发布。统计/采样/判定全部随查询惰性调起（批量定时调度随 P3 归
 * Task 18 Spec，本 PR 只落服务与端点）。
 *
 * <p><b>统计推算口径（V1012 列注释同源）</b>：expected_count = 设备指标字典最大标称频率 × 当日
 * 在线分钟数（在线时长近似：ONLINE 记全天 1440 分钟、其余状态记 0——精确在线时长随 P3 调度面
 * 完善）；missing_rate = max(0, expected-received)/expected（期望为 0 恒 0，未登记标称频率设备
 * 不参与缺数判定防误报）；anomaly_count = 当日 quality != GOOD 行数（SUSPECT+BAD）；
 * quality_score = 100×(1-缺数率)×(1-异常率)（异常率 = anomaly/received）。
 *
 * <p><b>断流判定</b>（brief 冻结语义）：在线设备登记标称频率的指标超过
 * {@link #ANOMALY_NOMINAL_MULTIPLIER} 倍标称周期无有效采集 → 发布 iot.telemetry.anomaly
 * （STREAM_GAP，载荷 TelemetryAnomalyPayload）；Redis 去重门闸 1 小时窗口内同设备同指标仅首见
 * 发布（防查询频度驱动事件风暴），Redis 异常降级为跳过本轮发布（辅助面降级口径同 AlarmEngine）。
 * 候选设备扫描上限 200 行（OfflineDetector 泄压同口径，截断 warn 留痕）。
 *
 * <p><b>积压快照</b>：读本地指标面（IotAmqpMetrics gauge 攒批队列填充率，本地积压水位口径）落
 * iot_consumer_stat 快照行；真实 IoTDA 积压水位/消费速率/到达速率本地不可得恒 NULL（随联调补全，
 * V1012 列注释申报）；消费链未启用（gauge 未注册）跳过采样仅返回既有快照。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class QualityServiceImpl implements IQualityService {

    /** 断流判定倍率 N：超过 3 倍标称周期无数据即断流（防抖余量，标称周期 = 60s/标称频率） */
    static final int ANOMALY_NOMINAL_MULTIPLIER = 3;

    /** 全天在线分钟数（在线时长近似口径：ONLINE 设备记全天，V1012 expected_count 推算基准） */
    static final int FULL_DAY_MINUTES = 24 * 60;

    /** 断流候选设备扫描上限（LIMIT 硬顶，OfflineDetector 泄压同口径） */
    static final int ANOMALY_SCAN_LIMIT = 200;

    /** 断流去重门闸 TTL：1 小时窗口内同设备同指标仅首见发布（brief 冻结防刷屏口径同源） */
    static final Duration ANOMALY_DEDUP_TTL = Duration.ofHours(1);

    /** 断流去重门闸键前缀（A.5-1 命名：fy:iot:anomaly:{deviceId}:{metricCode}） */
    private static final String ANOMALY_DEDUP_KEY_PREFIX = "fy:iot:anomaly:";

    /** 全链路追踪号 MDC 键（与发布器同源；发布点捕获防 AFTER_COMMIT 丢失） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /** 审计留痕系统操作人（惰性触发无登录上下文回退值） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 质量日统计 mapper：惰性重算 UPSERT 唯一写通道 */
    private final IotDataQualityStatMapper statMapper;

    /** 遥测明细 mapper：单日计数聚合下推与断流末次采集查询通道 */
    private final IotTelemetryMapper telemetryMapper;

    /** 设备档案 mapper：统计设备存在性校验、状态在线判定与断流候选扫描通道 */
    private final IotDeviceMapper deviceMapper;

    /** MDC 字典 mapper：标称频率装载通道 */
    private final IotMetricDictMapper metricDictMapper;

    /** 物模型属性映射 mapper：设备产品 → 指标编码展开通道（设备标称频率定位前置） */
    private final IotMetricMappingMapper metricMappingMapper;

    /** 消费积压快照 mapper：采样落表与最新快照查询通道 */
    private final IotConsumerStatMapper consumerStatMapper;

    /** Micrometer 注册表：AMQP 攒批队列填充率 gauge 读取面（IotAmqpMetrics 注册） */
    private final MeterRegistry meterRegistry;

    /** String 模板（A.5-1）：断流去重门闸 SET NX EX 通道 */
    private final StringRedisTemplate redisTemplate;

    /** 应用事件发布器：事务内/无事务发布 IotDomainEvent（IotDomainPublisher AFTER_COMMIT 出 MQ） */
    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param statMapper          质量日统计 mapper，非空；来源：同模块 mapper 包
     * @param telemetryMapper     遥测明细 mapper，非空；来源：同模块 mapper 包
     * @param deviceMapper        设备档案 mapper，非空；来源：同模块 mapper 包
     * @param metricDictMapper    MDC 字典 mapper，非空；来源：同模块 mapper 包
     * @param metricMappingMapper 物模型属性映射 mapper，非空；来源：同模块 mapper 包
     * @param consumerStatMapper  消费积压快照 mapper，非空；来源：同模块 mapper 包
     * @param meterRegistry       Micrometer 注册表，非空；来源：Boot actuator 自动装配
     * @param redisTemplate       String 模板（A.5-1），非空；来源：Boot Redis 自动配置
     * @param events              应用事件发布器，非空；来源：Spring 上下文
     */
    public QualityServiceImpl(
            IotDataQualityStatMapper statMapper,
            IotTelemetryMapper telemetryMapper,
            IotDeviceMapper deviceMapper,
            IotMetricDictMapper metricDictMapper,
            IotMetricMappingMapper metricMappingMapper,
            IotConsumerStatMapper consumerStatMapper,
            MeterRegistry meterRegistry,
            StringRedisTemplate redisTemplate,
            ApplicationEventPublisher events) {
        this.statMapper = statMapper;
        this.telemetryMapper = telemetryMapper;
        this.deviceMapper = deviceMapper;
        this.metricDictMapper = metricDictMapper;
        this.metricMappingMapper = metricMappingMapper;
        this.consumerStatMapper = consumerStatMapper;
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
        this.events = events;
    }

    @Override
    @Transactional
    public PageResult<DataQualityStatVO> qualityStats(QualityStatQueryRequest request) {
        return listStats(request);
    }

    @Override
    @Transactional
    public PageResult<DataQualityStatVO> deviceUsage(QualityStatQueryRequest request) {
        // 设备利用率与质量统计同表同重算语义（usageRate 为核心字段，DataQualityStatVO 现算折算）
        return listStats(request);
    }

    /**
     * 统计分页统一入口：显式指定设备时先重算该设备统计行（惰性触发面）并顺带断流判定，再分页
     * 返回（statDate 显式过滤，缺省列全部日期行）。
     *
     * @param request 查询请求，非空
     * @return 统计分页出参，非空
     * @throws BizException IOT-1006（404；deviceId 显式指定但设备不存在）
     */
    private PageResult<DataQualityStatVO> listStats(QualityStatQueryRequest request) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        String deviceId = request.deviceId();
        boolean deviceFiltered = deviceId != null && !deviceId.isBlank();
        if (deviceFiltered) {
            // 惰性重算触发面：显式指定设备才重算（防无指定查询触发全院重算风暴），缺省统计日=当日
            LocalDate recomputeDate = request.statDate() != null ? request.statDate() : LocalDate.now(ZoneOffset.UTC);
            recomputeDeviceDailyStat(deviceId, recomputeDate);
            // 断流判定随统计查询惰性调起（失败不阻断统计查询主链路）
            runAnomalyDetectionQuietly();
        }
        // 数据库读操作：过滤分页（UK device_date 准入，device_id 升序稳定输出）
        Page<IotDataQualityStatEntity> result = statMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<IotDataQualityStatEntity>lambdaQuery()
                        .eq(deviceFiltered, IotDataQualityStatEntity::getDeviceId, deviceId)
                        .eq(request.statDate() != null, IotDataQualityStatEntity::getStatDate, request.statDate())
                        .orderByAsc(IotDataQualityStatEntity::getDeviceId));
        return PageResult.of(
                result.getRecords().stream().map(DataQualityStatVO::from).toList(), page, size, result.getTotal());
    }

    /**
     * 单设备单日统计重算（UPSERT 覆盖）：标称频率取设备产品已映射指标字典最大值，期望 = 频率 ×
     * 在线分钟数（ONLINE 记全天近似），计数聚合下推数据库，得分/缺数率按类注释公式现算。
     *
     * @param deviceId 设备标识，非空
     * @param statDate 统计归属自然日，非空
     * @throws BizException IOT-1006（404；设备不存在）
     */
    private void recomputeDeviceDailyStat(String deviceId, LocalDate statDate) {
        // 数据库读操作：设备存在性校验（404 词表顺延消费）与状态/产品定位
        IotDeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BizException(IotErrorCode.DEVICE_NOT_FOUND, HttpStatus.NOT_FOUND, "设备不存在：" + deviceId);
        }
        BigDecimal freqPerMin = loadDeviceNominalFreq(device.getProductId());
        // 在线时长近似（类注释申报）：ONLINE 记全天 1440 分钟，其余状态记 0——精确时长随 P3 完善
        int onlineMinutes = device.getStatus() == DeviceStatus.ONLINE ? FULL_DAY_MINUTES : 0;
        long expected = freqPerMin == null
                ? 0
                : freqPerMin
                        .multiply(BigDecimal.valueOf(onlineMinutes))
                        .setScale(0, RoundingMode.DOWN)
                        .longValue();
        OffsetDateTime from = statDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime to = statDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        // 数据库读操作：当日接收/异常计数聚合下推（禁全量捞行内存计数）
        DailyQualityCountRow counts = telemetryMapper.countDailyQuality(deviceId, from, to);
        long received = counts == null || counts.totalCount() == null ? 0 : counts.totalCount();
        long anomaly = counts == null || counts.anomalyCount() == null ? 0 : counts.anomalyCount();
        BigDecimal missingRate = expected > 0
                ? BigDecimal.valueOf(Math.max(0, expected - received))
                        .divide(BigDecimal.valueOf(expected), 4, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        BigDecimal anomalyRate = received > 0
                ? BigDecimal.valueOf(anomaly).divide(BigDecimal.valueOf(received), 4, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        BigDecimal qualityScore = BigDecimal.valueOf(100)
                .multiply(BigDecimal.ONE.subtract(missingRate))
                .multiply(BigDecimal.ONE.subtract(anomalyRate))
                .setScale(2, RoundingMode.HALF_UP);
        IotDataQualityStatEntity stat = new IotDataQualityStatEntity();
        stat.setDeviceId(deviceId);
        stat.setStatDate(statDate);
        stat.setExpectedCount(expected);
        stat.setReceivedCount(received);
        stat.setMissingRate(missingRate);
        stat.setAnomalyCount(anomaly);
        stat.setQualityScore(qualityScore);
        stat.setUpdatedBy(SYSTEM_OPERATOR);
        // 数据库写操作：统计行 UPSERT（uk_iot_data_quality_stat_device_date 冲突覆盖）
        statMapper.upsertStat(stat);
        log.info(
                "质量日统计重算落库：deviceId={}，statDate={}，expected={}，received={}，missingRate={}，anomaly={}，score={}",
                deviceId,
                statDate,
                expected,
                received,
                missingRate,
                anomaly,
                qualityScore);
    }

    /**
     * 装载设备产品的最大标称频率（批级两次查询：产品映射 → 字典标称频率）：未挂产品/无映射/无
     * 登记标称频率返回 null（期望记 0，缺数率恒 0 防误报）。产品映射查询仅消费指标编码列，
     * .select 精确投影免映射宽行全列入内存（A.4.3-14；行集不变仅列收敛，distinct 语义等价）。
     *
     * @param productId 设备产品标识，可空（未挂产品）
     * @return 最大标称频率（次/分钟）；无可定位登记返回 null
     */
    private BigDecimal loadDeviceNominalFreq(String productId) {
        if (productId == null || productId.isBlank()) {
            return null;
        }
        // 数据库读操作：产品已映射指标编码单次 IN 前置查询（@TableLogic 自动过滤已删映射）——
        //   仅消费 metric_code 列，.select 精确投影免映射宽行全列入内存（A.4.3-14；行集不变
        //   仅列收敛，指标编码集合与全列取回完全等价）
        List<String> metricCodes = metricMappingMapper
                .selectList(Wrappers.<IotMetricMappingEntity>lambdaQuery()
                        .select(IotMetricMappingEntity::getMetricCode)
                        .eq(IotMetricMappingEntity::getProductId, productId))
                .stream()
                .map(IotMetricMappingEntity::getMetricCode)
                .distinct()
                .toList();
        if (metricCodes.isEmpty()) {
            return null;
        }
        // 数据库读操作：字典标称频率单次 IN 查询，取最大值（设备多指标按最高频指标推算期望）
        BigDecimal max = null;
        // 循环前批量取数（BE-C4 判据①形态收拢）：字典标称频率行单次装载，循环内纯迭代取最大
        List<IotMetricDictEntity> dictRows = metricDictMapper.selectList(Wrappers.<IotMetricDictEntity>lambdaQuery()
                .in(IotMetricDictEntity::getMetricCode, metricCodes)
                .isNotNull(IotMetricDictEntity::getNominalFreqPerMin));
        for (IotMetricDictEntity dict : dictRows) {
            BigDecimal freq = dict.getNominalFreqPerMin();
            if (freq.signum() > 0 && (max == null || freq.compareTo(max) > 0)) {
                max = freq;
            }
        }
        return max;
    }

    @Override
    public List<ConsumerStatVO> refreshAndListConsumerLag() {
        sampleConsumerStat();
        // 数据库读操作：每消费组最新快照（sampled_at 降序）
        return consumerStatMapper.selectLatestPerGroup(null).stream()
                .map(ConsumerStatVO::from)
                .toList();
    }

    /**
     * 积压快照采样落表：读本地指标面（攒批队列填充率 gauge）写一行 iot_consumer_stat；指标未注册
     * （消费链未启用）info 跳过，读数异常 warn 降级——采样为辅助语义不阻断快照查询返回。
     */
    private void sampleConsumerStat() {
        double fillRatio;
        try {
            fillRatio = meterRegistry
                    .get(IotAmqpMetrics.GAUGE_QUEUE_FILL_RATIO)
                    .gauge()
                    .value();
        } catch (RuntimeException e) {
            // 消费链未启用（gauge 未注册）或注册表异常：跳过采样仅返回既有快照（辅助面降级）
            log.info("AMQP 攒批队列指标未注册，跳过积压采样（消费链未启用或指标缺席）：原因={}", e.getMessage());
            return;
        }
        if (Double.isNaN(fillRatio)) {
            // gauge 载体被回收（强引用契约外场景）：按缺席跳过采样
            log.info("AMQP 攒批队列指标读数为 NaN（载体缺席），跳过本轮积压采样");
            return;
        }
        IotConsumerStatEntity entity = new IotConsumerStatEntity();
        entity.setConsumerGroup(IotMessagingConstants.LOCAL_CONSUMER_GROUP);
        entity.setSampledAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setBacklogEstimate(BigDecimal.valueOf(fillRatio).setScale(4, RoundingMode.HALF_UP));
        // 数据库写操作：快照行落表（真实 IoTDA 水位/速率本地不可得恒 NULL，随联调补全）
        consumerStatMapper.insert(entity);
        log.info("消费积压快照采样落库：group={}，backlogEstimate={}", entity.getConsumerGroup(), entity.getBacklogEstimate());
    }

    @Override
    public int detectTelemetryAnomalies() {
        // 数据库读操作：在线候选设备扫描（LIMIT 硬顶泄压，截断 warn 留痕）
        List<IotDeviceEntity> onlineDevices = deviceMapper.selectList(Wrappers.<IotDeviceEntity>lambdaQuery()
                .eq(IotDeviceEntity::getStatus, DeviceStatus.ONLINE)
                // 排序兜底：last_online_at 升序——状态最久未刷新者优先处置
                .orderByAsc(IotDeviceEntity::getLastOnlineAt)
                .last("LIMIT " + ANOMALY_SCAN_LIMIT));
        if (onlineDevices.isEmpty()) {
            return 0;
        }
        if (onlineDevices.size() >= ANOMALY_SCAN_LIMIT) {
            log.warn("断流候选触发扫描上限截断：limit={}", ANOMALY_SCAN_LIMIT);
        }
        List<String> deviceIds =
                onlineDevices.stream().map(IotDeviceEntity::getDeviceId).toList();
        // 数据库读操作：末次有效采集时刻批级一次聚合下推（禁循环内单查）
        Map<String, OffsetDateTime> lastByKey = new HashMap<>(deviceIds.size() * 2);
        // 循环前批量取数（BE-C4 判据①形态收拢）：末次采集时刻行单次装载，循环内纯迭代组装映射
        List<DeviceMetricLastRow> lastRows = telemetryMapper.selectLastOccurredByDeviceMetric(deviceIds);
        for (DeviceMetricLastRow row : lastRows) {
            lastByKey.put(row.deviceId() + ":" + row.metricCode(), row.lastOccurredAt());
        }
        // 数据库读操作：登记标称频率的字典行单次装载（指标 → 次/分钟）
        Map<String, BigDecimal> freqByMetric = new HashMap<>();
        // 循环前批量取数（BE-C4 判据①形态收拢）：字典标称频率行单次装载，循环内纯迭代组装映射
        List<IotMetricDictEntity> dictRows = metricDictMapper.selectList(
                Wrappers.<IotMetricDictEntity>lambdaQuery().isNotNull(IotMetricDictEntity::getNominalFreqPerMin));
        for (IotMetricDictEntity dict : dictRows) {
            if (dict.getNominalFreqPerMin().signum() > 0) {
                freqByMetric.put(dict.getMetricCode(), dict.getNominalFreqPerMin());
            }
        }
        Instant now = Instant.now();
        int published = 0;
        for (String deviceId : deviceIds) {
            for (Map.Entry<String, BigDecimal> freqEntry : freqByMetric.entrySet()) {
                OffsetDateTime lastOccurred = lastByKey.get(deviceId + ":" + freqEntry.getKey());
                if (lastOccurred == null) {
                    // 无采集历史（字典指标从未上报）：无从判定时点，跳过
                    continue;
                }
                // 标称周期 = 60s/标称频率（0.5 次/分 → 120s，进位取整防低频指标误判）
                long intervalSecs = BigDecimal.valueOf(60)
                        .divide(freqEntry.getValue(), 0, RoundingMode.UP)
                        .longValue();
                long thresholdSecs = (long) ANOMALY_NOMINAL_MULTIPLIER * intervalSecs;
                long gapSecs = Duration.between(lastOccurred.toInstant(), now).getSeconds();
                if (gapSecs <= thresholdSecs) {
                    continue;
                }
                if (!publishAnomalyOnce(deviceId, freqEntry.getKey(), lastOccurred, now)) {
                    continue;
                }
                published++;
                log.warn(
                        "遥测断流异常发布：deviceId={}，metricCode={}，gapSecs={}，thresholdSecs={}，lastOccurredAt={}",
                        deviceId,
                        freqEntry.getKey(),
                        gapSecs,
                        thresholdSecs,
                        lastOccurred);
            }
        }
        return published;
    }

    /**
     * 断流事件去重发布：Redis SET NX EX 门闸（1 小时窗口同设备同指标仅首见发布）；门闸失败降级
     * 为跳过本轮（防查询频度驱动事件风暴），门闸放行经应用事件发布 iot.telemetry.anomaly。
     *
     * @param deviceId     设备标识，非空
     * @param metricCode   指标编码，非空
     * @param lastOccurred 末次有效采集时刻，非空
     * @param detectedAt   判定时刻，非空
     * @return true=本次实际发布；false=门闸拦截或降级跳过
     */
    private boolean publishAnomalyOnce(
            String deviceId, String metricCode, OffsetDateTime lastOccurred, Instant detectedAt) {
        String key = ANOMALY_DEDUP_KEY_PREFIX + deviceId + ":" + metricCode;
        try {
            // Redis 写操作：SET NX EX 首见放行（1 小时窗口内重复断流静默，防事件风暴）
            Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(key, "1", ANOMALY_DEDUP_TTL);
            if (!Boolean.TRUE.equals(firstSeen)) {
                return false;
            }
        } catch (RuntimeException e) {
            // Redis 降级：去重门闸缺席时发布将随查询频度重复，宁缺毋滥跳过本轮并留痕
            log.warn("断流去重门闸操作失败（本轮跳过发布）：deviceId={}，metricCode={}，原因={}", deviceId, metricCode, e.getMessage());
            return false;
        }
        // 消息发送：发布断流异常事件（IotDomainPublisher AFTER_COMMIT 出 MQ；无事务 fallbackExecution 兜底）
        events.publishEvent(new IotDomainEvent(
                IotMessagingConstants.EVENT_TELEMETRY_ANOMALY,
                new TelemetryAnomalyPayload(
                        deviceId,
                        metricCode,
                        IotMessagingConstants.ANOMALY_TYPE_STREAM_GAP,
                        lastOccurred.toInstant(),
                        detectedAt),
                detectedAt,
                MDC.get(TRACE_ID_MDC_KEY)));
        return true;
    }

    /**
     * 断流判定静默执行（统计查询惰性调起包装）：判定失败仅 error 告警不阻断统计查询主链路。
     */
    private void runAnomalyDetectionQuietly() {
        try {
            detectTelemetryAnomalies();
        } catch (RuntimeException e) {
            log.error("断流判定执行失败（不影响统计查询返回）：原因={}", e.getMessage(), e);
        }
    }
}
