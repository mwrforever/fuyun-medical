package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.BedChangedPayload;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.BedAssignRequest;
import com.fuyun.inpatient.entity.Bed;
import com.fuyun.inpatient.entity.BedAssign;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.AssignType;
import com.fuyun.inpatient.enums.BedStatus;
import com.fuyun.inpatient.enums.TransferType;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.BedAssignMapper;
import com.fuyun.inpatient.mapper.BedMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.vo.BedMapVO;
import java.time.OffsetDateTime;
import java.util.List;
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
 * 床位管理域服务单测（Task 4 冻结集）：占床 CAS 防重（FREE/RESERVED 才可占、并发 0 行
 * IP-1006）、消毒/维修中禁分配（IP-1005）、床位图聚合（占用摘要+包床标记+降级）、
 * bed.changed 广播（V800 id 52 载荷逐字）、五态其余流转 CAS、Task 3 三联动入口与转出床
 * 消毒流转。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；条件更新
 * 断言直读 @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class BedServiceImplTest {

    /** 占用主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 占用患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 病区编码（床位归属） */
    private static final String WARD_ID = "W01";

    /** 床位行固定 id（流转用例载体） */
    private static final long BED_ID = 555L;

    @Mock
    private BedMapper bedMapper;

    @Mock
    private BedAssignMapper assignMapper;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<BedAssign> assignCaptor;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    private BedServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（bed 床位图面 + visit 占用摘要解析面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Bed.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        service = new BedServiceImpl(assignMapper, visitMapper, events);
        ReflectionTestUtils.setField(service, "baseMapper", bedMapper);
        OperatorContextHolder.set("nur-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("占床 CAS 防重：FREE→OCCUPIED 开 ADMISSION 流水并广播 bed.changed；已占用再占 IP-1006；并发 0 行 IP-1006")
    void assignEnforcesOccupyCasGuard() {
        // 占用主体在位且待入科态：直接分配快速通道成立
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.REGISTERED));
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casOccupy(BED_ID, VISIT_ID)).thenReturn(1);

        service.assign(BED_ID, new BedAssignRequest(VISIT_ID));

        // GC26 锚：占床 CAS 限定 FREE/RESERVED 两态可占（防重复占床硬防线）+ 显式 deleted=0
        String sql = recordSql(BedMapper.class, "casOccupy", Long.class, String.class);
        assertThat(sql)
                .contains("SET status = 'OCCUPIED', visit_id = #{visitId}")
                .contains("WHERE id = #{bedId}")
                .contains("status IN ('FREE', 'RESERVED')")
                .contains("deleted = 0");
        // 占用流水开账（只增表 ADMISSION 类型 + 操作者审计）
        verify(assignMapper).insert(assignCaptor.capture());
        BedAssign assignRow = assignCaptor.getValue();
        assertThat(assignRow.getBedId()).isEqualTo(BED_ID);
        assertThat(assignRow.getVisitId()).isEqualTo(VISIT_ID);
        assertThat(assignRow.getAssignType()).isEqualTo(AssignType.ADMISSION.getCode());
        assertThat(assignRow.getStartedAt()).isNotNull();
        assertThat(assignRow.getEndedAt()).isNull();
        assertThat(assignRow.getOperator()).isEqualTo("nur-01");
        // bed.changed 广播（V800 id 52 载荷逐字：wardId/bedId/bedNo/bedStatus/patientId）
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(InpatientMessagingConstants.EVENT_BED_CHANGED);
        assertThat(eventCaptor.getValue().payload())
                .isEqualTo(new BedChangedPayload(WARD_ID, BED_ID, "B01", BedStatus.OCCUPIED.getCode(), PATIENT_ID));

        // 已 OCCUPIED 再占：前置校验定性 IP-1006，零 CAS 零开账零广播
        clearInvocations(bedMapper, assignMapper, events);
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.OCCUPIED));
        assertThatThrownBy(() -> service.assign(BED_ID, new BedAssignRequest(VISIT_ID)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_OCCUPIED);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(bedMapper, never()).casOccupy(any(), any());

        // 前置校验过后 CAS 0 行（并发占床窗口）：同判 IP-1006
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casOccupy(BED_ID, VISIT_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.assign(BED_ID, new BedAssignRequest(VISIT_ID)))
                .isInstanceOf(BizException.class)
                .satisfies(
                        e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_OCCUPIED));
        verify(assignMapper, never()).insert(any(BedAssign.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("消毒/维修中禁分配与禁预占：DISINFECTING/MAINTENANCE → assign/reserve 抛 IP-1005")
    void assignAndReserveRejectUnavailableBeds() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.REGISTERED));
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.DISINFECTING));
        assertThatThrownBy(() -> service.assign(BED_ID, new BedAssignRequest(VISIT_ID)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(bedMapper, never()).casOccupy(any(), any());

        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.MAINTENANCE));
        assertThatThrownBy(() -> service.reserve(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));
        verify(bedMapper, never()).casReserve(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("床位图聚合：五态+包床标记+占用摘要；占用行缺就诊降级空摘要；空病区/无占用床零就诊查询")
    void bedMapAggregatesStatusAttrAndOccupiedSummary() {
        Bed free = bedRow(BedStatus.FREE);
        free.setId(501L);
        free.setBedNo("B01");
        Bed occupied = bedRow(BedStatus.OCCUPIED);
        occupied.setId(502L);
        occupied.setBedNo("B02");
        occupied.setBedAttr("PRIVATE");
        occupied.setVisitId(VISIT_ID);
        Bed disinfecting = bedRow(BedStatus.DISINFECTING);
        disinfecting.setId(503L);
        disinfecting.setBedNo("B03");
        Bed orphan = bedRow(BedStatus.OCCUPIED);
        orphan.setId(504L);
        orphan.setBedNo("B04");
        orphan.setVisitId("I2026092500009");
        // 同就诊重复占用行（脏数据）：摘要归并取首行不阻断床位图
        Bed duplicate = bedRow(BedStatus.OCCUPIED);
        duplicate.setId(505L);
        duplicate.setBedNo("B05");
        duplicate.setVisitId(VISIT_ID);
        // 占用态但 visit_id 缺失（脏数据）：摘要降级为空
        Bed nullVisit = bedRow(BedStatus.OCCUPIED);
        nullVisit.setId(506L);
        nullVisit.setBedNo("B06");
        nullVisit.setVisitId(null);
        when(bedMapper.selectList(any()))
                .thenReturn(List.of(free, occupied, disinfecting, orphan, duplicate, nullVisit));
        InpatientVisit visit = visitRow(VisitStatus.ADMITTED);
        // 就诊行重复返回（并发快照）：toMap 归并函数兜底取首行
        when(visitMapper.selectList(any())).thenReturn(List.of(visit, visit));

        List<BedMapVO> map = service.bedMap(WARD_ID);

        assertThat(map).hasSize(6);
        BedMapVO freeRow = map.get(0);
        assertThat(freeRow.bedStatus()).isEqualTo(BedStatus.FREE.getCode());
        assertThat(freeRow.occupiedVisit()).isNull();
        // 占用摘要（定位键+入科时点，敏感字段零载荷）与包床计费属性标记
        BedMapVO occupiedRow = map.get(1);
        assertThat(occupiedRow.bedAttr()).isEqualTo("PRIVATE");
        assertThat(occupiedRow.occupiedVisit()).isNotNull();
        assertThat(occupiedRow.occupiedVisit().visitId()).isEqualTo(VISIT_ID);
        assertThat(occupiedRow.occupiedVisit().patientId()).isEqualTo(PATIENT_ID);
        assertThat(occupiedRow.occupiedVisit().admittedAt()).isNotNull();
        assertThat(map.get(2).occupiedVisit()).isNull();
        // 占用床位缺就诊行/缺 visit_id（数据不一致）：摘要降级为空不阻断床位图
        assertThat(map.get(3).occupiedVisit()).isNull();
        assertThat(map.get(3).bedStatus()).isEqualTo(BedStatus.OCCUPIED.getCode());
        assertThat(map.get(4).occupiedVisit()).isNotNull();
        assertThat(map.get(5).occupiedVisit()).isNull();

        // 病区有床但零占用：占用收集短路返回，禁触就诊查询（MP in() 空集合生成非法 SQL 防线）
        clearInvocations(visitMapper);
        when(bedMapper.selectList(any())).thenReturn(List.of(free, disinfecting));
        assertThat(service.bedMap(WARD_ID)).hasSize(2);
        verifyNoMoreInteractions(visitMapper);

        // 空病区：床位零行即短路返回
        when(bedMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.bedMap(WARD_ID)).isEmpty();
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("预占与释放：FREE→RESERVED/RESERVED→FREE 全 CAS + bed.changed 广播；非预占态释放 IP-1005")
    void reserveAndReleaseMoveBetweenFreeAndReserved() {
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casReserve(BED_ID)).thenReturn(1);
        service.reserve(BED_ID);
        // GC26 锚：预占 CAS 限定 FREE 态且不绑定 visit_id（登记确认才签发）
        assertThat(recordSql(BedMapper.class, "casReserve", Long.class))
                .contains("SET status = 'RESERVED', visit_id = NULL")
                .contains("status = 'FREE'")
                .contains("deleted = 0");

        // 释放预占（住院证作废联动同源路径）：RESERVED→FREE 广播无主体
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.RESERVED));
        when(bedMapper.casRelease(BED_ID)).thenReturn(1);
        service.releaseForAdmission(BED_ID);
        assertThat(recordSql(BedMapper.class, "casRelease", Long.class))
                .contains("SET status = 'FREE', visit_id = NULL")
                .contains("status = 'RESERVED'");
        verify(events, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues().get(0).payload())
                .isEqualTo(new BedChangedPayload(WARD_ID, BED_ID, "B01", BedStatus.RESERVED.getCode(), null));
        assertThat(eventCaptor.getAllValues().get(1).payload())
                .isEqualTo(new BedChangedPayload(WARD_ID, BED_ID, "B01", BedStatus.FREE.getCode(), null));

        // 非预占态释放（占用态须走转床/转科/出院编排）：IP-1005
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.OCCUPIED));
        assertThatThrownBy(() -> service.release(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));
        // 前置校验过后 CAS 0 行（并发释放窗口）：同判 IP-1005
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.RESERVED));
        when(bedMapper.casRelease(BED_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.release(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));

        // 已预占再预占（预占与占床共用占用语义防线）：IP-1006
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.RESERVED));
        assertThatThrownBy(() -> service.reserve(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(
                        e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_OCCUPIED));
        // 前置校验过后 CAS 0 行（并发预占窗口）：同判 IP-1006
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casReserve(BED_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.reserve(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(
                        e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_OCCUPIED));
    }

    @Test
    @DisplayName("消毒完成与维修两态流转：DISINFECTING→FREE、FREE⇄MAINTENANCE 全 CAS + 广播；违例态与并发 0 行 IP-1005")
    void disinfectAndMaintenanceTransitionsEnforceCas() {
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.DISINFECTING));
        when(bedMapper.casDisinfectDone(BED_ID)).thenReturn(1);
        service.disinfectDone(BED_ID);
        assertThat(recordSql(BedMapper.class, "casDisinfectDone", Long.class))
                .contains("SET status = 'FREE'")
                .contains("status = 'DISINFECTING'")
                .contains("deleted = 0");

        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casMaintain(BED_ID)).thenReturn(1);
        service.maintain(BED_ID);
        assertThat(recordSql(BedMapper.class, "casMaintain", Long.class))
                .contains("SET status = 'MAINTENANCE'")
                .contains("status = 'FREE'");

        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.MAINTENANCE));
        when(bedMapper.casMaintainDone(BED_ID)).thenReturn(1);
        service.maintainDone(BED_ID);
        assertThat(recordSql(BedMapper.class, "casMaintainDone", Long.class))
                .contains("SET status = 'FREE'")
                .contains("status = 'MAINTENANCE'");

        // 三次成功迁移的广播时序：消毒完成回 FREE → 转维修 → 维修恢复
        verify(events, times(3)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(event -> ((BedChangedPayload) event.payload()).bedStatus())
                .containsExactly(BedStatus.FREE.getCode(), BedStatus.MAINTENANCE.getCode(), BedStatus.FREE.getCode());

        // 违例态：非消毒中确认完成/占用中转维修/非维修中恢复均拒 IP-1005
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        assertThatThrownBy(() -> service.disinfectDone(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.OCCUPIED));
        assertThatThrownBy(() -> service.maintain(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        assertThatThrownBy(() -> service.maintainDone(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));

        // 并发 0 行（前置校验与 CAS 之间的状态迁移窗口）：消毒完成/转维修/维修恢复同判 IP-1005
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.DISINFECTING));
        when(bedMapper.casDisinfectDone(BED_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.disinfectDone(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casMaintain(BED_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.maintain(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.MAINTENANCE));
        when(bedMapper.casMaintainDone(BED_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.maintainDone(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("转出床流转：OCCUPIED→DISINFECTING 闭合流水广播；非占用态 IP-1005；主体不符/并发流转/无未闭合流水 IP-1023")
    void transferOutClosesAssignAndMovesBedToDisinfecting() {
        when(bedMapper.selectById(BED_ID)).thenReturn(occupiedBed());
        when(bedMapper.casDisinfect(BED_ID, VISIT_ID)).thenReturn(1);
        when(assignMapper.closeOpen(any(), any(), any())).thenReturn(1);

        service.transferOut(BED_ID, VISIT_ID);

        // GC26 锚：转出 CAS 限定占用态且 bed_id+visit_id 双条件（防误流转他人床位）
        assertThat(recordSql(BedMapper.class, "casDisinfect", Long.class, String.class))
                .contains("SET status = 'DISINFECTING', visit_id = NULL")
                .contains("status = 'OCCUPIED'")
                .contains("visit_id = #{visitId}")
                .contains("deleted = 0");
        // 未继行闭合：ended_at 落值（uk_bed_assign_open 兜底后每床至多一条未闭合）
        verify(assignMapper).closeOpen(any(), any(), any());
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().payload())
                .isEqualTo(new BedChangedPayload(WARD_ID, BED_ID, "B01", BedStatus.DISINFECTING.getCode(), null));

        // 非占用态流转（空床/消毒中）：IP-1005
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        assertThatThrownBy(() -> service.transferOut(BED_ID, VISIT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.BED_STATE_NOT_ALLOWED));

        // 占用主体不符（床位被他人占用）：IP-1023
        Bed others = occupiedBed();
        others.setVisitId("I2026092500002");
        when(bedMapper.selectById(BED_ID)).thenReturn(others);
        assertThatThrownBy(() -> service.transferOut(BED_ID, VISIT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // CAS 0 行（并发流转窗口）：IP-1023
        when(bedMapper.selectById(BED_ID)).thenReturn(occupiedBed());
        when(bedMapper.casDisinfect(BED_ID, VISIT_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.transferOut(BED_ID, VISIT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // 无未闭合流水（数据不一致——只增表开账缺失）：IP-1023
        when(bedMapper.casDisinfect(BED_ID, VISIT_ID)).thenReturn(1);
        when(assignMapper.closeOpen(any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.transferOut(BED_ID, VISIT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("编排占床联动：occupyForTransfer 按编排类型开流水（BED_CHANGE/WARD_TRANSFER）；入科/预约联动同源 CAS")
    void occupyForTransferOpensTypedAssignRows() {
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.RESERVED));
        when(bedMapper.casOccupy(BED_ID, VISIT_ID)).thenReturn(1);

        service.occupyForTransfer(BED_ID, VISIT_ID, PATIENT_ID, TransferType.BED_CHANGE);
        service.occupyForTransfer(BED_ID, VISIT_ID, PATIENT_ID, TransferType.WARD_TRANSFER);

        // 编排类型 → 流水类型（转床/转科转入），RESERVED→OCCUPIED 目标床占床同源 CAS
        verify(assignMapper, times(2)).insert(assignCaptor.capture());
        assertThat(assignCaptor.getAllValues())
                .extracting(BedAssign::getAssignType)
                .containsExactly(AssignType.BED_CHANGE.getCode(), AssignType.WARD_TRANSFER.getCode());
        verify(events, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(event -> ((BedChangedPayload) event.payload()).patientId())
                .containsExactly(PATIENT_ID, PATIENT_ID);

        // 入科确认联动入口：ADMISSION 流水同源占床 CAS
        service.occupyForAdmission(BED_ID, VISIT_ID, PATIENT_ID);
        verify(assignMapper, times(3)).insert(assignCaptor.capture());
        assertThat(assignCaptor.getValue().getAssignType()).isEqualTo(AssignType.ADMISSION.getCode());

        // 预约入院联动入口：目标床位置 RESERVED（schedule 面）
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casReserve(BED_ID)).thenReturn(1);
        service.reserveForAdmission(BED_ID);
        verify(bedMapper).casReserve(BED_ID);
    }

    @Test
    @DisplayName("错误面与审计回退：床位不存在 IP-1004；就诊不存在 IP-1007；就诊状态不可分配 IP-1008；开账唯一冲突转 IP-1023；免登录回退 system")
    void rejectsMissingRowsAndInvalidStatesAndFallsBackToSystemOperator() {
        // 床位不存在：全操作统一 IP-1004
        when(bedMapper.selectById(BED_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.reserve(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_NOT_FOUND);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        assertThatThrownBy(() -> service.release(BED_ID))
                .isInstanceOf(BizException.class)
                .satisfies(
                        e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_NOT_FOUND));
        assertThatThrownBy(() -> service.transferOut(BED_ID, VISIT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(
                        e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.BED_NOT_FOUND));

        // 占用主体就诊不存在 / 非待入科·在院态（已出院床位禁再分配）：IP-1007/IP-1008
        clearInvocations(bedMapper);
        when(visitMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.assign(BED_ID, new BedAssignRequest(VISIT_ID)))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.DISCHARGED));
        assertThatThrownBy(() -> service.assign(BED_ID, new BedAssignRequest(VISIT_ID)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_STATE_NOT_ALLOWED));
        verify(bedMapper, never()).selectById(any());

        // 开账唯一冲突（uk_bed_assign_open 每床未闭合行唯一兜底——并发开账窗口）：转 IP-1023
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED));
        when(bedMapper.selectById(BED_ID)).thenReturn(bedRow(BedStatus.FREE));
        when(bedMapper.casOccupy(BED_ID, VISIT_ID)).thenReturn(1);
        when(assignMapper.insert(any(BedAssign.class))).thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> service.assign(BED_ID, new BedAssignRequest(VISIT_ID)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(events);

        // 免登录上下文回退：流水操作者落 system（与 V903 列默认同源）
        OperatorContextHolder.clear();
        when(assignMapper.insert(any(BedAssign.class))).thenReturn(1);
        service.occupyForAdmission(BED_ID, VISIT_ID, PATIENT_ID);
        verify(assignMapper, times(2)).insert(assignCaptor.capture());
        assertThat(assignCaptor.getValue().getOperator()).isEqualTo("system");
    }

    /** 构造床位行（状态可变，流转用例载体；默认 W01/B01/普通属性）。 */
    private Bed bedRow(BedStatus status) {
        Bed row = new Bed();
        row.setId(BED_ID);
        row.setBedNo("B01");
        row.setWardId(WARD_ID);
        row.setBedAttr("NORMAL");
        row.setStatus(status.getCode());
        return row;
    }

    /** 构造被主体占用的床位行（OCCUPIED + visit_id 绑定——转出流转用例载体）。 */
    private Bed occupiedBed() {
        Bed row = bedRow(BedStatus.OCCUPIED);
        row.setVisitId(VISIT_ID);
        return row;
    }

    /** 构造占用就诊行（床位图摘要解析用例载体）。 */
    private InpatientVisit visitRow(VisitStatus status) {
        InpatientVisit row = new InpatientVisit();
        row.setId(7001L);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setAdmittedAt(OffsetDateTime.now());
        row.setStatus(status.getCode());
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
            return fail("mapper 方法不存在：" + method, e);
        }
    }
}
