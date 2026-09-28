package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.fuyun.iot.enums.BindType;
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
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 绑定管理服务单测（P2 PR-2 Task 3 Step 4）：bind 校验链四类拒绝[IOT-1006/1007/1010/1011]、
 * 成功落行（患者归一主档落行）+ iot.binding.changed 事务内发布、唯一索引竞态翻译 IOT-1010、
 * unbind 原因强制与 BOUND→UNBINDING→UNBOUND 双 CAS 迁移（含并发落败回滚语义）、分页缺省值、
 * listByWard 与 findActiveByDevice 查询面。JaCoCo 核心包 com.fuyun.iot.service.impl LINE=1.00
 * 承载测试。
 *
 * <p>mapper/patient 契约/事件发布器以 Mockito 模拟（真实 SQL 与事件 AFTER_COMMIT 出 MQ 归
 * fuyun-app 集成面验证）；lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次）。
 */
@ExtendWith(MockitoExtension.class)
class BindingServiceImplTest {

    /** 设备标识夹具（V1006 演示夹具同款形态） */
    private static final String DEVICE_ID = "fuyun-demo-001";

    /** CF-3 住院就诊号夹具（I + 8 位日期 + 5 位流水，14 位定长） */
    private static final String VISIT_ID = "I2026090100001";

    /** 患者主索引夹具（入参从档 id，归一后主档为 RESOLVED_PATIENT_ID） */
    private static final long PATIENT_ID = 7L;

    /** 归一后主档 id：bind 落行/事件载荷必须使用 resolvedPatientId（M02 红线 1） */
    private static final long RESOLVED_PATIENT_ID = 70L;

    /** 病区 id 夹具 */
    private static final long WARD_ID = 1001L;

    /** 床位 id 夹具 */
    private static final long BED_ID = 2001L;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IotBindingMapper bindingMapper;

    @Mock
    private PatientContextResolver patientResolver;

    @Mock
    private OngoingVisitQuery ongoingVisitQuery;

    /** 第二个在途就诊契约实现（多实现集合"任一命中即在途"语义的验证载体） */
    @Mock
    private OngoingVisitQuery ongoingVisitQuerySecond;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<IotBindingEntity> insertCaptor;

    @Captor
    private ArgumentCaptor<IotDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Page<IotBindingEntity>> pageCaptor;

    private IBindingService service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotBindingEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 无 Spring 上下文直构（Bean 注册归 app 侧 IotConfig @Import）；ServiceImpl 基类字段手工注入；
        // 在途就诊契约以双元素清单注入（生产为 Spring 按类型收集的多模块实现集合，任一命中即在途）
        service = new BindingServiceImpl(
                deviceMapper, patientResolver, List.of(ongoingVisitQuery, ongoingVisitQuerySecond), events);
        ReflectionTestUtils.setField(service, "baseMapper", bindingMapper);
        ReflectionTestUtils.setField(service, "entityClass", IotBindingEntity.class);
        OperatorContextHolder.set("E1001");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("bind 拒绝：设备不存在 IOT-1006（404），且不触绑定写路径与事件")
    void bindRejectsMissingDeviceWithNotFound() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.bind(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("bind 拒绝：设备停用 DISABLED IOT-1007（409）——停用设备禁新绑定")
    void bindRejectsDisabledDeviceWithStateError() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.DISABLED));

        assertThatThrownBy(() -> service.bind(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_STATE_NOT_ALLOWED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("bind 拒绝：设备已有 BOUND 生效绑定 IOT-1010（409，uk_iot_binding_device_bound 应用层前置）")
    void bindRejectsWhenActiveBindingExists() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.ONLINE));
        when(bindingMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.bind(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_STATE_NOT_ALLOWED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(bindingMapper, never()).insert(any(IotBindingEntity.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("bind 拒绝：患者冻结中 IOT-1011（409，blocked 视图拦截）")
    void bindRejectsFrozenPatientWithCheckInvalid() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.ONLINE));
        when(bindingMapper.selectCount(any())).thenReturn(0L);
        when(patientResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "FROZEN", true, "档案冻结中"));

        assertThatThrownBy(() -> service.bind(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_CHECK_INVALID);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("bind 拒绝：患者合并状态 IOT-1011（409，MERGED 主档禁止挂新绑定）")
    void bindRejectsMergedPatientWithCheckInvalid() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.ONLINE));
        when(bindingMapper.selectCount(any())).thenReturn(0L);
        when(patientResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "MERGED", false, ""));

        assertThatThrownBy(() -> service.bind(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_CHECK_INVALID);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("bind 拒绝：无在途就诊 IOT-1011（409，按归一主档 id 查询在院状态）")
    void bindRejectsWhenNoOngoingVisit() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.ONLINE));
        when(bindingMapper.selectCount(any())).thenReturn(0L);
        when(patientResolver.resolve(PATIENT_ID)).thenReturn(normalView());
        when(ongoingVisitQuery.hasOngoingVisit(RESOLVED_PATIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.bind(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_CHECK_INVALID);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 在途判定必须以归一主档 id 为参（从档 id 不落在 visit 主档面上）
        verify(ongoingVisitQuery).hasOngoingVisit(RESOLVED_PATIENT_ID);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("bind 成功：BOUND 行落库（患者以归一主档 id 落行）+ 事务内发布 iot.binding.changed(BIND)")
    void bindPersistsBoundRowAndPublishesBindEvent() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.ONLINE));
        when(bindingMapper.selectCount(any())).thenReturn(0L);
        when(patientResolver.resolve(PATIENT_ID)).thenReturn(normalView());
        when(ongoingVisitQuery.hasOngoingVisit(RESOLVED_PATIENT_ID)).thenReturn(true);
        when(bindingMapper.insert(any(IotBindingEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, IotBindingEntity.class).setId(9001L);
            return 1;
        });

        BindingVO vo = service.bind(request());

        // 落行断言：五元组 + BOUND + 操作人留痕（visitId 为 CF-3 14 位字符串形态）
        verify(bindingMapper).insert(insertCaptor.capture());
        IotBindingEntity row = insertCaptor.getValue();
        assertThat(row.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(row.getPatientId()).as("必须以归一后主档 id 落行（M02 红线 1）").isEqualTo(RESOLVED_PATIENT_ID);
        assertThat(row.getVisitId()).isEqualTo(VISIT_ID);
        assertThat(row.getBedId()).isEqualTo(BED_ID);
        assertThat(row.getWardId()).isEqualTo(WARD_ID);
        assertThat(row.getBindType()).isEqualTo(BindType.FIXED);
        assertThat(row.getStatus()).isEqualTo(BindingStatus.BOUND);
        assertThat(row.getBindReason()).isEqualTo("入院固定绑定");
        assertThat(row.getBoundBy()).isEqualTo("E1001");
        // 事件断言：事务内 publishEvent（AFTER_COMMIT 出 MQ 归 IotDomainPublisher），载荷五元组同源
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_BINDING_CHANGED);
        assertThat(event.occurredAt()).isNotNull();
        BindingChangedPayload payload = (BindingChangedPayload) event.payload();
        assertThat(payload.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(payload.patientId()).isEqualTo(RESOLVED_PATIENT_ID);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.bedId()).isEqualTo(BED_ID);
        assertThat(payload.wardId()).isEqualTo(WARD_ID);
        assertThat(payload.bindType()).isEqualTo(BindType.FIXED.getCode());
        assertThat(payload.changeType()).isEqualTo("BIND");
        // 出参断言：落库视图回显（含回填雪花 id）
        assertThat(vo.id()).isEqualTo(9001L);
        assertThat(vo.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(vo.patientId()).isEqualTo(RESOLVED_PATIENT_ID);
        assertThat(vo.visitId()).isEqualTo(VISIT_ID);
        assertThat(vo.status()).isEqualTo(BindingStatus.BOUND);
    }

    @Test
    @DisplayName("bind 竞态兜底：并发命中 uk_iot_binding_device_bound 唯一索引翻译为 IOT-1010 整体回滚")
    void bindTranslatesUniqueIndexRaceToBindingStateError() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.ONLINE));
        when(bindingMapper.selectCount(any())).thenReturn(0L);
        when(patientResolver.resolve(PATIENT_ID)).thenReturn(normalView());
        when(ongoingVisitQuery.hasOngoingVisit(RESOLVED_PATIENT_ID)).thenReturn(true);
        when(bindingMapper.insert(any(IotBindingEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_iot_binding_device_bound"));

        assertThatThrownBy(() -> service.bind(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_STATE_NOT_ALLOWED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("unbind 拒绝：解绑原因空白 IOT-1010（400，原因强制红线），不触绑定行与事件")
    void unbindRejectsBlankReason() {
        assertThatThrownBy(() -> service.unbind(DEVICE_ID, new UnbindDeviceRequest("  ")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_STATE_NOT_ALLOWED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(bindingMapper, never()).casMarkUnbinding(any());
        verify(bindingMapper, never()).casMarkUnbound(any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("unbind 拒绝：设备无 BOUND 绑定 IOT-1010（409，含重复解绑）")
    void unbindRejectsWhenNoBoundBinding() {
        when(bindingMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.unbind(DEVICE_ID, new UnbindDeviceRequest("转床")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_STATE_NOT_ALLOWED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(bindingMapper, never()).casMarkUnbinding(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("unbind 成功：BOUND→UNBINDING→UNBOUND 双 CAS 依序迁移 + 发布 iot.binding.changed(UNBIND)")
    void unbindPerformsDoubleCasAndPublishesUnbindEvent() {
        when(bindingMapper.selectOne(any())).thenReturn(boundEntity());
        when(bindingMapper.casMarkUnbinding(DEVICE_ID)).thenReturn(1);
        when(bindingMapper.casMarkUnbound(DEVICE_ID, "消毒停用")).thenReturn(1);

        service.unbind(DEVICE_ID, new UnbindDeviceRequest("消毒停用"));

        // 双 CAS 顺序断言：先受理（BOUND→UNBINDING）后终态（UNBINDING→UNBOUND），行数不足即中断
        InOrder inOrder = inOrder(bindingMapper);
        inOrder.verify(bindingMapper).casMarkUnbinding(DEVICE_ID);
        inOrder.verify(bindingMapper).casMarkUnbound(DEVICE_ID, "消毒停用");
        // UNBIND 载荷按 BindingChangedPayload 契约：解绑后患者/就诊置 null，病区/床位/模式留档回显
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_BINDING_CHANGED);
        BindingChangedPayload payload = (BindingChangedPayload) event.payload();
        assertThat(payload.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(payload.patientId()).as("解绑后无患者（载荷契约）").isNull();
        assertThat(payload.visitId()).as("解绑后无就诊（载荷契约）").isNull();
        assertThat(payload.bedId()).isEqualTo(BED_ID);
        assertThat(payload.wardId()).isEqualTo(WARD_ID);
        assertThat(payload.bindType()).isEqualTo(BindType.FIXED.getCode());
        assertThat(payload.changeType()).isEqualTo("UNBIND");
    }

    @Test
    @DisplayName("unbind 并发落败（第一步 CAS 0 行）：IOT-1010 回滚且不触第二步与事件")
    void unbindRejectsWhenFirstCasMisses() {
        when(bindingMapper.selectOne(any())).thenReturn(boundEntity());
        when(bindingMapper.casMarkUnbinding(DEVICE_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.unbind(DEVICE_ID, new UnbindDeviceRequest("维修")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_STATE_NOT_ALLOWED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(bindingMapper, never()).casMarkUnbound(any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("unbind 并发落败（第二步 CAS 0 行）：IOT-1010 抛出令事务整体回滚（第一步随回滚）")
    void unbindRejectsWhenSecondCasMisses() {
        when(bindingMapper.selectOne(any())).thenReturn(boundEntity());
        when(bindingMapper.casMarkUnbinding(DEVICE_ID)).thenReturn(1);
        when(bindingMapper.casMarkUnbound(eq(DEVICE_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> service.unbind(DEVICE_ID, new UnbindDeviceRequest("维修")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.BINDING_STATE_NOT_ALLOWED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("分页：page/size 缺省补齐（0/20）+ 实体经 VO 工厂出网 + 0 基页码回显")
    void pageAppliesDefaultsAndMapsVoRows() {
        when(bindingMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<IotBindingEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(boundEntity()));
            result.setTotal(5);
            return result;
        });

        PageResult<BindingVO> result =
                service.page(new BindingQueryRequest(null, null, DEVICE_ID, WARD_ID, BindingStatus.BOUND));

        assertThat(result.page()).as("0 基页码按请求口径回显").isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).deviceId()).isEqualTo(DEVICE_ID);
        assertThat(result.content().get(0).visitId()).isEqualTo(VISIT_ID);
        // MP 分页 1 基 current：缺省 0 基请求 0 → current 1、size 20
        verify(bindingMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("listByWard：病区维度返回 BOUND 绑定视图清单（M05/M16 查询面）")
    void listByWardReturnsBoundBindings() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(boundEntity()));

        List<BindingVO> rows = service.listByWard(WARD_ID);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).wardId()).isEqualTo(WARD_ID);
        assertThat(rows.get(0).status()).isEqualTo(BindingStatus.BOUND);
    }

    @Test
    @DisplayName("findActiveByDevice：委托批量通道取单元素——有 BOUND 绑定回命中、无绑定回 empty")
    void findActiveByDeviceReturnsPresentOrEmpty() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(boundEntity()), List.of());

        Optional<BindingVO> present = service.findActiveByDevice(DEVICE_ID);
        Optional<BindingVO> absent = service.findActiveByDevice("dev-other");

        assertThat(present).isPresent();
        assertThat(present.get().deviceId()).isEqualTo(DEVICE_ID);
        assertThat(absent).isEmpty();
    }

    @Test
    @DisplayName("listActiveByDevices：设备集合单次批量 IN 查 BOUND 绑定（遥测富化批量通道）")
    void listActiveByDevicesBatchesActiveBindingsForDeviceSet() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(boundEntity()));

        List<BindingVO> rows = service.listActiveByDevices(List.of(DEVICE_ID, "dev-other"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).deviceId()).isEqualTo(DEVICE_ID);
        assertThat(rows.get(0).status()).isEqualTo(BindingStatus.BOUND);
    }

    @Test
    @DisplayName("listActiveByDevices：空集合直接返回空清单零触库（防空 IN 列表非法 SQL）")
    void listActiveByDevicesShortCircuitsEmptyDeviceSet() {
        List<BindingVO> rows = service.listActiveByDevices(List.of());

        assertThat(rows).isEmpty();
        verifyNoInteractions(bindingMapper);
    }

    @Test
    @DisplayName("在途就诊契约多实现：任一实现命中即认定在途（首查未命中、次查命中放行落行）")
    void bindPassesWhenAnyOngoingVisitQueryImplementationHits() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DeviceStatus.ONLINE));
        when(bindingMapper.selectCount(any())).thenReturn(0L);
        when(patientResolver.resolve(PATIENT_ID)).thenReturn(normalView());
        when(ongoingVisitQuery.hasOngoingVisit(RESOLVED_PATIENT_ID)).thenReturn(false);
        when(ongoingVisitQuerySecond.hasOngoingVisit(RESOLVED_PATIENT_ID)).thenReturn(true);

        BindingVO vo = service.bind(request());

        assertThat(vo.deviceId()).as("任一实现命中即在途，放行落行").isEqualTo(DEVICE_ID);
        verify(bindingMapper).insert(any(IotBindingEntity.class));
    }

    /** 构造绑定请求（FIXED 固定式，床位/病区/就诊/患者齐备） */
    private static BindDeviceRequest request() {
        return new BindDeviceRequest(DEVICE_ID, PATIENT_ID, VISIT_ID, BED_ID, WARD_ID, BindType.FIXED, "入院固定绑定");
    }

    /** 构造设备档案（状态可变：ONLINE 可绑定 / DISABLED 拒绑定） */
    private static IotDeviceEntity device(DeviceStatus status) {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setStatus(status);
        return device;
    }

    /** 构造归一主档正常视图（从档入参 → 主档收敛，非拦截） */
    private static PatientContextView normalView() {
        return new PatientContextView(PATIENT_ID, RESOLVED_PATIENT_ID, "NORMAL", false, "");
    }

    /** 构造 BOUND 绑定行（unbind 事件载荷与 VO 映射断言数据源；雪花 id 已回填） */
    private static IotBindingEntity boundEntity() {
        IotBindingEntity entity = new IotBindingEntity();
        entity.setId(9001L);
        entity.setDeviceId(DEVICE_ID);
        entity.setPatientId(RESOLVED_PATIENT_ID);
        entity.setVisitId(VISIT_ID);
        entity.setBedId(BED_ID);
        entity.setWardId(WARD_ID);
        entity.setBindType(BindType.FIXED);
        entity.setStatus(BindingStatus.BOUND);
        entity.setBoundBy("E1001");
        return entity;
    }
}
