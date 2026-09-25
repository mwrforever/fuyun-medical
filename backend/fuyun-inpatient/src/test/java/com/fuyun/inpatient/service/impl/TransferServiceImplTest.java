package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.VisitTransferredPayload;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.ChangeBedRequest;
import com.fuyun.inpatient.dto.TransferRequest;
import com.fuyun.inpatient.entity.Bed;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.TransferType;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.BedMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.service.MedicalOrderService;
import com.fuyun.inpatient.vo.TransferResultVO;
import org.apache.ibatis.annotations.Update;
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
import org.springframework.http.HttpStatus;

/**
 * 转科四阶段编排与转床轻量路径单测（Task 4 冻结集）：四阶段全链（mock 医嘱停嘱、断言床位
 * 三段流转与 transferred 六字段载荷）、目标床位被占编排失败回滚（@Transactional 回滚语义下
 * 以 mock 验证调用序 + 异常传播）、同病区转床轻量路径（无停嘱调用）与守卫错误面。
 * MedicalOrderService 为 Task 5 冻结接口（本套 mock 消费）。MP 3.5.17 单测范式：lambdaQuery
 * 触达实体 @BeforeAll 手工注册表信息；条件更新断言直读 @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 编排主体就诊行主键（MedicalOrderService.stopAllForTransfer 消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 转出病区/床位 */
    private static final String FROM_WARD = "W01";

    private static final long FROM_BED = 501L;

    /** 转入病区/床位（跨病区转科目标面） */
    private static final String TO_WARD = "W02";

    private static final long TO_BED = 601L;

    /** 同病区转床目标床（W01 内） */
    private static final long SAME_WARD_TO_BED = 502L;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private BedMapper bedMapper;

    @Mock
    private BedService bedService;

    @Mock
    private MedicalOrderService medicalOrderService;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    private TransferServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（visit 在院态定位面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(visitMapper, bedMapper, bedService, medicalOrderService, events);
        OperatorContextHolder.set("doc-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("转科四阶段全链：停嘱→转出床流转→目标床占床→visit 定位 CAS→transferred 六字段载荷")
    void transferRunsFourPhasesInFrozenOrder() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(bedMapper.selectById(TO_BED)).thenReturn(bedRow(TO_BED, TO_WARD));
        when(visitMapper.casTransferLocation(VISIT_ID, "D02", TO_WARD, TO_BED, "doc-01"))
                .thenReturn(1);

        TransferResultVO result = service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED));

        // 出参前后定位面与完成时点
        assertThat(result.visitId()).isEqualTo(VISIT_ID);
        assertThat(result.fromWardId()).isEqualTo(FROM_WARD);
        assertThat(result.fromBedId()).isEqualTo(FROM_BED);
        assertThat(result.toWardId()).isEqualTo(TO_WARD);
        assertThat(result.toBedId()).isEqualTo(TO_BED);
        assertThat(result.transferredAt()).isNotNull();

        // 四阶段时序冻结锚：①停嘱（转出病区长期医嘱）→ ③转出床流转→目标床占床→visit 定位 CAS
        InOrder order = inOrder(medicalOrderService, bedService, visitMapper);
        order.verify(medicalOrderService).stopAllForTransfer(VISIT_PK, "转科");
        order.verify(bedService).transferOut(FROM_BED, VISIT_ID);
        order.verify(bedService).occupyForTransfer(TO_BED, VISIT_ID, PATIENT_ID, TransferType.WARD_TRANSFER);
        order.verify(visitMapper).casTransferLocation(VISIT_ID, "D02", TO_WARD, TO_BED, "doc-01");

        // ④transferred 事件（V800 id 49 载荷逐字：visitId/patientId/fromWardId/fromBedId/toWardId/toBedId/transferredAt）
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_VISIT_TRANSFERRED);
        VisitTransferredPayload payload = (VisitTransferredPayload) event.payload();
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.fromWardId()).isEqualTo(FROM_WARD);
        assertThat(payload.fromBedId()).isEqualTo(FROM_BED);
        assertThat(payload.toWardId()).isEqualTo(TO_WARD);
        assertThat(payload.toBedId()).isEqualTo(TO_BED);
        assertThat(payload.transferredAt()).isEqualTo(result.transferredAt().toInstant());

        // GC26 锚：定位 CAS 限定在院态（转科为 ADMITTED 内属性变更）、目标科室缺席保留原值
        String sql = recordSql(
                InpatientVisitMapper.class,
                "casTransferLocation",
                String.class,
                String.class,
                String.class,
                Long.class,
                String.class);
        assertThat(sql)
                .contains("SET current_ward_id = #{toWardId}, current_bed_id = #{toBedId}")
                .contains("<if test='toDeptId != null'>, current_dept_id = #{toDeptId}</if>")
                .contains("WHERE visit_id = #{visitId}")
                .contains("status = 'ADMITTED'")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("目标床位被占编排失败回滚：IP-1006 异常传播，停嘱/转出床已调用、定位 CAS 与事件零触达")
    void transferPropagatesOccupiedTargetAndStopsBeforeVisitUpdate() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(bedMapper.selectById(TO_BED)).thenReturn(bedRow(TO_BED, TO_WARD));
        doThrow(new BizException(InpatientErrorCode.BED_OCCUPIED, HttpStatus.CONFLICT, "床位已被占用"))
                .when(bedService)
                .occupyForTransfer(TO_BED, VISIT_ID, PATIENT_ID, TransferType.WARD_TRANSFER);

        // 编排事务内目标床占床失败：异常原样传播（@Transactional 回滚语义下转出床状态自动复原，
        // 单测以调用序锚 + 异常传播断言回滚前置件）
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_OCCUPIED);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 调用序锚：①停嘱与③转出床流转先于目标床占床（失败点），转出床确已进入流转
        InOrder order = inOrder(medicalOrderService, bedService);
        order.verify(medicalOrderService).stopAllForTransfer(VISIT_PK, "转科");
        order.verify(bedService).transferOut(FROM_BED, VISIT_ID);
        order.verify(bedService).occupyForTransfer(TO_BED, VISIT_ID, PATIENT_ID, TransferType.WARD_TRANSFER);
        // 定位 CAS 与 transferred 事件零触达（编排未推进到③后段/④）
        verify(visitMapper, never()).casTransferLocation(any(), any(), any(), any(), any());
        verifyNoInteractions(events);

        // 定位 CAS 0 行（并发出院窗口）：编排推进至③后段，定性 IP-1023，事件零发布
        org.mockito.Mockito.doNothing()
                .when(bedService)
                .occupyForTransfer(TO_BED, VISIT_ID, PATIENT_ID, TransferType.WARD_TRANSFER);
        when(visitMapper.casTransferLocation(VISIT_ID, "D02", TO_WARD, TO_BED, "doc-01"))
                .thenReturn(0);
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("同病区转床轻量路径：无停嘱调用，转出→占床（BED_CHANGE）→定位→transferred（前后同病区）")
    void changeBedRunsLightweightPathWithoutStoppingOrders() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(bedMapper.selectById(SAME_WARD_TO_BED)).thenReturn(bedRow(SAME_WARD_TO_BED, FROM_WARD));
        // 免登录上下文场景：操作者回退 system（与审计列默认同源）
        OperatorContextHolder.clear();
        when(visitMapper.casTransferLocation(VISIT_ID, null, FROM_WARD, SAME_WARD_TO_BED, "system"))
                .thenReturn(1);

        TransferResultVO result = service.changeBed(VISIT_ID, new ChangeBedRequest(SAME_WARD_TO_BED));

        // 轻量路径锚：无医嘱停嘱步骤（仅床位切换与事件）
        verify(medicalOrderService, never()).stopAllForTransfer(any(), any());
        verify(bedService).transferOut(FROM_BED, VISIT_ID);
        verify(bedService).occupyForTransfer(SAME_WARD_TO_BED, VISIT_ID, PATIENT_ID, TransferType.BED_CHANGE);
        assertThat(result.fromWardId()).isEqualTo(FROM_WARD);
        assertThat(result.toWardId()).isEqualTo(FROM_WARD);
        assertThat(result.fromBedId()).isEqualTo(FROM_BED);
        assertThat(result.toBedId()).isEqualTo(SAME_WARD_TO_BED);
        // transferred 事件同源发布（前后病区相同——转床路径）
        verify(events).publishEvent(eventCaptor.capture());
        VisitTransferredPayload payload =
                (VisitTransferredPayload) eventCaptor.getValue().payload();
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(InpatientMessagingConstants.EVENT_VISIT_TRANSFERRED);
        assertThat(payload.fromWardId()).isEqualTo(FROM_WARD);
        assertThat(payload.toWardId()).isEqualTo(FROM_WARD);
        assertThat(payload.fromBedId()).isEqualTo(FROM_BED);
        assertThat(payload.toBedId()).isEqualTo(SAME_WARD_TO_BED);
    }

    @Test
    @DisplayName("守卫错误面：就诊不存在 IP-1007/非在院 IP-1008；同病区转科 IP-1022；在院无床位/归属不符 IP-1023；同床 IP-1022；目标床不存在 IP-1004")
    void transferAndChangeBedRejectGuardViolations() {
        // 就诊不存在：IP-1007，零床位/停嘱/事件触达
        when(visitMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        // 非在院态（已出院）：IP-1008
        InpatientVisit discharged = visitRow();
        discharged.setStatus(VisitStatus.DISCHARGED.getCode());
        when(visitMapper.selectOne(any())).thenReturn(discharged);
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_STATE_NOT_ALLOWED));

        // 同病区转科（目标病区=当前病区）：IP-1022——同病区床位切换走 change-bed 轻量路径
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest(null, FROM_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 在院无床位（数据不一致）：IP-1023
        InpatientVisit bedless = visitRow();
        bedless.setCurrentBedId(null);
        when(visitMapper.selectOne(any())).thenReturn(bedless);
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.changeBed(VISIT_ID, new ChangeBedRequest(SAME_WARD_TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // 目标床位与当前床位相同：IP-1022（转科/转床同判——无位移编排拒绝）
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, FROM_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        assertThatThrownBy(() -> service.changeBed(VISIT_ID, new ChangeBedRequest(FROM_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 目标床位不存在：IP-1004
        when(bedMapper.selectById(TO_BED)).thenReturn(null);
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(
                        e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_NOT_FOUND));

        // 目标床位与目标病区归属不符（转科）：IP-1023
        when(bedMapper.selectById(TO_BED)).thenReturn(bedRow(TO_BED, FROM_WARD));
        assertThatThrownBy(() -> service.transfer(VISIT_ID, new TransferRequest("D02", TO_WARD, TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // 跨病区转床（转床限定同病区）：IP-1023
        when(bedMapper.selectById(TO_BED)).thenReturn(bedRow(TO_BED, TO_WARD));
        assertThatThrownBy(() -> service.changeBed(VISIT_ID, new ChangeBedRequest(TO_BED)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // 全部守卫拒绝路径：停嘱/床位流转/事件零触达
        verifyNoInteractions(medicalOrderService, bedService, events);
    }

    /** 构造在院就诊行（ADMITTED，W01/501 定位面——编排起始载体）。 */
    private InpatientVisit visitRow() {
        InpatientVisit row = new InpatientVisit();
        row.setId(VISIT_PK);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setCurrentDeptId("D01");
        row.setCurrentWardId(FROM_WARD);
        row.setCurrentBedId(FROM_BED);
        row.setStatus(VisitStatus.ADMITTED.getCode());
        return row;
    }

    /** 构造目标床位行（归属病区可变——守卫用例载体）。 */
    private Bed bedRow(long bedId, String wardId) {
        Bed row = new Bed();
        row.setId(bedId);
        row.setBedNo("B" + bedId);
        row.setWardId(wardId);
        row.setBedAttr("NORMAL");
        row.setStatus("FREE");
        return row;
    }

    /**
     * 直读 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。
     *
     * @param mapper     mapper 接口类型
     * @param method     mapper 方法名
     * @param paramTypes 方法参数类型（重载定位）
     * @return 拼接后的注解 SQL 全文
     */
    private String recordSql(Class<?> mapper, String method, Class<?>... paramTypes) {
        try {
            Update update = mapper.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return org.assertj.core.api.Assertions.fail("mapper 方法不存在：" + method, e);
        }
    }
}
