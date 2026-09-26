package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.BindingChangedPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.BindDeviceRequest;
import com.fuyun.iot.dto.BindingQueryRequest;
import com.fuyun.iot.dto.UnbindDeviceRequest;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.vo.BindingVO;
import com.fuyun.patient.api.OngoingVisitQuery;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备患者绑定管理服务实现（iot.iot_binding 唯一写入口）：绑定校验链、解绑双 CAS 状态机与
 * iot.binding.changed 域事件发布。
 *
 * <p>红线口径：绑定历史只增（V400 部分唯一索引 uk_iot_binding_device_bound 兜底"同一设备同一
 * 时刻至多一条绑定中"——应用层前置计数 + 插入竞态翻译 IOT-1010 双层防御）；解绑走
 * BOUND→UNBINDING→UNBOUND 双 CAS 条件迁移（{@link IotBindingMapper#casMarkUnbinding}/
 * {@link IotBindingMapper#casMarkUnbound} 影响行数判定，任一步落败抛 IOT-1010 整体回滚，
 * 杜绝半迁移状态）；患者落行取 {@link PatientContextResolver} 归一后主档 id（M02 红线 1：
 * 业务数据必须关联 resolvedPatientId，禁从档 id 直连）；状态迁移在事务内发布 IotDomainEvent，
 * AFTER_COMMIT 直发 MQ 归 {@link IotDomainPublisher}（事务内禁直发 MQ 红线的进程内桥）。
 * UNBIND 载荷患者/就诊置 null（BindingChangedPayload 冻结契约"解绑后无患者为 null"）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class BindingServiceImpl extends ServiceImpl<IotBindingMapper, IotBindingEntity> implements IBindingService {

    /** 绑定变更方向：BIND（bind 落行） */
    private static final String CHANGE_TYPE_BIND = "BIND";

    /** 绑定变更方向：UNBIND（解绑双 CAS 迁移完成） */
    private static final String CHANGE_TYPE_UNBIND = "UNBIND";

    /** 患者主档状态：MERGED（已合并——归一后仍出现即异常面，禁挂新绑定） */
    private static final String PATIENT_STATUS_MERGED = "MERGED";

    /** 全链路追踪号 MDC 键（与 GlobalExceptionHandler/发布器同源；发布点捕获防 AFTER_COMMIT 丢失） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /** 设备档案 mapper：bind 校验链第一环（设备存在且非 DISABLED） */
    private final IotDeviceMapper deviceMapper;

    /** 患者上下文解析契约（CF-3）：归一主档 + 冻结/合并拦截 */
    private final PatientContextResolver patientResolver;

    /** 在途就诊查询契约（M02 红线 4 SPI，M04 在院三态实现）：绑定必须有在途就诊承载 */
    private final OngoingVisitQuery ongoingVisitQuery;

    /** 应用事件发布器：事务内发布 IotDomainEvent，IotDomainPublisher AFTER_COMMIT 直发 MQ */
    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param deviceMapper       设备档案 mapper，非空；来源：同模块 mapper 包
     * @param patientResolver    患者上下文解析契约，非空；来源：fuyun-patient api（容器实现注入）
     * @param ongoingVisitQuery  在途就诊查询契约，非空；来源：fuyun-patient api（M04 实现注入）
     * @param events             应用事件发布器，非空；来源：Spring 上下文
     */
    public BindingServiceImpl(
            IotDeviceMapper deviceMapper,
            PatientContextResolver patientResolver,
            OngoingVisitQuery ongoingVisitQuery,
            ApplicationEventPublisher events) {
        this.deviceMapper = deviceMapper;
        this.patientResolver = patientResolver;
        this.ongoingVisitQuery = ongoingVisitQuery;
        this.events = events;
    }

    @Override
    @Transactional
    public BindingVO bind(BindDeviceRequest req) {
        // 校验链第一环：设备存在（404）且非 DISABLED（409 停用设备禁新绑定，14-iot §5 状态机）
        IotDeviceEntity device = deviceMapper.selectById(req.deviceId());
        if (device == null) {
            throw new BizException(IotErrorCode.DEVICE_NOT_FOUND, HttpStatus.NOT_FOUND, "设备不存在：" + req.deviceId());
        }
        if (device.getStatus() == DeviceStatus.DISABLED) {
            throw new BizException(
                    IotErrorCode.DEVICE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "设备已停用，禁止绑定：" + req.deviceId());
        }
        // 校验链第二环：无 BOUND 生效绑定（应用层前置拒绝；插入竞态由唯一索引兜底翻译 IOT-1010）
        if (lambdaQuery()
                        .eq(IotBindingEntity::getDeviceId, req.deviceId())
                        .eq(IotBindingEntity::getStatus, BindingStatus.BOUND)
                        .count()
                > 0) {
            log.warn("绑定拒绝：设备已有生效绑定（uk_iot_binding_device_bound 前置）：deviceId={}", req.deviceId());
            throw new BizException(
                    IotErrorCode.BINDING_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "设备已有生效绑定，禁止重复绑定：" + req.deviceId());
        }
        // 校验链第三环（患者面）：解析归一主档，冻结（blocked）或合并中（MERGED）任一不过 IOT-1011；
        // 落行/事件统一使用 resolvedPatientId（M02 红线 1，业务数据关联主档）
        PatientContextView view = patientResolver.resolve(req.patientId());
        if (view.blocked() || PATIENT_STATUS_MERGED.equals(view.status())) {
            log.warn(
                    "绑定拒绝：患者档案不可用（冻结/合并中）：patientId={}，status={}，blockReason={}",
                    req.patientId(),
                    view.status(),
                    view.blockReason());
            throw new BizException(
                    IotErrorCode.BINDING_CHECK_INVALID, HttpStatus.CONFLICT, "患者档案冻结或合并中，禁止绑定：" + req.patientId());
        }
        // 校验链第四环（就诊面）：归一主档必须存在在途就诊（M04 在院三态），无在途即 IOT-1011
        if (!ongoingVisitQuery.hasOngoingVisit(view.resolvedPatientId())) {
            log.warn("绑定拒绝：患者无在途就诊：resolvedPatientId={}", view.resolvedPatientId());
            throw new BizException(
                    IotErrorCode.BINDING_CHECK_INVALID,
                    HttpStatus.CONFLICT,
                    "患者无在途就诊，禁止绑定：" + view.resolvedPatientId());
        }
        // 数据库写操作：落 BOUND 行（bound_at 由库端 DEFAULT now() 承担；操作人无登录上下文回退 system）
        String operator = OperatorContextHolder.get();
        IotBindingEntity entity = new IotBindingEntity();
        entity.setDeviceId(req.deviceId());
        entity.setPatientId(view.resolvedPatientId());
        entity.setVisitId(req.visitId());
        entity.setBedId(req.bedId());
        entity.setWardId(req.wardId());
        entity.setBindType(req.bindType());
        entity.setStatus(BindingStatus.BOUND);
        entity.setBindReason(req.bindReason());
        entity.setBoundBy(operator == null || operator.isBlank() ? "system" : operator);
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            // 并发兜底：双绑定请求同设备竞态穿越前置计数，由 uk_iot_binding_device_bound 拒绝
            // （异常翻译 IOT-1010 并上抛令本事务回滚，ProblemDetail 与前置拒绝同码同语义）
            log.warn("绑定竞态落败（唯一索引兜底）：deviceId={}", req.deviceId());
            throw new BizException(
                    IotErrorCode.BINDING_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "设备已有生效绑定，禁止重复绑定：" + req.deviceId());
        }
        // 事务内发布绑定变更事件（BIND）：IotDomainPublisher AFTER_COMMIT 直发 MQ；traceId 于发布点
        // 捕获（MDC 在事务回调时点不可依赖，IotDomainEvent 契约）
        Instant occurredAt = Instant.now();
        events.publishEvent(new IotDomainEvent(
                IotMessagingConstants.EVENT_BINDING_CHANGED,
                new BindingChangedPayload(
                        entity.getDeviceId(),
                        entity.getPatientId(),
                        entity.getVisitId(),
                        entity.getBedId(),
                        entity.getWardId(),
                        entity.getBindType().getCode(),
                        CHANGE_TYPE_BIND,
                        occurredAt),
                occurredAt,
                MDC.get(TRACE_ID_MDC_KEY)));
        log.info(
                "设备绑定落行完成：deviceId={}，patientId={}，visitId={}，wardId={}，bindType={}，boundBy={}",
                entity.getDeviceId(),
                entity.getPatientId(),
                entity.getVisitId(),
                entity.getWardId(),
                entity.getBindType().getCode(),
                entity.getBoundBy());
        return BindingVO.from(entity);
    }

    @Override
    @Transactional
    public void unbind(String deviceId, UnbindDeviceRequest req) {
        // 原因强制（brief 指定 IOT-1010 码位）：空白原因在服务层显式拒（400），Bean Validation 之外
        // 兜住直接调用服务层的路径
        String reason = req == null ? null : req.reason();
        if (reason == null || reason.isBlank()) {
            throw new BizException(IotErrorCode.BINDING_STATE_NOT_ALLOWED, HttpStatus.BAD_REQUEST, "解绑原因强制，禁止空白");
        }
        // 数据库读操作：定位 BOUND 行（事件载荷数据源；无 BOUND 即无绑定/重复解绑 IOT-1010）
        IotBindingEntity active = lambdaQuery()
                .eq(IotBindingEntity::getDeviceId, deviceId)
                .eq(IotBindingEntity::getStatus, BindingStatus.BOUND)
                .one();
        if (active == null) {
            log.warn("解绑拒绝：设备无 BOUND 生效绑定：deviceId={}", deviceId);
            throw new BizException(
                    IotErrorCode.BINDING_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "设备无生效绑定，禁止解绑：" + deviceId);
        }
        // 数据库写操作（CAS 第一步）：仅 BOUND 行迁移 UNBINDING；行数不足 = 并发被抢，IOT-1010 回滚
        if (baseMapper.casMarkUnbinding(deviceId) != 1) {
            log.warn("解绑 CAS 落败（BOUND→UNBINDING 0 行，并发竞争）：deviceId={}", deviceId);
            throw new BizException(
                    IotErrorCode.BINDING_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "绑定状态已变更，解绑失败：" + deviceId);
        }
        // 数据库写操作（CAS 第二步）：仅 UNBINDING 行迁移 UNBOUND 终态并留痕；落败抛出让第一步随
        // 事务整体回滚（双迁移原子性）
        if (baseMapper.casMarkUnbound(deviceId, reason) != 1) {
            log.warn("解绑 CAS 落败（UNBINDING→UNBOUND 0 行，并发竞争）：deviceId={}", deviceId);
            throw new BizException(
                    IotErrorCode.BINDING_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "绑定状态已变更，解绑失败：" + deviceId);
        }
        // 事务内发布绑定变更事件（UNBIND）：载荷患者/就诊置 null（BindingChangedPayload 契约
        // "解绑后无患者为 null"），病区/床位/模式取解绑前档案留档回显
        Instant occurredAt = Instant.now();
        events.publishEvent(new IotDomainEvent(
                IotMessagingConstants.EVENT_BINDING_CHANGED,
                new BindingChangedPayload(
                        active.getDeviceId(),
                        null,
                        null,
                        active.getBedId(),
                        active.getWardId(),
                        active.getBindType().getCode(),
                        CHANGE_TYPE_UNBIND,
                        occurredAt),
                occurredAt,
                MDC.get(TRACE_ID_MDC_KEY)));
        log.info(
                "设备解绑迁移完成：deviceId={}，wardId={}，reason={}，operator={}",
                deviceId,
                active.getWardId(),
                reason,
                OperatorContextHolder.get());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<BindingVO> page(BindingQueryRequest req) {
        // 缺省补齐：page 0 基缺省 0、size 缺省 20（GET 幂等查询无强制必填面）
        int page = req.page() == null ? 0 : req.page();
        int size = req.size() == null ? 20 : req.size();
        // 数据库读操作：过滤条件缺席即不过滤；id 倒序 = 新绑定在前（雪花 id 时序，A.4.3-17 唯一顺序）
        Page<IotBindingEntity> result = lambdaQuery()
                .eq(req.deviceId() != null && !req.deviceId().isBlank(), IotBindingEntity::getDeviceId, req.deviceId())
                .eq(req.wardId() != null, IotBindingEntity::getWardId, req.wardId())
                .eq(req.status() != null, IotBindingEntity::getStatus, req.status())
                .orderByDesc(IotBindingEntity::getId)
                .page(new Page<>(page + 1, size));
        return PageResult.of(result.getRecords().stream().map(BindingVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BindingVO> listByWard(Long wardId) {
        // 数据库读操作：病区维度 BOUND 生效绑定（M05 播报路由/M16 病区设备墙当前归属面），id 升序稳定
        return lambdaQuery()
                .eq(IotBindingEntity::getWardId, wardId)
                .eq(IotBindingEntity::getStatus, BindingStatus.BOUND)
                .orderByAsc(IotBindingEntity::getId)
                .list()
                .stream()
                .map(BindingVO::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BindingVO> findActiveByDevice(String deviceId) {
        // 数据库读操作：设备维度 BOUND 生效绑定；唯一索引保证至多一行（one() 对脏数据多行会显式报错）
        IotBindingEntity active = lambdaQuery()
                .eq(IotBindingEntity::getDeviceId, deviceId)
                .eq(IotBindingEntity::getStatus, BindingStatus.BOUND)
                .one();
        return Optional.ofNullable(active).map(BindingVO::from);
    }
}
