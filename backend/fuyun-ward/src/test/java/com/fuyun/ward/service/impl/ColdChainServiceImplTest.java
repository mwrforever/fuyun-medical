package com.fuyun.ward.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.ward.api.ColdChainAlertArchivedPayload;
import com.fuyun.ward.api.WardErrorCode;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.dto.RegisterColdChainRecordRequest;
import com.fuyun.ward.dto.SaveColdChainArchiveRequest;
import com.fuyun.ward.entity.ColdChainArchiveEntity;
import com.fuyun.ward.entity.ColdChainRecordEntity;
import com.fuyun.ward.enums.ColdChainPurpose;
import com.fuyun.ward.enums.ColdChainRecordType;
import com.fuyun.ward.enums.TempRangeType;
import com.fuyun.ward.internal.WardDomainEvent;
import com.fuyun.ward.mapper.ColdChainArchiveMapper;
import com.fuyun.ward.mapper.ColdChainRecordMapper;
import com.fuyun.ward.service.IColdChainService;
import com.fuyun.ward.vo.ColdChainArchiveVO;
import com.fuyun.ward.vo.ColdChainRecordVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 冷链合规台账服务单测（P2 PR-2 Task 12 Step 4，TDD 先红后绿）：档案 CRUD、温度区间四类/用途
 * 四值透传、记录三类型校验（ALARM_HANDLE 必填 alarm_ref+second_operator WD-1005）、处置完成
 * 归档事件发布（载荷逐字段核对）、巡检 overdue 读时惰性判定（每日≥2 次、间隔≥6h）。
 *
 * <p>构造范式：MockitoExtension + 构造器注入 mock + @BeforeAll TableInfoHelper.initTableInfo
 * （MP 实体相关，AlarmServiceImplTest 同款）。
 */
@ExtendWith(MockitoExtension.class)
class ColdChainServiceImplTest {

    /** 档案业务号夹具 */
    private static final String ARCHIVE_NO = "ARCH2026092600001";

    /** 记录业务号夹具 */
    private static final String RECORD_NO = "CCR2026092600001";

    /** 操作人夹具（审计留痕口径） */
    private static final String OPERATOR = "pharmacist-01";

    @Mock
    private ColdChainArchiveMapper archiveMapper;

    @Mock
    private ColdChainRecordMapper recordMapper;

    @Mock
    private com.fuyun.ward.cache.WardSeqGate seqGate;

    @Mock
    private ApplicationEventPublisher events;

    private IColdChainService service;

    @BeforeAll
    static void initTableInfo() {
        // MP 实体元数据初始化（selectPage wrapper 断言依赖 TableInfo，iot 单测同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ColdChainArchiveEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ColdChainRecordEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 操作人上下文注入（审计留痕口径）
        com.fuyun.common.context.OperatorContextHolder.set(OPERATOR);
        service = new ColdChainServiceImpl(archiveMapper, recordMapper, seqGate, events);
    }

    @AfterEach
    void tearDown() {
        // 请求结束清理操作人上下文（ThreadLocal 防线程复用泄漏）
        com.fuyun.common.context.OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("建档：签发档案号落库并回读（用途/区间枚举透传，overdue 按新建语义为 true）")
    void createArchiveSignsNoAndPersists() {
        when(seqGate.nextArchiveNo()).thenReturn(ARCHIVE_NO);

        ColdChainArchiveVO vo = service.createArchive(new SaveColdChainArchiveRequest(
                ColdChainPurpose.VACCINE, "dev-fridge-1", TempRangeType.COOL, null, null));

        verify(archiveMapper).insert(archiveCaptor.capture());
        assertThat(archiveCaptor.getValue().getArchiveNo()).isEqualTo(ARCHIVE_NO);
        assertThat(archiveCaptor.getValue().getPurpose()).isEqualTo(ColdChainPurpose.VACCINE);
        assertThat(archiveCaptor.getValue().getTempRangeType()).isEqualTo(TempRangeType.COOL);
        assertThat(vo.archiveNo()).isEqualTo(ARCHIVE_NO);
        assertThat(vo.overdue()).as("新建档案当日尚无巡检，overdue 按合规基线为 true").isTrue();
    }

    @Test
    @DisplayName("温度区间四类与用途四值词表冻结（枚举全集即 DB 词表——V1101 列注释同源）")
    void purposeAndTempRangeVocabulariesAreFrozen() {
        assertThat(ColdChainPurpose.values())
                .extracting(ColdChainPurpose::getCode)
                .containsExactly("VACCINE", "BLOOD", "REAGENT", "PHARMA");
        assertThat(TempRangeType.values())
                .extracting(TempRangeType::getCode)
                .containsExactly("FREEZE", "COOL", "SHELDED", "NORMAL");
        assertThat(ColdChainRecordType.values())
                .extracting(ColdChainRecordType::getCode)
                .containsExactly("INSPECTION", "ALARM_HANDLE", "DEVIATION");
    }

    @Test
    @DisplayName("更新档案：档案不存在拒绝（WD-1004）")
    void updateArchiveRejectsMissingArchive() {
        when(archiveMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateArchive(
                        ARCHIVE_NO,
                        new SaveColdChainArchiveRequest(
                                ColdChainPurpose.BLOOD, "dev-fridge-2", TempRangeType.COOL, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(WardErrorCode.COLD_CHAIN_NOT_FOUND));
    }

    @Test
    @DisplayName("删除档案：逻辑删且存在性前置校验（WD-1004）")
    void deleteArchiveMarksLogicalDelete() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());
        when(archiveMapper.deleteById(1L)).thenReturn(1);

        service.deleteArchive(ARCHIVE_NO);

        verify(archiveMapper).deleteById(1L);
    }

    @Test
    @DisplayName("档案详情：overdue 注记随读时惰性判定出网（当日零巡检即逾期）")
    void getArchiveMarksOverdueWhenNoInspectionToday() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());
        when(recordMapper.selectList(any())).thenReturn(List.of());

        ColdChainArchiveVO vo = service.getArchive(ARCHIVE_NO);

        assertThat(vo.overdue()).as("当日零巡检（每日≥2 次基线不满足）").isTrue();
    }

    @Test
    @DisplayName("档案分页：0 基页码换算与 overdue 批量判定")
    void pageArchivesAppliesPurposeFilterAndOverdue() {
        when(archiveMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<ColdChainArchiveEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(archiveRow()));
            result.setTotal(1);
            return result;
        });
        // 当日两巡检且最近一次 1h 前：合规（overdue=false）
        OffsetDateTime now = OffsetDateTime.now();
        when(recordMapper.selectList(any()))
                .thenReturn(List.of(inspectionAt(now.minusHours(7)), inspectionAt(now.minusHours(1))));

        var result = service.pageArchives(null, 0, 20);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content().get(0).overdue()).as("当日 2 次且最近间隔 1h（<6h）").isFalse();
    }

    @Test
    @DisplayName("巡检 overdue：当日 2 次但最近一次超 6h 判逾期")
    void overdueWhenLastInspectionOlderThanSixHours() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());
        OffsetDateTime now = OffsetDateTime.now();
        when(recordMapper.selectList(any()))
                .thenReturn(List.of(inspectionAt(now.minusHours(7)), inspectionAt(now.minusHours(9))));

        ColdChainArchiveVO vo = service.getArchive(ARCHIVE_NO);

        assertThat(vo.overdue()).as("当日 2 次但最近间隔 >6h（间隔基线不满足）").isTrue();
    }

    @Test
    @DisplayName("登记巡检：正常落库不发归档事件（INSPECTION 类型无事件）")
    void registerInspectionPersistsWithoutEvent() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());
        when(seqGate.nextRecordNo()).thenReturn(RECORD_NO);

        ColdChainRecordVO vo = service.registerRecord(
                ARCHIVE_NO, new RegisterColdChainRecordRequest(ColdChainRecordType.INSPECTION, null, null, null));

        verify(recordMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getRecordNo()).isEqualTo(RECORD_NO);
        assertThat(recordCaptor.getValue().getRecordType()).isEqualTo(ColdChainRecordType.INSPECTION);
        verify(events, never()).publishEvent(any(WardDomainEvent.class));
        assertThat(vo.recordNo()).isEqualTo(RECORD_NO);
    }

    @Test
    @DisplayName("告警处置缺 alarm_ref：WD-1005 拒绝（ALARM_HANDLE 必填）")
    void registerAlarmHandleWithoutAlarmRefRejected() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());

        assertThatThrownBy(() -> service.registerRecord(
                        ARCHIVE_NO,
                        new RegisterColdChainRecordRequest(ColdChainRecordType.ALARM_HANDLE, null, "second-op", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(WardErrorCode.COLD_CHAIN_RECORD_INVALID));
        verify(recordMapper, never()).insert(any(ColdChainRecordEntity.class));
    }

    @Test
    @DisplayName("告警处置缺双人第二人：WD-1005 拒绝（双人核对红线）")
    void registerAlarmHandleWithoutSecondOperatorRejected() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());

        assertThatThrownBy(() -> service.registerRecord(
                        ARCHIVE_NO,
                        new RegisterColdChainRecordRequest(
                                ColdChainRecordType.ALARM_HANDLE, "AL2026092600001", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(WardErrorCode.COLD_CHAIN_RECORD_INVALID));
        verify(recordMapper, never()).insert(any(ColdChainRecordEntity.class));
    }

    @Test
    @DisplayName("告警处置完成：落库并同事务发布归档事件（载荷逐字段核对，id 82 契约）")
    void registerAlarmHandlePublishesArchivedEvent() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());
        when(seqGate.nextRecordNo()).thenReturn(RECORD_NO);

        ColdChainRecordVO vo = service.registerRecord(
                ARCHIVE_NO,
                new RegisterColdChainRecordRequest(
                        ColdChainRecordType.ALARM_HANDLE, "AL2026092600001", "second-op", "{}"));

        verify(recordMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getAlarmRef()).isEqualTo("AL2026092600001");
        assertThat(recordCaptor.getValue().getSecondOperator()).isEqualTo("second-op");
        verify(events).publishEvent(eventCaptor.capture());
        WardDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(WardMessagingConstants.EVENT_COLD_CHAIN_ALERT_ARCHIVED);
        ColdChainAlertArchivedPayload payload = (ColdChainAlertArchivedPayload) event.payload();
        assertThat(payload.archiveNo()).isEqualTo(ARCHIVE_NO);
        assertThat(payload.recordNo()).isEqualTo(RECORD_NO);
        assertThat(payload.alarmRef()).isEqualTo("AL2026092600001");
        assertThat(payload.purpose()).isEqualTo("VACCINE");
        assertThat(payload.handledBy()).isEqualTo(OPERATOR);
        assertThat(payload.handledAt()).isNotNull();
        assertThat(vo.recordNo()).isEqualTo(RECORD_NO);
    }

    @Test
    @DisplayName("登记记录：档案不存在拒绝（WD-1004）")
    void registerRecordRejectsMissingArchive() {
        when(archiveMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.registerRecord(
                        ARCHIVE_NO,
                        new RegisterColdChainRecordRequest(ColdChainRecordType.DEVIATION, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(WardErrorCode.COLD_CHAIN_NOT_FOUND));
    }

    @Test
    @DisplayName("更新档案：属性列维护且 archive_no 不可变，overdue 注记随行")
    void updateArchiveMaintainsAttributesAndKeepsNoImmutable() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());
        OffsetDateTime now = OffsetDateTime.now();
        when(recordMapper.selectList(any()))
                .thenReturn(List.of(inspectionAt(now.minusHours(1)), inspectionAt(now.minusHours(2))));

        ColdChainArchiveVO vo = service.updateArchive(
                ARCHIVE_NO,
                new SaveColdChainArchiveRequest(
                        ColdChainPurpose.BLOOD, "dev-fridge-2", TempRangeType.FREEZE, now.plusDays(90), "盘点摘要"));

        verify(archiveMapper).updateById(archiveCaptor.capture());
        ColdChainArchiveEntity updated = archiveCaptor.getValue();
        assertThat(updated.getArchiveNo()).as("archive_no 不可变").isEqualTo(ARCHIVE_NO);
        assertThat(updated.getPurpose()).isEqualTo(ColdChainPurpose.BLOOD);
        assertThat(updated.getTempRangeType()).isEqualTo(TempRangeType.FREEZE);
        assertThat(vo.overdue()).as("当日 2 次且最近间隔 1h（<6h）合规").isFalse();
    }

    @Test
    @DisplayName("记录列表：档案维度登记时刻倒序出网")
    void listRecordsReturnsArchiveScopedList() {
        when(archiveMapper.selectOne(any())).thenReturn(archiveRow());
        when(recordMapper.selectList(any()))
                .thenReturn(
                        List.of(recordRow(ColdChainRecordType.INSPECTION), recordRow(ColdChainRecordType.DEVIATION)));

        var records = service.listRecords(ARCHIVE_NO);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).archiveNo()).isEqualTo(ARCHIVE_NO);
        assertThat(records.get(1).recordType()).isEqualTo(ColdChainRecordType.DEVIATION);
    }

    @Test
    @DisplayName("记录列表：档案不存在拒绝（WD-1004）")
    void listRecordsRejectsMissingArchive() {
        when(archiveMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.listRecords(ARCHIVE_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(WardErrorCode.COLD_CHAIN_NOT_FOUND));
    }

    @Test
    @DisplayName("档案分页空页：零行不触发巡检聚合查询（overdue 批量判定早退）")
    void pageArchivesWithEmptyPageSkipsOverdueAggregation() {
        when(archiveMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<ColdChainArchiveEntity> result = invocation.getArgument(0);
            result.setRecords(List.of());
            result.setTotal(0);
            return result;
        });

        var result = service.pageArchives(ColdChainPurpose.VACCINE, 0, 20);

        assertThat(result.content()).isEmpty();
        verify(recordMapper, never()).selectList(any());
    }

    /** 档案行夹具（疫苗冷藏档案） */
    private static ColdChainArchiveEntity archiveRow() {
        ColdChainArchiveEntity entity = new ColdChainArchiveEntity();
        entity.setId(1L);
        entity.setArchiveNo(ARCHIVE_NO);
        entity.setPurpose(ColdChainPurpose.VACCINE);
        entity.setDeviceId("dev-fridge-1");
        entity.setTempRangeType(TempRangeType.COOL);
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }

    /** 记录行夹具 */
    private static ColdChainRecordEntity recordRow(ColdChainRecordType type) {
        ColdChainRecordEntity entity = new ColdChainRecordEntity();
        entity.setId(2L);
        entity.setRecordNo(RECORD_NO);
        entity.setArchiveNo(ARCHIVE_NO);
        entity.setRecordType(type);
        entity.setRecordedBy(OPERATOR);
        entity.setRecordedAt(OffsetDateTime.now());
        return entity;
    }

    /** 当日巡检行夹具（overdue 判定窗口锚） */
    private static ColdChainRecordEntity inspectionAt(OffsetDateTime recordedAt) {
        ColdChainRecordEntity entity = new ColdChainRecordEntity();
        entity.setArchiveNo(ARCHIVE_NO);
        entity.setRecordType(ColdChainRecordType.INSPECTION);
        entity.setRecordedAt(recordedAt);
        return entity;
    }

    /** 档案插入参数捕获器（多处 verify 共用） */
    private final ArgumentCaptor<ColdChainArchiveEntity> archiveCaptor =
            ArgumentCaptor.forClass(ColdChainArchiveEntity.class);

    /** 记录插入参数捕获器（多处 verify 共用） */
    private final ArgumentCaptor<ColdChainRecordEntity> recordCaptor =
            ArgumentCaptor.forClass(ColdChainRecordEntity.class);

    /** 应用事件捕获器（归档事件载荷核对） */
    private final ArgumentCaptor<WardDomainEvent> eventCaptor = ArgumentCaptor.forClass(WardDomainEvent.class);
}
