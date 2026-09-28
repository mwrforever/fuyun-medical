package com.fuyun.iot.service.impl;

import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.IotTelemetryQueryPort;
import com.fuyun.iot.api.TelemetryPoint;
import com.fuyun.iot.dto.TelemetrySeriesRequest;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.service.ITelemetryQueryService;
import com.fuyun.iot.vo.BindingVO;
import com.fuyun.iot.vo.TelemetryLatestVO;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 遥测时序查询服务实现（FU-M14-06，P2 PR-2 Task 10）：三档查询路由 + 三维度范围展开 + 最新值
 * Redis 快照兜底；同时实现 ward 模块消费端口 {@link IotTelemetryQueryPort}（Task 12 冷链温度
 * 曲线/输液看板经依赖注入消费）。
 *
 * <p><b>三档路由（14-iot Spec §3.2，保护数据库的查询分级）</b>：查询窗 ≤24h 且 raw（显式或缺省）
 * 走明细档（idx 命中的点查）；超 24h 或显式 1min/1h 走连续聚合档（V1011 cagg_1min/cagg_1h，
 * 实时查询语义自动联合未物化窗口）；超 90 天无论入参强制 cagg_1h（明细 90 天保留策略下大范围
 * 明细已淘汰，且大范围扫描是唯一需主动防御的查询形态）。
 *
 * <p><b>范围展开</b>：device 维度直查；patient 维度强制数据范围校验（{@link PatientContextResolver}
 * resolve 状态判断——患者 api P0 无操作者级数据范围端口，冻结/合并档案拒绝查询，语义细分 IOT-1019；
 * 归一主档 id 参与查询，M02 红线 1 同源），明细档按 patient_id 直查（idx_iot_telemetry_patient 准入），
 * 聚合档按绑定历史展开设备集合（cagg 无患者列）；ward 维度按 BOUND 绑定展开设备集合。
 *
 * <p>最新值快照承载形态（AlarmEngine 写入面对齐）：键 {@code fy:iot:snapshot:latest:{deviceId}:
 * {metricCode}}，值 {@code value|occurredAt毫秒} 管道文本；快照为兜底辅助面，Redis 异常降级为空值
 * 视图不抛错（与写入侧降级口径同源）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class TelemetryQueryServiceImpl implements ITelemetryQueryService, IotTelemetryQueryPort {

    /** 明细档最大查询窗（Spec §3.2：≤24h 且点数受控走明细，超线转连续聚合保护明细扫描） */
    private static final Duration MAX_DETAIL_WINDOW = Duration.ofHours(24);

    /** 强制 1 小时聚合阈值（Spec §3.2：超 90 天强制走聚合——明细保留策略边界同源） */
    private static final Duration FORCE_CAGG_1H_THRESHOLD = Duration.ofDays(90);

    /** 最新值快照键前缀（brief 冻结形态：fy:iot:snapshot:latest:，与 AlarmEngine 写入面同源） */
    private static final String LATEST_SNAPSHOT_KEY_PREFIX = "fy:iot:snapshot:latest:";

    /** 患者主档状态：MERGED（已合并——查询侧同绑定校验链拒绝，归属已收敛禁跨档读取） */
    private static final String PATIENT_STATUS_MERGED = "MERGED";

    /** 路由档位：明细 / 1 分钟聚合 / 1 小时聚合（连续聚合表名白名单唯一来源，禁外部输入） */
    private enum Route {
        /** 明细档（≤24h 且 raw） */
        DETAIL,
        /** 1 分钟连续聚合档 */
        CAGG_1MIN,
        /** 1 小时连续聚合档 */
        CAGG_1H;

        /**
         * 取本档位连续聚合视图全名。
         *
         * @return 视图全名（V1011 产物），非空；仅 DETAIL 档不可达（调用方保证）
         */
        String caggTable() {
            return this == CAGG_1H ? "iot.cagg_1h" : "iot.cagg_1min";
        }
    }

    /** 遥测明细 mapper：明细档查询与聚合档查询共用读通道 */
    private final IotTelemetryMapper telemetryMapper;

    /** 绑定 mapper：patient 维度聚合档的绑定历史设备展开（distinct 设备清单） */
    private final IotBindingMapper bindingMapper;

    /** 绑定域服务：ward 维度 BOUND 绑定展开（与推送/告警病区设备集合同源口径） */
    private final IBindingService bindingService;

    /** 患者上下文解析契约（CF-3）：patient 维度数据范围校验与主档归一唯一出口 */
    private final PatientContextResolver patientResolver;

    /** String 模板（A.5-1）：最新值快照兜底读取通道 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param telemetryMapper 遥测明细 mapper，非空；来源：同模块 mapper 包
     * @param bindingMapper   绑定 mapper，非空；来源：同模块 mapper 包
     * @param bindingService  绑定域服务，非空；来源：IotConfig 装配链
     * @param patientResolver 患者上下文解析契约，非空；来源：fuyun-patient api（容器实现注入）
     * @param redisTemplate   String 模板（A.5-1），非空；来源：Boot Redis 自动配置
     */
    public TelemetryQueryServiceImpl(
            IotTelemetryMapper telemetryMapper,
            IotBindingMapper bindingMapper,
            IBindingService bindingService,
            PatientContextResolver patientResolver,
            StringRedisTemplate redisTemplate) {
        this.telemetryMapper = telemetryMapper;
        this.bindingMapper = bindingMapper;
        this.bindingService = bindingService;
        this.patientResolver = patientResolver;
        this.redisTemplate = redisTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TelemetryPoint> series(TelemetrySeriesRequest request) {
        return series(
                request.scope(),
                request.deviceId(),
                request.patientId(),
                request.wardId(),
                request.metricCode(),
                request.from(),
                request.to(),
                request.granularity());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TelemetryPoint> series(
            String deviceId, String metricCode, OffsetDateTime from, OffsetDateTime to, String granularity) {
        return series("device", deviceId, null, null, metricCode, from, to, granularity);
    }

    /**
     * 三档路由查询统一入口（REST 多维度与 Port 单设备两形态收敛于此）。
     *
     * @param scope       查询维度 device|patient|ward，非空
     * @param deviceId    设备标识（device 维度必填），可空
     * @param patientId   患者主索引（patient 维度必填），可空
     * @param wardId      病区 ID（ward 维度必填），可空
     * @param metricCode  指标编码，非空
     * @param from        起始时刻（含），非空
     * @param to          结束时刻（不含），非空
     * @param granularity 档位 raw|1min|1h，可空=自动
     * @return 时序点清单（time 升序），非空
     * @throws BizException IOT-1019（400；时窗/档位/维度标识非法或患者数据范围校验不通过）
     */
    private List<TelemetryPoint> series(
            String scope,
            String deviceId,
            Long patientId,
            Long wardId,
            String metricCode,
            OffsetDateTime from,
            OffsetDateTime to,
            String granularity) {
        // 时窗合法性：from 严格早于 to（空窗/倒挂均 IOT-1019，半开区间边界语义）
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BizException(
                    IotErrorCode.TELEMETRY_QUERY_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "查询时窗非法（要求 from 早于 to）：" + from + " ~ " + to);
        }
        Route route = resolveRoute(from, to, granularity);
        switch (scope) {
            case "device":
                if (deviceId == null || deviceId.isBlank()) {
                    throw queryInvalid("scope=device 要求 deviceId 非空");
                }
                return queryByRoute(route, metricCode, from, to, List.of(deviceId), null, deviceId);
            case "patient":
                if (patientId == null) {
                    throw queryInvalid("scope=patient 要求 patientId 非空");
                }
                return seriesByPatient(route, metricCode, from, to, patientId);
            case "ward":
                if (wardId == null) {
                    throw queryInvalid("scope=ward 要求 wardId 非空");
                }
                // 数据库读操作：BOUND 绑定展开病区设备集合（与推送/告警病区设备口径同源）
                List<String> wardDevices = bindingService.listByWard(wardId).stream()
                        .map(BindingVO::deviceId)
                        .toList();
                return queryByRoute(route, metricCode, from, to, wardDevices, null, null);
            default:
                // @Pattern 已拦截词表外 scope，此处兜底直调服务层的编程错误
                throw queryInvalid("scope 只允许 device/patient/ward：" + scope);
        }
    }

    /**
     * patient 维度查询：resolve 归一 + 数据范围校验（冻结/合并拒绝，IOT-1019 语义细分）后按档位
     * 选数据源——明细档按 patient_id 直查（索引准入），聚合档按绑定历史展开设备集合。
     *
     * @param route     路由档位，非空
     * @param metricCode 指标编码，非空
     * @param from      起始时刻（含），非空
     * @param to        结束时刻（不含），非空
     * @param patientId 调用方入参患者 id，非空
     * @return 时序点清单，非空
     * @throws BizException IOT-1019（400；数据范围校验不通过）
     */
    private List<TelemetryPoint> seriesByPatient(
            Route route, String metricCode, OffsetDateTime from, OffsetDateTime to, Long patientId) {
        // 患者面数据范围校验（照抄 BindingServiceImpl 校验链形态）：resolve 归一主档，冻结（blocked）
        // 或合并中（MERGED）拒绝查询——患者 api P0 无操作者级数据范围端口，resolve 状态判断为唯一判定面
        PatientContextView view = patientResolver.resolve(patientId);
        if (view.blocked() || PATIENT_STATUS_MERGED.equals(view.status())) {
            log.warn(
                    "患者维度遥测查询拒绝（数据范围校验不通过）：patientId={}，resolvedPatientId={}，status={}",
                    patientId,
                    view.resolvedPatientId(),
                    view.status());
            throw queryInvalid("患者数据范围校验不通过，禁止查询：" + patientId);
        }
        long resolvedPatientId = view.resolvedPatientId();
        if (route == Route.DETAIL) {
            // 明细档按 patient_id 直查（写入时绑定快照冗余列，idx_iot_telemetry_patient 准入）
            return telemetryMapper.selectRawSeries(metricCode, from, to, null, resolvedPatientId, null);
        }
        // 数据库读操作：绑定历史展开设备集合（只增历史含 UNBOUND 行，跨就诊复用设备 distinct 去重）
        List<String> deviceIds = bindingMapper.selectDeviceIdsByPatientId(resolvedPatientId);
        if (deviceIds.isEmpty()) {
            // 无历史绑定设备：聚合表无患者列无解，直接空清单（防空 IN 非法 SQL）
            return List.of();
        }
        return telemetryMapper.selectCaggSeries(route.caggTable(), metricCode, from, to, deviceIds);
    }

    /**
     * 按路由档位执行单指标查询（device/ward 维度共用：聚合档设备集合入参，明细档按需单设备/
     * 患者/集合条件）。
     *
     * @param route     路由档位，非空
     * @param metricCode 指标编码，非空
     * @param from      起始时刻（含），非空
     * @param to        结束时刻（不含），非空
     * @param deviceIds 设备集合（device/ward 维度展开产物），非空
     * @param patientId 患者 id（patient 明细档专用条件），可空
     * @param deviceId  单设备条件（device 明细档专用），可空
     * @return 时序点清单（time 升序），非空
     */
    private List<TelemetryPoint> queryByRoute(
            Route route,
            String metricCode,
            OffsetDateTime from,
            OffsetDateTime to,
            List<String> deviceIds,
            Long patientId,
            String deviceId) {
        if (route == Route.DETAIL) {
            // 数据库读操作：明细档查询（三个范围条件互斥，ward 空集合返回空清单防非法 SQL）
            if (deviceIds.isEmpty()) {
                return List.of();
            }
            return telemetryMapper.selectRawSeries(
                    metricCode, from, to, deviceId, patientId, deviceId == null ? deviceIds : null);
        }
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        // 数据库读操作：连续聚合档查询（tableName 为路由枚举白名单值，禁外部输入——注入防线）
        return telemetryMapper.selectCaggSeries(route.caggTable(), metricCode, from, to, deviceIds);
    }

    /**
     * 三档路由判定（Spec §3.2 冻结边界）：超 90 天恒强制 1 小时聚合；显式档位按显式值路由；
     * 缺省档按 24h 明细保护线分流。
     *
     * @param from        起始时刻（含），非空
     * @param to          结束时刻（不含），非空
     * @param granularity 档位 raw|1min|1h，可空=自动
     * @return 路由档位，非空
     * @throws BizException IOT-1019（400；档位词表外）
     */
    private static Route resolveRoute(OffsetDateTime from, OffsetDateTime to, String granularity) {
        if (granularity != null
                && !granularity.isBlank()
                && !granularity.equals("raw")
                && !granularity.equals("1min")
                && !granularity.equals("1h")) {
            // @Pattern 已拦截，此处兜底经 Port 直调的词表外档位（服务层复验不信任上游）
            throw queryInvalid("granularity 只允许 raw/1min/1h：" + granularity);
        }
        Duration window = Duration.between(from, to);
        if (window.compareTo(FORCE_CAGG_1H_THRESHOLD) > 0) {
            // 超 90 天强制 1 小时聚合（明细保留策略边界 + 大范围扫描保护，忽略显式档位）
            return Route.CAGG_1H;
        }
        if ("1h".equals(granularity)) {
            return Route.CAGG_1H;
        }
        if ("1min".equals(granularity)) {
            return Route.CAGG_1MIN;
        }
        // raw 显式或缺省：24h 明细保护线（显式 raw 超线同样拒绝明细档）
        return window.compareTo(MAX_DETAIL_WINDOW) <= 0 ? Route.DETAIL : Route.CAGG_1MIN;
    }

    @Override
    public TelemetryLatestVO latest(String deviceId, String metricCode) {
        String key = LATEST_SNAPSHOT_KEY_PREFIX + deviceId + ":" + metricCode;
        String snapshot;
        try {
            // Redis 读操作：最新值快照（AlarmEngine 写入面，值形态 value|occurredAt毫秒）
            snapshot = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            // Redis 降级：快照为兜底辅助面，读失败返回空值视图不抛错（与写入侧降级口径同源）
            log.warn("最新值快照读取失败（辅助面降级返回空值）：deviceId={}，metricCode={}，原因={}", deviceId, metricCode, e.getMessage());
            return TelemetryLatestVO.empty(deviceId, metricCode);
        }
        if (snapshot == null || snapshot.isBlank()) {
            // 快照缺席（TTL 过期/从未上报）：空值视图非错误（200 语义，调用方按无数据显示）
            return TelemetryLatestVO.empty(deviceId, metricCode);
        }
        int separator = snapshot.indexOf('|');
        if (separator <= 0) {
            // 形态漂移防御：管道分隔符缺失按快照缺席处置并留痕（写入面契约 value|epochMilli）
            log.warn("最新值快照形态非法按缺席处置：deviceId={}，metricCode={}", deviceId, metricCode);
            return TelemetryLatestVO.empty(deviceId, metricCode);
        }
        try {
            BigDecimal value = new BigDecimal(snapshot.substring(0, separator));
            Instant occurred = Instant.ofEpochMilli(Long.parseLong(snapshot.substring(separator + 1)));
            return new TelemetryLatestVO(
                    deviceId, metricCode, value, OffsetDateTime.ofInstant(occurred, ZoneOffset.UTC));
        } catch (NumberFormatException e) {
            log.warn("最新值快照解析失败按缺席处置：deviceId={}，metricCode={}，原因={}", deviceId, metricCode, e.getMessage());
            return TelemetryLatestVO.empty(deviceId, metricCode);
        }
    }

    /**
     * 构造 IOT-1019 业务异常（400，查询参数非法语义统一出口）。
     *
     * @param message 违规原因描述，非空
     * @return 业务异常，非空
     */
    private static BizException queryInvalid(String message) {
        return new BizException(IotErrorCode.TELEMETRY_QUERY_INVALID, HttpStatus.BAD_REQUEST, message);
    }
}
