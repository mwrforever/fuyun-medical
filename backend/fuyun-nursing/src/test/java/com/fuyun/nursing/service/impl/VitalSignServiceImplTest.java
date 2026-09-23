package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.VitalSignRecordedPayload;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.constants.NursingVitalThresholds;
import com.fuyun.nursing.constants.VitalSignValues;
import com.fuyun.nursing.dto.VitalSignRecordRequest;
import com.fuyun.nursing.dto.VitalSignRejectRequest;
import com.fuyun.nursing.entity.VitalSignRecord;
import com.fuyun.nursing.enums.VitalReviewStatus;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.VitalSignRecordMapper;
import com.fuyun.nursing.service.INursingRecordService;
import com.fuyun.nursing.service.ITemperatureChartService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.VitalSignVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 生命体征域服务单测（Task 5 冻结用例集 + 补充锚）：点测落卡直 CONFIRMED、生理极限拒收
 * （NS-1005）、落卡唯一约束幂等拒绝（NS-1016）、同刻异部位并存且 abnormal 独立判定、观察行
 * 归集二分支（正常合并/异常独立落行）、体温单 VITAL 条目写入（失败上抛不吞）、IoT 列 P1
 * 恒空锚（GC17-①）、待复核清单、复核 CAS 流转（GC26）与转正入卡事件、阈值边界判定。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；条件更新断言直读
 * @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class VitalSignServiceImplTest {

    /** I 型 14 位合法 visit_id（在区校验守卫链通过值） */
    private static final String VISIT = "I2026092200001";

    /** 病区编码（在区行归一数据源） */
    private static final String WARD = "W01";

    /** 首条插入行固定 id（insert 桩回填值） */
    private static final long ROW_ID = 301L;

    /** 待复核行固定 id（复核用例载体） */
    private static final long PENDING_ID = 401L;

    /** 待复核行固定测量时点（复核转正条目/事件载荷断言基准） */
    private static final OffsetDateTime T1000 = OffsetDateTime.of(2026, 9, 22, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private VitalSignRecordMapper vitalMapper;

    @Mock
    private IWardMetaService wardMetaService;

    @Mock
    private INursingRecordService recordService;

    @Mock
    private ITemperatureChartService chartService;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<VitalSignRecord> rowCaptor;

    @Captor
    private ArgumentCaptor<NursingDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Instant> entryTimeCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<VitalSignRecord>> queryCaptor;

    private VitalSignServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（体征记录读面）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), VitalSignRecord.class);
    }

    @BeforeEach
    void setUp() {
        service = new VitalSignServiceImpl(vitalMapper, wardMetaService, recordService, chartService, events);
        ReflectionTestUtils.setField(service, "baseMapper", vitalMapper);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("手工录入：source=MANUAL 直落 CONFIRMED（reviewedBy/reviewedAt 随录盖章），无待复核行产生")
    void recordManualSourceLandsConfirmedDirectly() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));

        VitalSignVO vo = service.record(normalRequest(null));

        assertThat(vo.reviewStatus()).isEqualTo(VitalReviewStatus.CONFIRMED.getCode());
        assertThat(vo.reviewedBy()).isEqualTo("nurse-01");
        assertThat(vo.reviewedAt()).isNotNull();
        verify(vitalMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getSource()).isEqualTo("MANUAL");
        assertThat(rowCaptor.getValue().getReviewStatus()).isEqualTo(VitalReviewStatus.CONFIRMED.getCode());
        assertThat(rowCaptor.getValue().getReviewedBy()).isEqualTo("nurse-01");
        assertThat(rowCaptor.getValue().getReviewedAt()).isNotNull();
        // 录入即转正：单行落库即 CONFIRMED，无 PENDING_REVIEW 待复核行产生
        verify(vitalMapper, times(1)).insert(any(VitalSignRecord.class));
    }

    @Test
    @DisplayName("PDA 录入：source=PDA 同样直落 CONFIRMED（Spec :130 护士手工/PDA 点测值直接 CONFIRMED）")
    void recordPdaSourceLandsConfirmedDirectly() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));

        VitalSignVO vo = service.record(normalRequest("PDA"));

        assertThat(vo.reviewStatus()).isEqualTo(VitalReviewStatus.CONFIRMED.getCode());
        verify(vitalMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getSource()).isEqualTo("PDA");
        assertThat(rowCaptor.getValue().getReviewStatus()).isEqualTo(VitalReviewStatus.CONFIRMED.getCode());
    }

    @Test
    @DisplayName("生理极限拒收：体温 44.0 越极限拒 NS-1005（消息含「生理极限」），守卫链第一步、insert 未被调")
    void recordRejectsValueBeyondPhysiologicalLimit() {
        VitalSignRecordRequest req = new VitalSignRecordRequest(
                VISIT, null, new BigDecimal("44.0"), "AXILLARY", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.record(req)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.VITAL_OUT_OF_RANGE);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1005");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains("生理极限");
        });
        // 极限闸门为守卫链第一步：在区校验/落库/归集/条目/事件全程未触达
        verifyNoInteractions(vitalMapper, wardMetaService, recordService, chartService, events);
    }

    @Test
    @DisplayName("落卡唯一约束：同 (visit_id, measured_at, 部位) 二次录入唯一冲突转 NS-1016 幂等拒绝，不覆盖首值")
    void recordRejectsDuplicateAtSameTimeAndSite() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_vital_sign_visit_time_site"));

        assertThatThrownBy(() -> service.record(normalRequest(null))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 幂等拒绝发生在 insert（守卫链③）：归集/条目/事件全程未触达（首值权威，无覆盖写）
        verifyNoInteractions(recordService, chartService, events);
    }

    @Test
    @DisplayName("同刻异部位并存：AXILLARY 与 ORAL 均落卡（唯一键第四维为部位），第二行 abnormal 独立判定")
    void recordAllowsSameTimeDifferentSite() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        AtomicLong ids = new AtomicLong(ROW_ID);
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, VitalSignRecord.class).setId(ids.getAndIncrement());
            return 1;
        });
        VitalSignRecordRequest axillary = new VitalSignRecordRequest(
                VISIT, null, new BigDecimal("38.6"), "AXILLARY", null, null, null, null, null, null, null, null);
        VitalSignRecordRequest oral = new VitalSignRecordRequest(
                VISIT, null, new BigDecimal("36.5"), "ORAL", null, null, null, null, null, null, null, null);

        service.record(axillary);
        service.record(oral);

        verify(vitalMapper, times(2)).insert(rowCaptor.capture());
        List<VitalSignRecord> rows = rowCaptor.getAllValues();
        assertThat(rows).extracting(VitalSignRecord::getTempSite).containsExactly("AXILLARY", "ORAL");
        // 第二行 abnormal 独立判定：腋温 38.6 异常、口温 36.5 正常，互不污染
        assertThat(rows.get(0).getAbnormalFlag()).isTrue();
        assertThat(rows.get(1).getAbnormalFlag()).isFalse();
        // 唯一权威在 DB 部分唯一索引：服务端无应用层查重查询（禁查—插竞态假象）
        verify(vitalMapper, never()).selectOne(any());
        verify(recordService).appendObservation(eq(VISIT), contains("38.6"), eq(true), eq("nurse-01"));
        verify(recordService).appendObservation(eq(VISIT), contains("36.5"), eq(false), eq("nurse-01"));
    }

    @Test
    @DisplayName("观察行归集（合并分支）：全项正常 → appendObservation 走合并（abnormal=false）；事件载荷 abnormal=false")
    void recordWithinNormalRangeCallsMergeObservation() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));

        service.record(new VitalSignRecordRequest(
                VISIT, null, new BigDecimal("36.5"), "AXILLARY", 80, 18, 120, 80, 98, null, null, null));

        // 归集口径锚（Task 11 IT 断言依据）：全部指标正常 → 合并分支 abnormal=false，调用恰 1 次
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(recordService, times(1))
                .appendObservation(eq(VISIT), contentCaptor.capture(), eq(false), eq("nurse-01"));
        assertThat(contentCaptor.getValue()).isNotBlank();

        // 同一断言核事件载荷：nursing.vital-sign.recorded 载荷 abnormal=false（GC8 事务内发布）
        verify(events).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED);
        VitalSignRecordedPayload payload = (VitalSignRecordedPayload) event.payload();
        assertThat(payload.abnormal()).isFalse();
        assertThat(payload.patientId()).isEqualTo(7L);
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.source()).isEqualTo("MANUAL");
        assertThat(payload.reviewStatus()).isEqualTo(VitalReviewStatus.CONFIRMED.getCode());
    }

    @Test
    @DisplayName("观察行归集（异常分支）：体温 38.6 → 独立落行（abnormal=true），内容含指标/值/范围文本；事件载荷 abnormal=true")
    void recordAbnormalCallsNewObservationWithItemDetail() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));

        service.record(new VitalSignRecordRequest(
                VISIT, null, new BigDecimal("38.6"), "AXILLARY", null, null, null, null, null, null, null, null));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(recordService, times(1)).appendObservation(eq(VISIT), contentCaptor.capture(), eq(true), eq("nurse-01"));
        // 异常项描述冻结形态：指标名 + 测量值 + 方向 + 正常范围文本
        assertThat(contentCaptor.getValue()).contains("体温").contains("38.6").contains("36.0–37.2℃");
        verify(vitalMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getAbnormalFlag()).isTrue();

        verify(events).publishEvent(eventCaptor.capture());
        VitalSignRecordedPayload payload =
                (VitalSignRecordedPayload) eventCaptor.getValue().payload();
        assertThat(payload.abnormal()).isTrue();
    }

    @Test
    @DisplayName("体温单条目写入：appendVitalEntry 被调 1 次（visitId/entryTime/vitalRef/tempSite 四参逐项断言）")
    void recordWritesChartVitalEntry() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));

        service.record(normalRequest(null));

        verify(chartService, times(1))
                .appendVitalEntry(eq(VISIT), entryTimeCaptor.capture(), eq(ROW_ID), eq("AXILLARY"));
        verify(vitalMapper).insert(rowCaptor.capture());
        // 条目时点=体征测量时点（体征域服务端权威时间，体温单不另取钟）
        assertThat(entryTimeCaptor.getValue())
                .isEqualTo(rowCaptor.getValue().getMeasuredAt().toInstant());
    }

    @Test
    @DisplayName("条目写入失败上抛（不吞）：appendVitalEntry 异常原样外传且事件不发布（发布步在条目步之后）")
    void recordPropagatesChartEntryFailure() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));
        BizException conflict =
                new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "体温单条目已存在（幂等拒绝，不覆盖首值）");
        // appendVitalEntry 为 void 方法：异常桩须走 doThrow 形态
        doThrow(conflict).when(chartService).appendVitalEntry(anyString(), any(Instant.class), anyLong(), anyString());

        assertThatThrownBy(() -> service.record(normalRequest(null))).isSameAs(conflict);
        verify(events, never()).publishEvent(any(NursingDomainEvent.class));
    }

    @Test
    @DisplayName("IoT 列 P1 恒空锚：落库行 iotQuality/conflictRef 均为 null（GC17-① 列落位不落消费者）")
    void recordAnnotatesIotColumnsAsNullInP1() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));

        service.record(normalRequest(null));

        verify(vitalMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getIotQuality()).isNull();
        assertThat(rowCaptor.getValue().getConflictRef()).isNull();
    }

    @Test
    @DisplayName("待复核清单：仅返回病区 PENDING_REVIEW 行（谓词钉死 ward_id + review_status），测量时点升序")
    void pendingReviewListsOnlyPendingRowsOfWard() {
        when(vitalMapper.selectList(any())).thenReturn(List.of(pendingRow()));

        List<VitalSignVO> result = service.pendingReview(WARD);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(PENDING_ID);
        assertThat(result.get(0).reviewStatus()).isEqualTo(VitalReviewStatus.PENDING_REVIEW.getCode());
        // 谓词根因锚：病区过滤 + 仅待复核行（CONFIRMED/REJECTED 行被 DB 谓词滤除的依据）
        verify(vitalMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<VitalSignRecord> wrapper = rendered(queryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(WARD, VitalReviewStatus.PENDING_REVIEW.getCode());
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY").contains("measured_at");
    }

    @Test
    @DisplayName("复核转正：CAS 命中后补写体温单条目并发布转正入卡事件（载荷 CONFIRMED）；重复 confirm 拒 NS-1015")
    void confirmTransitionsPendingToConfirmed() {
        when(vitalMapper.casReview(PENDING_ID, VitalReviewStatus.CONFIRMED.getCode(), "nurse-01", null))
                .thenReturn(1, 0);
        when(vitalMapper.selectById(PENDING_ID)).thenReturn(confirmedRow());

        VitalSignVO vo = service.confirm(PENDING_ID);

        assertThat(vo.reviewStatus()).isEqualTo(VitalReviewStatus.CONFIRMED.getCode());
        assertThat(vo.reviewedBy()).isEqualTo("nurse-01");
        // 转正入权威栏：补写体温单 VITAL 条目（时点=测量时点、vitalRef=体征行、部位随行）
        verify(chartService, times(1))
                .appendVitalEntry(eq(VISIT), eq(T1000.toInstant()), eq(PENDING_ID), eq("AXILLARY"));
        // 转正入卡事件：载荷 reviewStatus=CONFIRMED（Spec :137 流程 4）
        verify(events, times(1)).publishEvent(eventCaptor.capture());
        VitalSignRecordedPayload payload =
                (VitalSignRecordedPayload) eventCaptor.getValue().payload();
        assertThat(payload.reviewStatus()).isEqualTo(VitalReviewStatus.CONFIRMED.getCode());
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.patientId()).isEqualTo(7L);
        assertThat(payload.measuredAt()).isEqualTo(T1000.toInstant());
        assertThat(payload.source()).isEqualTo("IOT");
        assertThat(payload.abnormal()).isTrue();

        // 重复转正：CAS 0 行（已非 PENDING_REVIEW）→ NS-1015，条目与事件不重复
        assertThatThrownBy(() -> service.confirm(PENDING_ID)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.VITAL_REVIEW_STATE_NOT_ALLOWED);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1015");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(chartService, times(1)).appendVitalEntry(anyString(), any(Instant.class), anyLong(), anyString());
        verify(events, times(1)).publishEvent(any(NursingDomainEvent.class));
    }

    @Test
    @DisplayName("复核驳回：CAS 置 REJECTED 且原因落 remark、reviewedBy 盖章；不写体温单条目、不发事件")
    void rejectKeepsRowOutOfAuthorityColumn() {
        when(vitalMapper.casReview(PENDING_ID, VitalReviewStatus.REJECTED.getCode(), "nurse-01", "体温数值存疑"))
                .thenReturn(1);
        when(vitalMapper.selectById(PENDING_ID)).thenReturn(rejectedRow());

        VitalSignVO vo = service.reject(PENDING_ID, new VitalSignRejectRequest("体温数值存疑"));

        assertThat(vo.reviewStatus()).isEqualTo(VitalReviewStatus.REJECTED.getCode());
        assertThat(vo.remark()).isEqualTo("体温数值存疑");
        assertThat(vo.reviewedBy()).isEqualTo("nurse-01");
        // 驳回行不入权威栏：零条目写入、零事件发布
        verifyNoInteractions(chartService, events);
        // GC26 可执行锚：复核流转必须为 @Update 注解 SQL 条件更新（仅 PENDING_REVIEW + deleted=0）
        String sql = recordSql("casReview", long.class, String.class, String.class, String.class);
        assertThat(sql)
                .contains("review_status = #{targetStatus}")
                .contains("reviewed_by = #{reviewedBy}")
                .contains("reviewed_at = now()")
                .contains("WHERE id = #{id}")
                .contains("review_status = 'PENDING_REVIEW'")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("患者体征查询：patientId 过滤 + from/to 窗口含头不含尾，测量时点升序（DB 侧排序钉死）")
    void listByPatientSupportsWindowAndOrdersByMeasuredAt() {
        when(vitalMapper.selectList(any())).thenReturn(List.of(confirmedRow()));

        List<VitalSignVO> windowed =
                service.listByPatient(7L, T1000.toInstant(), T1000.plusHours(1).toInstant());
        List<VitalSignVO> all = service.listByPatient(7L, null, null);

        assertThat(windowed).hasSize(1);
        assertThat(windowed.get(0).id()).isEqualTo(PENDING_ID);
        assertThat(all).hasSize(1);
        verify(vitalMapper, times(2)).selectList(queryCaptor.capture());
        LambdaQueryWrapper<VitalSignRecord> windowWrapper =
                rendered(queryCaptor.getAllValues().get(0));
        assertThat(windowWrapper.getParamNameValuePairs().values()).contains(7L);
        assertThat(windowWrapper.getSqlSegment())
                .contains("measured_at >=")
                .contains("measured_at <")
                .contains("ORDER BY")
                .contains("measured_at");
        LambdaQueryWrapper<VitalSignRecord> allWrapper =
                rendered(queryCaptor.getAllValues().get(1));
        assertThat(allWrapper.getSqlSegment())
                .doesNotContain("measured_at >=")
                .contains("ORDER BY")
                .contains("measured_at");
    }

    @Test
    @DisplayName("阈值边界判定：正常范围含端点（36.0/37.2/60/100/12/20/90/140/60/90/95/NRS3 全正常）")
    void thresholdNormalBoundariesAreInclusive() {
        VitalSignValues allAtBounds = new VitalSignValues(new BigDecimal("36.0"), "AXILLARY", 60, 12, 90, 60, 95, 3);
        assertThat(NursingVitalThresholds.isWithinNormalRange(allAtBounds)).isTrue();
        assertThat(NursingVitalThresholds.abnormalItems(allAtBounds)).isEmpty();

        VitalSignValues allAtUpperBounds = new VitalSignValues(new BigDecimal("37.2"), "ORAL", 100, 20, 140, 90, 98, 0);
        assertThat(NursingVitalThresholds.isWithinNormalRange(allAtUpperBounds)).isTrue();

        // 上界刚越出：体温 37.3、脉搏 101、呼吸 21、收缩压 141、舒张压 91、血氧 94、疼痛 4 → 七项全异常
        VitalSignValues justOutside = new VitalSignValues(new BigDecimal("37.3"), "ORAL", 101, 21, 141, 91, 94, 4);
        assertThat(NursingVitalThresholds.isWithinNormalRange(justOutside)).isFalse();
        assertThat(NursingVitalThresholds.abnormalItems(justOutside)).hasSize(7);
    }

    @Test
    @DisplayName("生理极限闸门：边界值放行（34/43/20/200/5/60/40/300/20/200/50），越界拒 NS-1005 且消息含「生理极限」")
    void physiologicalLimitBoundariesPassAndViolationsReject() {
        VitalSignValues atLowerBounds =
                new VitalSignValues(new BigDecimal("34.0"), "AXILLARY", 20, 5, 40, 20, 50, null);
        VitalSignValues atUpperBounds =
                new VitalSignValues(new BigDecimal("43.0"), "RECTAL", 200, 60, 300, 200, 100, null);
        assertThatCode(() -> NursingVitalThresholds.assertWithinPhysiologicalLimit(atLowerBounds))
                .doesNotThrowAnyException();
        assertThatCode(() -> NursingVitalThresholds.assertWithinPhysiologicalLimit(atUpperBounds))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> NursingVitalThresholds.assertWithinPhysiologicalLimit(
                        new VitalSignValues(new BigDecimal("44.0"), null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.VITAL_OUT_OF_RANGE);
                    assertThat(e.getMessage()).contains("生理极限").contains("44.0");
                });
        assertThatThrownBy(() -> NursingVitalThresholds.assertWithinPhysiologicalLimit(
                        new VitalSignValues(null, null, null, null, null, null, 49, null)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.getMessage()).contains("生理极限").contains("49"));
    }

    @Test
    @DisplayName("P1 双源边界：source=IOT 拒 NS-1019（IoT 归 P2 无处理路径）、tempSite code 非法拒 NS-1019，不落库")
    void recordRejectsIotSourceAndUnknownTempSiteInP1() {
        VitalSignRecordRequest iot = new VitalSignRecordRequest(
                VISIT, "IOT", new BigDecimal("36.5"), "AXILLARY", null, null, null, null, null, null, null, null);
        VitalSignRecordRequest badSite = new VitalSignRecordRequest(
                VISIT, null, new BigDecimal("36.5"), "EAR", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.record(iot)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
            assertThat(e.getMessage()).contains("P2");
        });
        assertThatThrownBy(() -> service.record(badSite)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
        });
        verifyNoInteractions(vitalMapper, wardMetaService, recordService, chartService, events);
    }

    @Test
    @DisplayName("在区校验：IWardMetaService 查无在区行拒 NS-1004（患者不在区），不发号不落库不归集")
    void recordRejectsNotInWardVisit() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(
                        NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + VISIT));

        assertThatThrownBy(() -> service.record(normalRequest(null))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PATIENT_BLOCKED);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1004");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(vitalMapper, never()).insert(any(VitalSignRecord.class));
        verifyNoInteractions(recordService, chartService, events);
    }

    @Test
    @DisplayName("在区校验：病区服务其他业务异常原样透传（仅在区缺失才翻译为 NS-1004）")
    void recordPropagatesUnrelatedWardBizException() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "其他校验失败"));

        assertThatThrownBy(() -> service.record(normalRequest(null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verify(vitalMapper, never()).insert(any(VitalSignRecord.class));
    }

    @Test
    @DisplayName("正常归集内容覆盖部分测量：单侧血压与疼痛评分各自入摘要（收缩压/舒张压/疼痛分支）")
    void recordNormalSummaryCoversPartialVitals() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(vitalMapper.insert(any(VitalSignRecord.class))).thenAnswer(insertWithId(ROW_ID));
        VitalSignRecordRequest systolicOnly =
                new VitalSignRecordRequest(VISIT, null, null, null, null, null, 120, null, null, null, null, 2);
        VitalSignRecordRequest diastolicOnly =
                new VitalSignRecordRequest(VISIT, null, null, null, null, null, null, 80, null, null, null, null);

        service.record(systolicOnly);
        service.record(diastolicOnly);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(recordService, times(2))
                .appendObservation(eq(VISIT), contentCaptor.capture(), eq(false), eq("nurse-01"));
        assertThat(contentCaptor.getAllValues().get(0)).contains("收缩压 120 mmHg").contains("疼痛 NRS 2 分");
        assertThat(contentCaptor.getAllValues().get(1)).contains("舒张压 80 mmHg");
    }

    @Test
    @DisplayName("复核转正回读缺失：CAS 命中后行被并发逻辑删（selectById 空）拒 NS-1016，不写条目不发事件")
    void confirmFailsWhenRowVanishesAfterCas() {
        when(vitalMapper.casReview(PENDING_ID, VitalReviewStatus.CONFIRMED.getCode(), "nurse-01", null))
                .thenReturn(1);
        when(vitalMapper.selectById(PENDING_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.confirm(PENDING_ID)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
        });
        verifyNoInteractions(chartService, events);
    }

    @Test
    @DisplayName("复核驳回状态违例：CAS 0 行（不存在或已转正/已驳回）拒 NS-1015，零后续副作用")
    void rejectRejectsNonPendingRow() {
        when(vitalMapper.casReview(PENDING_ID, VitalReviewStatus.REJECTED.getCode(), "nurse-01", "体温数值存疑"))
                .thenReturn(0);

        assertThatThrownBy(() -> service.reject(PENDING_ID, new VitalSignRejectRequest("体温数值存疑")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.VITAL_REVIEW_STATE_NOT_ALLOWED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1015");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(vitalMapper, never()).selectById(PENDING_ID);
        verifyNoInteractions(chartService, events);
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 全项正常录入请求（体温 36.5 腋温 + 脉搏/呼吸/血压/血氧全正常；source 可指定）。 */
    private VitalSignRecordRequest normalRequest(String source) {
        return new VitalSignRecordRequest(
                VISIT, source, new BigDecimal("36.5"), "AXILLARY", 80, 18, 120, 80, 98, null, null, null);
    }

    /** 在区详情卡替身（W01/床 01/患者 7；IWardMetaService.detail 的归一数据源）。 */
    private WardPatientDetailVO detailVO() {
        return new WardPatientDetailVO(
                WARD,
                "01",
                7L,
                VISIT,
                "张三",
                null,
                null,
                "NORMAL",
                "",
                false,
                "",
                OffsetDateTime.now(),
                List.of(),
                List.of(),
                List.of());
    }

    /** 待复核行替身（IOT 源、PENDING_REVIEW、体温 38.6 腋温异常；复核用例载体）。 */
    private VitalSignRecord pendingRow() {
        VitalSignRecord row = new VitalSignRecord();
        row.setId(PENDING_ID);
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        row.setWardId(WARD);
        row.setMeasuredAt(T1000);
        row.setTemperature(new BigDecimal("38.6"));
        row.setTempSite("AXILLARY");
        row.setSource("IOT");
        row.setReviewStatus(VitalReviewStatus.PENDING_REVIEW.getCode());
        row.setAbnormalFlag(true);
        return row;
    }

    /** 转正后行替身（读后写替身：CAS 命中后的回读态，reviewedBy=nurse-01）。 */
    private VitalSignRecord confirmedRow() {
        VitalSignRecord row = pendingRow();
        row.setReviewStatus(VitalReviewStatus.CONFIRMED.getCode());
        row.setReviewedBy("nurse-01");
        row.setReviewedAt(T1000);
        return row;
    }

    /** 驳回后行替身（读后写替身：REJECTED + 原因留痕 + 复核人盖章）。 */
    private VitalSignRecord rejectedRow() {
        VitalSignRecord row = pendingRow();
        row.setReviewStatus(VitalReviewStatus.REJECTED.getCode());
        row.setReviewedBy("nurse-01");
        row.setReviewedAt(T1000);
        row.setRemark("体温数值存疑");
        return row;
    }

    /** insert 桩：回填固定 id 并返回影响行数 1。 */
    private org.mockito.stubbing.Answer<Integer> insertWithId(long id) {
        return inv -> {
            inv.getArgument(0, VitalSignRecord.class).setId(id);
            return 1;
        };
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<VitalSignRecord> rendered(Wrapper<VitalSignRecord> captured) {
        LambdaQueryWrapper<VitalSignRecord> wrapper = (LambdaQueryWrapper<VitalSignRecord>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /**
     * 直读 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。
     *
     * @param method     mapper 方法名
     * @param paramTypes 方法参数类型（重载定位）
     * @return 拼接后的注解 SQL 全文
     */
    private String recordSql(String method, Class<?>... paramTypes) {
        try {
            Update update =
                    VitalSignRecordMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
    }
}
