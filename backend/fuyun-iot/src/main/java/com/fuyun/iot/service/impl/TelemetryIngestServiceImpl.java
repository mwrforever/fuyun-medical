package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.internal.alarm.AlarmEngine;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.mapper.IotMetricMappingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.properties.TelemetryValidationProperties;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.service.ITelemetryIngestService;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.iot.vo.BindingVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 遥测入库服务实现（iot.iot_telemetry 批量写唯一入口，BRIEF-PR4-01 §3 service 行）。
 *
 * <p>五步校验编排（FU-M14-05，P2 PR-2 Task 6 深化，顺序冻结）：①档案匹配→②术语映射→③数值粗
 * 校验→④时间合理性→⑤患者关联。出库行质量语义=GOOD（正常）|SUSPECT（时间偏差）|BAD（非数值/
 * 生理极限越界），标注不丢弃是本模块口径（数据质量参差时保留原始行供质量统计，禁静默丢弃）。
 * 批量边界（宪法 A.4.3-14）：档案/映射/字典/绑定四类查询全部批级——整批各恰好一次 IN 查询，
 * 行级判定纯内存（拒循环内单查）；Redis 防刷屏键仅在映射缺失行触发且为单命令轻量操作。
 *
 * <p>步骤语义：①档案匹配（批级一次 device_id IN 查询取 product_id，供映射定位；无档案设备按
 * 无映射直通）→②术语映射（批级一次 product_id+property_name 双 IN 查询；映射缺失按
 * RAW_PASSTHROUGH 原文直通——metric_code 记原生属性名不静默丢弃（Spec 红线），并对每设备每
 * 属性一次告警日志：Redis 键 {@code fy:iot:warn:metric-missing:{deviceId}:{prop}} SET NX EX
 * TTL 1h 防刷屏，Redis 抖动降级为静默直通不阻断入库主链路）→③数值粗校验（字典生理极限
 * physio_min/max 越界标 BAD 且数值保留入库；非数值行维持 W-7 的 BAD 口径不冲突）→④时间合理
 * 性（occurred_at 与服务器当前时刻偏差绝对值超阈值标 SUSPECT 不丢弃，默认 300s）→⑤患者关联
 * （绑定快照富化，Task 3 既有链路保留，无绑定落 NULL 仍入库）。
 *
 * <p>波形白名单通道（丢弃面在批量写之前）：字典类别=波形且病区非白名单（含无绑定/未编病区）
 * 的行丢弃 + 每批一条聚合 warn + 计数（waveform_dropped）；白名单来自
 * {@link TelemetryValidationProperties#waveformWhitelistWardIds()}，空清单=全部拒绝（安全默认）。
 *
 * <p>绑定快照经绑定域 {@link IBindingService#listActiveByDevices} 单次批量 in 查询（只取 BOUND，
 * uk_iot_binding_device_bound 保证每设备至多一条活跃绑定）→ 冗余 patient_id/visit_id → mapper
 * 多值 INSERT ON CONFLICT DO NOTHING（唯一约束冲突忽略 = 明细层幂等，返回实际插入行数）。方法级
 * 独立事务（宪法 A.4.2-7）。
 *
 * <p>B4.3 摘要推送接线：落库成功后按绑定快照病区分组，每组经 {@link ITelemetryPushService}
 * 推一帧遥测摘要（/topic/iot/telemetry/{wardId}，简报 §1.4"遥测批量落库成功后推一帧汇总；
 * 无绑定快照的帧不推送仅落库"）。推送时机遵守宪法 A.4.2-7"事务内禁止远程调用、消息发送与
 * 人工等待，对外调用在事务提交后执行"：分组数据在事务方法内组装完成，推送 I/O 经
 * TransactionSynchronizationManager 注册 afterCommit 回调延迟至<b>事务提交后</b>执行（回滚
 * 事务不推送——摘要只对已提交批次负责）；无事务同步上下文（单测直调等未经代理场景）时直接
 * 推送，行为不变。推送失败仅 error 告警不回滚落库批次——落库是主职责、推送是辅助语义。
 *
 * <p>告警引擎挂接（P2 PR-2 Task 7 / FU-M14-08，afterCommit 推送钩子形态照抄扩展）：事务提交后
 * 经 {@link com.fuyun.iot.internal.alarm.AlarmEngine}#evaluate 旁路评估三类规则源（阈值/透传/
 * 离线）——评估在 ingest 事务外（afterCommit 时点原事务已提交），引擎自身独立短事务承载落行与
 * 事件发布，不阻塞 ingest 主链路；评估失败旁路跳过不上抛不重试，error 留痕含批次规模、设备
 * 足迹采样与异常摘要（EX-16 收拢：旁路语义保留但必须可观测），不影响已提交批次。
 *
 * <p>W-7 非数值承载语义（D-9 裁决，V1005 raw_value 列）：整批全量入库不再丢弃任何行——value 可
 * 数值定型的行落 NUMERIC 值列且 raw_value 为 NULL；非数值行 value 落 NULL（哨兵值会污染生理指标
 * 统计，禁回填）、raw_value 承载原文（标量原文；对象/数组经 Jackson 树规整为紧凑 JSON 标准输出）
 * 且 quality 强制 BAD（语义 = 非数值定型标注，不阻断入库）。批次观测口径为 received/inserted/
 * unbound/non_numeric_bad/physio_bad/clock_skew_suspect/waveform_dropped 七计数（历史
 * skipped_non_numeric 丢弃口径已退役）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class TelemetryIngestServiceImpl implements ITelemetryIngestService {

    /** 紧凑 JSON 规整器（线程安全，树读入 + 标准写出去除原文内部空白；无 Boot 定制依赖，静态持有） */
    private static final ObjectMapper COMPACT_JSON_MAPPER = new ObjectMapper();

    /** 映射缺失告警防刷屏键前缀（brief 冻结形态：fy:iot:warn:metric-missing:{deviceId}:{prop}） */
    private static final String METRIC_MISSING_WARN_KEY_PREFIX = "fy:iot:warn:metric-missing:";

    /** 防刷屏键 TTL：1 小时（brief 冻结；TTL 窗口内同设备同属性仅首见告警一次） */
    private static final Duration METRIC_MISSING_WARN_TTL = Duration.ofHours(1);

    /** 评估失败留痕的设备标识采样上限：批次可跨数百设备，全量入日志有刷屏风险，采样定位即可（全量设备可按时间窗查 iot_telemetry） */
    private static final int ALARM_FAIL_LOG_DEVICE_SAMPLE = 5;

    /** 绑定域服务：生效绑定查询唯一出口（findActiveByDevice，遥测富化与设备归属查询同源） */
    private final IBindingService bindingService;

    /** 遥测明细数据访问：多值 INSERT ON CONFLICT DO NOTHING 唯一写通道 */
    private final IotTelemetryMapper telemetryMapper;

    /** STOMP 推送服务：落库成功后按病区分组推送摘要帧（/topic/iot/telemetry/{wardId}） */
    private final ITelemetryPushService pushService;

    /** 设备档案 mapper：五步之档案匹配的批查询通道（device_id → product_id，术语映射定位输入） */
    private final IotDeviceMapper deviceMapper;

    /** 物模型属性映射 mapper：五步之术语映射的批查询通道（product_id+property_name → MDC 编码） */
    private final IotMetricMappingMapper metricMappingMapper;

    /** MDC 字典 mapper：五步之数值粗校验与波形白名单通道的批查询通道（metric_code → 类别/生理边界） */
    private final IotMetricDictMapper metricDictMapper;

    /** String 模板（A.5-1）：映射缺失告警防刷屏键的 SET NX EX 门闸 */
    private final StringRedisTemplate redisTemplate;

    /** 五步校验配置属性：时间合理性阈值与波形白名单病区清单 */
    private final TelemetryValidationProperties properties;

    /** 告警引擎（Task 7 / FU-M14-08）：afterCommit 后旁路评估三类规则源，独立短事务不阻塞主链 */
    private final AlarmEngine alarmEngine;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param bindingService      绑定域服务，非空；来源：同模块 service 装配链
     * @param telemetryMapper     遥测明细 mapper，非空；来源：同模块 mapper 包
     * @param pushService         STOMP 推送服务，非空；来源：IotConfig 装配链
     * @param deviceMapper        设备档案 mapper，非空；来源：同模块 mapper 包
     * @param metricMappingMapper 物模型属性映射 mapper，非空；来源：同模块 mapper 包
     * @param metricDictMapper    MDC 字典 mapper，非空；来源：同模块 mapper 包
     * @param redisTemplate       String 模板（A.5-1），非空；来源：Boot Redis 自动配置
     * @param properties          五步校验配置属性，非空；来源：fuyun-app IotConfig 属性注册
     * @param alarmEngine         告警引擎，非空；afterCommit 后旁路评估（Task 7）
     */
    public TelemetryIngestServiceImpl(
            IBindingService bindingService,
            IotTelemetryMapper telemetryMapper,
            ITelemetryPushService pushService,
            IotDeviceMapper deviceMapper,
            IotMetricMappingMapper metricMappingMapper,
            IotMetricDictMapper metricDictMapper,
            StringRedisTemplate redisTemplate,
            TelemetryValidationProperties properties,
            AlarmEngine alarmEngine) {
        this.bindingService = bindingService;
        this.telemetryMapper = telemetryMapper;
        this.pushService = pushService;
        this.deviceMapper = deviceMapper;
        this.metricMappingMapper = metricMappingMapper;
        this.metricDictMapper = metricDictMapper;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.alarmEngine = alarmEngine;
    }

    @Override
    @Transactional
    public int ingest(List<StandardTelemetryMessage> batch) {
        // 空批次防御：直接返回零行（避免空 IN 列表与空 VALUES 生成非法 SQL）
        if (batch.isEmpty()) {
            return 0;
        }
        List<String> deviceIds = batch.stream()
                .map(StandardTelemetryMessage::deviceId)
                .distinct()
                .toList();

        // 步骤一 档案匹配（批级一次）：device_id → product_id，术语映射定位输入；无档案设备按无映射直通
        Map<String, String> productIdByDevice = loadDeviceProductIds(deviceIds);

        // 患者关联（步骤五）数据源：绑定快照单次批量查询（Task 3 既有链路保留，宪法 A.4.3-14）：
        // distinct 设备集合入参，只取 BOUND 生效绑定，patient/visit/ward 供快照冗余与摘要推送分组
        Map<String, BindingVO> boundByDeviceId = new HashMap<>(deviceIds.size());
        // 循环前批量取数（BE-C4 判据①形态收拢）：生效绑定单次装载，循环内纯迭代组装映射
        List<BindingVO> bindings = bindingService.listActiveByDevices(deviceIds);
        for (BindingVO binding : bindings) {
            // 快照映射组装（纯内存）：uk_iot_binding_device_bound 保证每设备至多一条活跃绑定
            boundByDeviceId.put(binding.deviceId(), binding);
        }

        // 步骤二 术语映射（批级一次）：product_id+property_name 双 IN 查询取批内全部映射行
        Map<String, Map<String, String>> mappingByProduct = loadMetricMappings(batch, productIdByDevice);
        // 第一遍行处理（映射判定纯内存）：解析结果与批次下标对齐，同时收集去重编码供字典批查询；
        // 缺映射行按 RAW_PASSTHROUGH 原生属性名直通（Spec 红线：失配期间原样入库不静默丢弃）
        List<String> resolvedMetricCodes = new ArrayList<>(batch.size());
        Set<String> distinctMetricCodes = new HashSet<>(batch.size() * 2);
        for (StandardTelemetryMessage message : batch) {
            // 无档案设备 productId 为 null：不可作映射表键查询（ImmutableMap 拒 null 键），直接走直通面
            String productId = productIdByDevice.get(message.deviceId());
            String mapped = productId == null
                    ? null
                    : mappingByProduct.getOrDefault(productId, Map.of()).get(message.metricCode());
            String metricCode;
            if (mapped != null) {
                metricCode = mapped;
            } else {
                metricCode = message.metricCode();
                warnMetricMissingOnce(message.deviceId(), metricCode);
            }
            resolvedMetricCodes.add(metricCode);
            distinctMetricCodes.add(metricCode);
        }
        // 数值粗校验/波形通道判别数据源（批级一次）：metric_code → 字典行（类别/生理极限边界）
        Map<String, IotMetricDictEntity> dictByMetricCode = loadMetricDicts(distinctMetricCodes);
        Set<Long> waveformWhitelist = new HashSet<>(properties.waveformWhitelistWardIds());

        // 第二遍行处理：③数值粗校验→④时间合理性→⑤患者关联（toEntity 内）+ 波形白名单通道丢弃面
        List<IotTelemetryEntity> entities = new ArrayList<>(batch.size());
        long nonNumericBadCount = 0;
        long physioBadCount = 0;
        long clockSkewSuspectCount = 0;
        long waveformDroppedCount = 0;
        // 偏差基准时刻批内单次采样：同批行用同一基准，判定口径一致且免循环内重复取时
        Instant now = Instant.now();
        for (int i = 0; i < batch.size(); i++) {
            StandardTelemetryMessage message = batch.get(i);
            IotMetricDictEntity dict = dictByMetricCode.get(resolvedMetricCodes.get(i));
            BindingVO binding = boundByDeviceId.get(message.deviceId());
            // 波形白名单通道：字典类别=波形且病区非白名单（含无绑定/未编病区）→ 丢弃 + 计数（聚合 warn）
            if (dict != null
                    && dict.getCategory() == MetricCategory.WAVEFORM
                    && !isWardWhitelisted(binding, waveformWhitelist)) {
                waveformDroppedCount++;
                continue;
            }
            // 步骤四 时间合理性：偏差绝对值（过去/未来同判）超阈值标 SUSPECT，标注不丢弃
            boolean clockSkewed =
                    Duration.between(message.occurredAt(), now).abs().compareTo(properties.clockSkewThreshold()) > 0;
            IotTelemetryEntity entity = toEntity(message, binding, resolvedMetricCodes.get(i), dict, clockSkewed);
            if (entity.getValue() == null) {
                // 非数值定型行计数（W-7 既有口径，quality 已强制 BAD）
                nonNumericBadCount++;
            } else if (entity.getQuality() == TelemetryQuality.BAD) {
                // 生理极限越界行计数（数值保留入库，供 FU-M14-11 质量统计）
                physioBadCount++;
            }
            if (entity.getQuality() == TelemetryQuality.SUSPECT) {
                clockSkewSuspectCount++;
            }
            entities.add(entity);
        }
        if (waveformDroppedCount > 0) {
            // 每批一条聚合 warn（禁循环内逐行打印；丢弃计数随批次 info 摘要同步留痕）
            log.warn("波形白名单通道丢弃非白名单病区波形行：waveform_dropped={}", waveformDroppedCount);
        }
        // 数据库写操作：批量写唯一键冲突行忽略，返回实际插入行数（冲突行不计入 = 明细层幂等语义）；
        // 整批皆被通道丢弃时跳过批量写（防空 VALUES 非法 SQL），仅留观测日志
        int inserted = entities.isEmpty() ? 0 : telemetryMapper.insertBatchIgnoreConflict(entities);
        log.info(
                "遥测批量落库完成：received={}，inserted={}，unbound={}，non_numeric_bad={}，physio_bad={}，"
                        + "clock_skew_suspect={}，waveform_dropped={}",
                batch.size(),
                inserted,
                countUnbound(entities),
                nonNumericBadCount,
                physioBadCount,
                clockSkewSuspectCount,
                waveformDroppedCount);
        // 摘要推送分组数据在事务方法内组装完成（快照 wardId 分组），推送 I/O 移出事务执行——
        // 宪法 A.4.2-7"事务内禁止远程调用、消息发送与人工等待，对外调用在事务提交后执行"
        Map<Long, List<IotTelemetryEntity>> writtenByWardId = groupWrittenByWard(entities, boundByDeviceId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务同步激活（经 Spring 代理调用）：注册 afterCommit 回调，事务提交后才执行推送；
            // 回调运行于提交线程且不新开事务（SimpleBroker 进程内直推，无二次远程调用）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    pushSummariesByWard(writtenByWardId);
                    evaluateAlarms(entities);
                }
            });
        } else {
            // 无事务同步上下文（单测直调等未经代理场景）：无事务可出，行为不变直接推送
            pushSummariesByWard(writtenByWardId);
            evaluateAlarms(entities);
        }
        return inserted;
    }

    /**
     * 告警引擎旁路评估（afterCommit 推送钩子形态照抄扩展，Task 7）：评估失败旁路吞并不上抛不
     * 重试，评估告警旁路跳过不影响已提交落库批次（评估是旁路语义，落库是主职责）。失败留痕按
     * EX-16/BE-C3-06 收拢口径补强（旁路语义保留但必须可观测）：error 含批次规模、设备足迹
     * （去重数与采样标识防刷屏）与异常类名消息，堆栈按模块惯例随行。
     *
     * @param entities 本批已写入遥测实体，非空
     */
    private void evaluateAlarms(List<IotTelemetryEntity> entities) {
        try {
            alarmEngine.evaluate(new AlarmEngine.TelemetryBatch(entities));
        } catch (RuntimeException e) {
            // 评估失败旁路吞并：不上抛不重试（旁路语义保留），留痕后本批评估告警旁路跳过；
            // 批次可跨数百设备，设备标识采样打印防刷屏，堆栈按模块惯例随行
            List<String> deviceIds = entities.stream()
                    .map(IotTelemetryEntity::getDeviceId)
                    .distinct()
                    .toList();
            log.error(
                    "告警引擎评估失败，评估告警旁路跳过（不影响已提交批次）：count={}，devices={}，" + "sample={}，原因={}: {}",
                    entities.size(),
                    deviceIds.size(),
                    deviceIds.stream().limit(ALARM_FAIL_LOG_DEVICE_SAMPLE).toList(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
        }
    }

    /**
     * 档案匹配批查询（步骤一）：批内 distinct 设备集合单次 IN 查询取 device_id → product_id。
     *
     * <p>无档案设备不入映射（值缺省 null）：其遥测行按无映射 RAW_PASSTHROUGH 直通，不阻断入库
     * （设备先上报后建档的窗口期语义）。{@code @TableLogic} 自动过滤已删档案行。
     *
     * @param deviceIds 批内去重设备号集合，非空
     * @return deviceId → productId 映射（productId 可能为 null——档案在册但未挂产品），非空
     */
    private Map<String, String> loadDeviceProductIds(List<String> deviceIds) {
        // 数据库读操作：批内 distinct 设备集合单次 IN 查询（宪法 A.4.3-14，禁循环内单查）
        List<IotDeviceEntity> devices = deviceMapper.selectList(
                Wrappers.<IotDeviceEntity>lambdaQuery().in(IotDeviceEntity::getDeviceId, deviceIds));
        Map<String, String> productIdByDevice = new HashMap<>(devices.size());
        for (IotDeviceEntity device : devices) {
            productIdByDevice.put(device.getDeviceId(), device.getProductId());
        }
        return productIdByDevice;
    }

    /**
     * 术语映射批查询（步骤二）：批内有档案设备派生的 (product_id, property_name) 组合，按双 IN
     * 条件一次取回全部映射行，组装为 product_id → (property_name → MDC 编码) 嵌套映射。
     *
     * <p>嵌套映射避免拼接复合键的分隔符歧义；无档案设备（无 product_id）不参与查询条件。
     * {@code @TableLogic} 自动过滤已删映射行（管理台全量替换重建的旧代映射不参与判定）。
     *
     * @param batch            遥测消息批次，非空
     * @param productIdByDevice 档案匹配产物（deviceId → productId），非空
     * @return productId → (propertyName → MDC 编码) 嵌套映射；批内无可定位组合时为空映射，非空
     */
    private Map<String, Map<String, String>> loadMetricMappings(
            List<StandardTelemetryMessage> batch, Map<String, String> productIdByDevice) {
        Set<String> productIds = new HashSet<>();
        Set<String> propertyNames = new HashSet<>();
        for (StandardTelemetryMessage message : batch) {
            String productId = productIdByDevice.get(message.deviceId());
            if (productId != null) {
                // 有档案设备才具备映射定位条件（product_id+property_name 联合键）；无档案设备走直通面
                productIds.add(productId);
                propertyNames.add(message.metricCode());
            }
        }
        if (productIds.isEmpty()) {
            return Map.of();
        }
        // 数据库读操作：product_id/property_name 双 IN 一次取回批内全部映射行（宪法 A.4.3-14）
        List<IotMetricMappingEntity> mappings =
                metricMappingMapper.selectList(Wrappers.<IotMetricMappingEntity>lambdaQuery()
                        .in(IotMetricMappingEntity::getProductId, productIds)
                        .in(IotMetricMappingEntity::getPropertyName, propertyNames));
        Map<String, Map<String, String>> mappingByProduct = new HashMap<>();
        for (IotMetricMappingEntity mapping : mappings) {
            mappingByProduct
                    .computeIfAbsent(mapping.getProductId(), key -> new HashMap<>())
                    .put(mapping.getPropertyName(), mapping.getMetricCode());
        }
        return mappingByProduct;
    }

    /**
     * MDC 字典批查询：批内解析后编码集合单次 IN 查询取 metric_code → 字典行。
     *
     * <p>字典行承载数值粗校验的生理极限边界（physio_min/max）与波形白名单通道的类别判别
     * （category=波形）；字典未登记的编码不参与校验（直通行维持既有质量口径）。
     *
     * @param metricCodes 批内解析后 metric_code 去重集合，非空（空批次已在上游短路，集合恒非空）
     * @return metricCode → 字典实体映射，非空
     */
    private Map<String, IotMetricDictEntity> loadMetricDicts(Set<String> metricCodes) {
        // 数据库读操作：批内解析后编码集合单次 IN 查询（自然键 metric_code，宪法 A.4.3-14）
        List<IotMetricDictEntity> dicts = metricDictMapper.selectList(
                Wrappers.<IotMetricDictEntity>lambdaQuery().in(IotMetricDictEntity::getMetricCode, metricCodes));
        Map<String, IotMetricDictEntity> dictByMetricCode = new HashMap<>(dicts.size());
        for (IotMetricDictEntity dict : dicts) {
            dictByMetricCode.put(dict.getMetricCode(), dict);
        }
        return dictByMetricCode;
    }

    /**
     * 映射缺失告警防刷屏门闸：每设备每属性仅首见放行一条告警日志（Redis SET NX EX 单命令原子，
     * TTL 1h——brief 冻结口径），TTL 窗口内重复失配静默放行不刷屏。
     *
     * <p>Redis 抖动降级：防刷屏是辅助语义，Redis 异常不阻断遥测入库主链路（与摘要推送吞并口径
     * 同款），降级为本窗口省略告警并留 warn 留痕。
     *
     * @param deviceId     设备号，非空；来源：遥测消息
     * @param propertyName 原生物模型属性名（即直通入库的 metric_code），非空；来源：遥测消息
     */
    private void warnMetricMissingOnce(String deviceId, String propertyName) {
        String key = METRIC_MISSING_WARN_KEY_PREFIX + deviceId + ":" + propertyName;
        try {
            // SET NX EX：首见设备+属性组合返回 true 放行告警，TTL 窗口内重复返回 false 静默
            Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(key, "1", METRIC_MISSING_WARN_TTL);
            if (!Boolean.TRUE.equals(firstSeen)) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("映射缺失防刷屏键操作失败（不阻断入库，本窗口告警省略）：deviceId={}，property={}", deviceId, propertyName, e);
            return;
        }
        log.warn(
                "遥测术语映射缺失，按 RAW_PASSTHROUGH 原生属性名直通入库：deviceId={}，property={}" + "（1 小时内同设备同属性仅本条告警）",
                deviceId,
                propertyName);
    }

    /**
     * 波形白名单判定：绑定快照病区在白名单清单内方为获准。
     *
     * <p>无绑定/未编病区一律视为非白名单（波形仅对明确授权病区落库）；白名单空集 = 全部拒绝
     * （安全默认：未显式授权不收波形，防高体量波形数据灌库）。
     *
     * @param binding   该设备的 BOUND 绑定快照，可为 null（无绑定）
     * @param whitelist 白名单病区集合（配置装载产物），非空
     * @return true=病区在白名单内，波形行获准落库
     */
    private static boolean isWardWhitelisted(BindingVO binding, Set<Long> whitelist) {
        return binding != null && binding.wardId() != null && whitelist.contains(binding.wardId());
    }

    /**
     * 按绑定快照病区分组本批已写入实体（纯内存组装，无 I/O，供推送时机分派复用）。
     *
     * <p>分组语义：wardId 取设备 BOUND 绑定的写入时快照（iot_binding.ward_id），无绑定或绑定
     * 未编病区的行仅落库不入组；每组一帧（条数/items/occurredAt 上界见推送服务载荷契约）。
     *
     * @param entities        已组装并落库的遥测实体批次，非空
     * @param boundByDeviceId 绑定快照映射（deviceId → BOUND 绑定视图，含 wardId），非空
     * @return 病区 → 本批该病区已写入实体列表（值列表非空），非空；无归属行不出现
     */
    private static Map<Long, List<IotTelemetryEntity>> groupWrittenByWard(
            List<IotTelemetryEntity> entities, Map<String, BindingVO> boundByDeviceId) {
        Map<Long, List<IotTelemetryEntity>> writtenByWardId = new HashMap<>();
        for (IotTelemetryEntity entity : entities) {
            BindingVO binding = boundByDeviceId.get(entity.getDeviceId());
            Long wardId = binding == null ? null : binding.wardId();
            if (wardId != null) {
                // 按病区分组：同病区多设备/多帧合并为一帧摘要（每批每病区一帧，简报 §1.4 推送频率口径）
                writtenByWardId
                        .computeIfAbsent(wardId, key -> new ArrayList<>())
                        .add(entity);
            }
        }
        return writtenByWardId;
    }

    /**
     * 执行按病区分组的摘要帧推送（推送 I/O 执行点：调用方须保证已处于事务提交后或无事务上下文）。
     *
     * <p>推送失败仅 error 告警不中断其余病区批次——推送是辅助语义，落库批次与客户端确认语义
     * 不受影响（broker 抖动不阻断遥测持久化，失败帧靠唯一约束重推兜底）。
     *
     * @param writtenByWardId 病区 → 已写入实体列表分组（{@link #groupWrittenByWard} 产物），非空
     */
    private void pushSummariesByWard(Map<Long, List<IotTelemetryEntity>> writtenByWardId) {
        writtenByWardId.forEach((wardId, written) -> {
            try {
                pushService.pushSummary(written, wardId);
            } catch (RuntimeException e) {
                // 推送失败吞并：错误留痕后批次照常返回（辅助语义不阻断主链路，javadoc 声明）
                log.error("遥测摘要推送失败（不影响落库批次）：wardId={}，count={}，原因={}", wardId, written.size(), e.getMessage(), e);
            }
        });
    }

    /**
     * 统计无绑定快照的行数（日志观测口径：未关联入库占比是绑定质量运营指标）。
     *
     * @param entities 已组装的遥测实体批次，非空
     * @return patient_id 为空的行数
     */
    private long countUnbound(List<IotTelemetryEntity> entities) {
        return entities.stream().filter(entity -> entity.getPatientId() == null).count();
    }

    /**
     * 解析 CF-7 value 字符串为 NUMERIC 定型值。
     *
     * @param message 标准遥测消息，非空
     * @return 数值定型结果；不可解析返回 null（调用方落 raw_value 原文承载）
     */
    private static BigDecimal parseValue(StandardTelemetryMessage message) {
        try {
            return new BigDecimal(message.value());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 生理极限越界判定（步骤三 数值粗校验）：双边界各自独立生效（字典可只配单边界），越任一界
     * 即 BAD。字典未登记（null）或双边界均未配置时不校验（维持既有质量口径，不误伤无字典语义的
     * 直通行）。
     *
     * @param value 数值定型结果，非空
     * @param dict  MDC 字典行，可为 null（字典未登记）
     * @return true=越生理极限下界或上界
     */
    private static boolean isOutOfPhysioRange(BigDecimal value, IotMetricDictEntity dict) {
        if (dict == null) {
            return false;
        }
        BigDecimal min = dict.getPhysioMin();
        BigDecimal max = dict.getPhysioMax();
        // 边界比较用 compareTo（BigDecimal 等值判定含标度差异，equals 会把 72 与 72.0 判不等）
        return (min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0);
    }

    /**
     * 非数值原文承载规整（W-7）：标量以原文承载；形似对象/数组的原文经 Jackson 树读入 + 标准写出
     * 规整为紧凑 JSON（去除键值/元素间空白，无空格标准输出）。入参由 CF-7 契约保证非空
     * （StandardTelemetryMessage.value 非空），且本方法仅在 parseValue 判定不可数值定型后调用
     * （null 入参会在定型处先行暴露，此处不重复防御）。
     *
     * @param raw 遥测原文，非空（CF-7 契约）
     * @return 承载文本：对象/数组为紧凑 JSON，其余为原文
     */
    private static String toRawValueText(String raw) {
        String trimmed = raw.trim();
        boolean shapedAsJson = (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
        if (!shapedAsJson) {
            // 标量原文：原样承载（quality=BAD 已完成非数值定型标注）
            return raw;
        }
        try {
            // 形似对象/数组：树规整为紧凑 JSON（标准输出无空格，规整重复上报的格式漂移）
            JsonNode node = COMPACT_JSON_MAPPER.readTree(trimmed);
            return COMPACT_JSON_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // 形似 JSON 但非法（如 "{"a": }"）：按标量原文承载，不因规整失败丢行（quality=BAD 兜底标注）
            return raw;
        }
    }

    /**
     * 标准遥测消息 → 超表行实体（枚举 code 已由解析器校验值域，fromCode 不会失败）。
     *
     * <p>质量判定优先级（出库行质量语义，brief Produces 冻结）：非数值 BAD > 生理极限越界 BAD >
     * 时间偏差 SUSPECT > GOOD——越严重越优先，保证 BAD 不被 SUSPECT 降级。W-7 分流：value 可数值
     * 定型 → NUMERIC 值列、raw_value 保持 NULL；不可数值定型 → value 落 NULL、raw_value 承载
     * 原文/紧凑 JSON。
     *
     * @param message    标准遥测消息，非空
     * @param binding    该设备的 BOUND 绑定快照，可为 null（无绑定：患者/就诊列落 NULL）
     * @param metricCode 五步之术语映射解析后的入库编码（命中=MDC 编码；缺失=原生属性名直通），非空
     * @param dict       解析后编码的 MDC 字典行，可为 null（字典未登记：跳过生理极限校验）
     * @param clockSkewed true=occurred_at 偏差已超阈值（步骤四判定产物）
     * @return 遥测实体，非空
     */
    private static IotTelemetryEntity toEntity(
            StandardTelemetryMessage message,
            BindingVO binding,
            String metricCode,
            IotMetricDictEntity dict,
            boolean clockSkewed) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId(message.deviceId());
        // 步骤五 患者关联：写入时绑定快照冗余，无绑定落 NULL（未关联仍入库，14-iot §3.3）
        entity.setPatientId(binding == null ? null : binding.patientId());
        entity.setVisitId(binding == null ? null : binding.visitId());
        entity.setMetricCode(metricCode);
        BigDecimal value = parseValue(message);
        entity.setValue(value);
        if (value == null) {
            // W-7 既有口径：非数值行 raw_value 承载原文 + quality 强制 BAD（标注不阻断入库）
            entity.setRawValue(toRawValueText(message.value()));
            entity.setQuality(TelemetryQuality.BAD);
        } else if (isOutOfPhysioRange(value, dict)) {
            // 步骤三 数值粗校验：生理极限越界标 BAD，数值保留入库（标注不丢弃）
            entity.setQuality(TelemetryQuality.BAD);
        } else if (clockSkewed) {
            // 步骤四 时间合理性：偏差超阈值标 SUSPECT 不丢弃
            entity.setQuality(TelemetryQuality.SUSPECT);
        } else {
            entity.setQuality(TelemetryQuality.GOOD);
        }
        entity.setUnit(message.unit());
        entity.setOccurredAt(OffsetDateTime.ofInstant(message.occurredAt(), ZoneOffset.UTC));
        entity.setSource(TelemetrySource.fromCode(message.source()));
        return entity;
    }
}
