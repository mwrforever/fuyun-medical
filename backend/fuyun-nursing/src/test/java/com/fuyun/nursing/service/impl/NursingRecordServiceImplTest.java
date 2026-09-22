package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.dto.NursingRecordCreateRequest;
import com.fuyun.nursing.dto.NursingRecordReviseRequest;
import com.fuyun.nursing.entity.NursingRecord;
import com.fuyun.nursing.enums.RecordClass;
import com.fuyun.nursing.enums.RecordStatus;
import com.fuyun.nursing.mapper.NursingRecordMapper;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NursingRecordVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 护理记录单域服务单测（Task 4 七用例冻结集）：护理记录创建（发号 + 在区校验 NS-1004）、
 * 提交锁定（GC26 CAS + NS-1007）、修订留痕原值可见（GC25 红线——新行 REVISED + revised_from
 * 链、原行保留零改动）、观察行归集落点（Task 5 消费面——正常合并/异常新建二分支）。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；条件更新断言直读
 * @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class NursingRecordServiceImplTest {

    /** I 型 14 位合法 visit_id（在区校验守卫链通过值） */
    private static final String VISIT = "I2026092200001";

    /** 发号器首号（mock NursingSeqGate 固定返回值） */
    private static final String RECORD_NO = "NR2026092200001";

    /** 修订新行号（mock NursingSeqGate 第二固定值） */
    private static final String REVISED_NO = "NR2026092200002";

    /** DB 服务器时刻替身（M2 时钟源统一断言：修订件 record_time/signed_at 同源同值） */
    private static final OffsetDateTime DB_NOW = OffsetDateTime.of(2026, 9, 22, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private NursingRecordMapper recordMapper;

    @Mock
    private NursingSeqGate seqGate;

    @Mock
    private IWardMetaService wardMetaService;

    @Captor
    private ArgumentCaptor<Wrapper<NursingRecord>> queryCaptor;

    @Captor
    private ArgumentCaptor<NursingRecord> rowCaptor;

    private NursingRecordServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（护理记录单读面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingRecord.class);
    }

    @BeforeEach
    void setUp() {
        service = new NursingRecordServiceImpl(recordMapper, seqGate, wardMetaService);
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("创建护理记录：NursingSeqGate 发号 NR 前缀单号回填 VO，status=DRAFT、记录时间服务器时间")
    void createGeneratesRecordNoWithPrefixAndSequence() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("NR")).thenReturn(RECORD_NO);
        when(recordMapper.insert(any(NursingRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, NursingRecord.class).setId(1L);
            return 1;
        });

        NursingRecordVO vo =
                service.create(new NursingRecordCreateRequest(VISIT, null, "病情平稳", "持续心电监护", "生命体征正常", null));

        assertThat(vo.recordNo()).isEqualTo(RECORD_NO);
        assertThat(vo.status()).isEqualTo(RecordStatus.DRAFT.getCode());
        verify(recordMapper).insert(rowCaptor.capture());
        // 在区行归一：patient_id/ward_id 由 IWardMetaService 在区行服务端装配（不信客户端）
        assertThat(rowCaptor.getValue().getVisitId()).isEqualTo(VISIT);
        assertThat(rowCaptor.getValue().getPatientId()).isEqualTo(7L);
        assertThat(rowCaptor.getValue().getWardId()).isEqualTo("W01");
        assertThat(rowCaptor.getValue().getRecordClass()).isEqualTo(RecordClass.GENERAL.getCode());
        assertThat(rowCaptor.getValue().getAutoGenerated()).isFalse();
        // GC25 护理文书红线：记录时间一律服务器时间（服务端落 now，非客户端传入）
        assertThat(rowCaptor.getValue().getRecordTime()).isNotNull();
    }

    @Test
    @DisplayName("创建护理记录：IWardMetaService 查无在区行拒 NS-1004（患者不在区），不发号不落库")
    void createRejectsUnknownVisit() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(
                        NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + VISIT));

        assertThatThrownBy(() -> service.create(new NursingRecordCreateRequest(VISIT, null, "病情观察", null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PATIENT_BLOCKED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1004");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(seqGate, recordMapper);
    }

    @Test
    @DisplayName("提交锁定：CAS 置 SUBMITTED 并盖签名（操作者=当前上下文），重复提交 CAS 0 行拒 NS-1007")
    void submitLocksRecordAndStampsOperatorAndTime() {
        when(recordMapper.casSubmit(RECORD_NO, "nurse-01")).thenReturn(1, 0);
        when(recordMapper.selectOne(any())).thenReturn(submittedRow());

        NursingRecordVO vo = service.submit(RECORD_NO);

        assertThat(vo.status()).isEqualTo(RecordStatus.SUBMITTED.getCode());
        assertThat(vo.signedOperator()).isEqualTo("nurse-01");
        assertThat(vo.signedAt()).isNotNull();
        // GC26 可执行锚：提交锁定必须为 @Update 注解 SQL 条件更新（DRAFT→SUBMITTED 单向 + deleted=0）
        String sql = recordSql("casSubmit", String.class, String.class);
        assertThat(sql)
                .contains("status = 'SUBMITTED'")
                .contains("signed_operator = #{signedOperator}")
                .contains("signed_at = now()")
                .contains("WHERE record_no = #{recordNo}")
                .contains("status = 'DRAFT'")
                .contains("deleted = 0");

        // 重复提交：CAS 命中 0 行（status 已非 DRAFT）→ NS-1007 已提交锁定
        assertThatThrownBy(() -> service.submit(RECORD_NO)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.RECORD_LOCKED);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1007");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
    }

    @Test
    @DisplayName("修订留痕：新行新号 REVISED + revised_from 指向原号；时钟源与提交 CAS 同源 DB now()；原行零改动仍可 get 回原值（GC25）")
    void reviseCreatesNewRowKeepingOriginal() {
        when(recordMapper.selectOne(any())).thenReturn(submittedRow());
        when(seqGate.nextNo("NR")).thenReturn(REVISED_NO);
        when(recordMapper.dbNow()).thenReturn(DB_NOW);
        when(recordMapper.insert(any(NursingRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, NursingRecord.class).setId(2L);
            return 1;
        });

        NursingRecordVO vo = service.revise(RECORD_NO, new NursingRecordReviseRequest("修订后观察", "修订后措施", "修订后评价", null));

        verify(recordMapper).insert(rowCaptor.capture());
        NursingRecord revised = rowCaptor.getValue();
        assertThat(revised.getRecordNo()).isEqualTo(REVISED_NO);
        assertThat(revised.getRevisedFrom()).isEqualTo(RECORD_NO);
        assertThat(revised.getStatus()).isEqualTo(RecordStatus.REVISED.getCode());
        assertThat(revised.getObservation()).isEqualTo("修订后观察");
        // M2 时钟源统一：record_time/signed_at 与提交 CAS 同源 DB now()（防签名链时序倒挂）
        assertThat(revised.getRecordTime()).isEqualTo(DB_NOW);
        assertThat(revised.getSignedAt()).isEqualTo(DB_NOW);
        // 修订件继承原行归属（visit/patient/ward/记录类别不动）
        assertThat(revised.getVisitId()).isEqualTo(VISIT);
        assertThat(revised.getPatientId()).isEqualTo(7L);
        assertThat(revised.getRecordClass()).isEqualTo(RecordClass.GENERAL.getCode());
        // 原值可见靠历史行不删：原行零改动（无 updateById/无 update），回读仍为原正文
        verify(recordMapper, never()).updateById(any(NursingRecord.class));
        verify(recordMapper, never()).update(any(Wrapper.class));
        NursingRecordVO original = service.get(RECORD_NO);
        assertThat(original.observation()).isEqualTo("原观察内容");
        assertThat(original.revisedFrom()).isNull();
        assertThat(original.status()).isEqualTo(RecordStatus.SUBMITTED.getCode());
    }

    @Test
    @DisplayName("修订链合法性：DRAFT 草稿行发起修订拒 NS-1008（仅 SUBMITTED 可修订），不插新行")
    void reviseRejectsDraftRecord() {
        when(recordMapper.selectOne(any())).thenReturn(draftRow());

        assertThatThrownBy(() -> service.revise(RECORD_NO, new NursingRecordReviseRequest("x", null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.RECORD_REVISE_NOT_ALLOWED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1008");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(recordMapper, never()).insert(any(NursingRecord.class));
        verifyNoInteractions(seqGate);
    }

    @Test
    @DisplayName("观察行归集（Task 5 消费面）：正常范围合并当日既有正常观察行（条件更新 1 次，不新建行）")
    void appendObservationMergesSameDayNormalRow() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        NursingRecord existing = autoObservationRow();
        when(recordMapper.selectOne(any())).thenReturn(existing);
        when(recordMapper.appendObservation(eq(9L), eq("脉搏 80 次/分 正常"), eq("nurse-01")))
                .thenReturn(1);

        service.appendObservation(VISIT, "脉搏 80 次/分 正常", false, "nurse-01");

        // 合并分支钉死：仅条件更新追加既有行，insert 未被调
        verify(recordMapper).appendObservation(eq(9L), eq("脉搏 80 次/分 正常"), eq("nurse-01"));
        verify(recordMapper, never()).insert(any(NursingRecord.class));
        // 合并谓词钉死：归集定位落在 visit_id + auto_generated + abnormal_flag=false + 当日窗口
        // （I1：abnormal_flag=false 限定正常行——异常行不被稀释、正常/异常并存时单行命中）
        verify(recordMapper).selectOne(queryCaptor.capture());
        LambdaQueryWrapper<NursingRecord> wrapper = rendered(queryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT, true, false);
        assertThat(wrapper.getSqlSegment()).contains("record_time >=").contains("record_time <");
    }

    @Test
    @DisplayName("观察行归集：异常项新建独立行（autoGenerated=true、abnormal_flag=true、内容含异常描述），不触碰既有行")
    void appendObservationCreatesRowWhenAbnormal() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(recordMapper.insert(any(NursingRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, NursingRecord.class).setId(3L);
            return 1;
        });

        service.appendObservation(VISIT, "体温 38.6℃ 高于正常范围 36.0-37.2℃", true, "nurse-01");

        verify(recordMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getAutoGenerated()).isTrue();
        // I1：异常行置 abnormal_flag=true，后续正常归集合并谓词不再命中该行
        assertThat(rowCaptor.getValue().getAbnormalFlag()).isTrue();
        assertThat(rowCaptor.getValue().getObservation()).contains("38.6");
        assertThat(rowCaptor.getValue().getRecordClass()).isEqualTo(RecordClass.GENERAL.getCode());
        assertThat(rowCaptor.getValue().getRecordTime()).isNotNull();
        // 异常行独立落行：不查询合并行、不条件更新（既有观察行零改动）
        verify(recordMapper, never()).selectOne(any());
        verify(recordMapper, never()).appendObservation(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("观察行归集（I1 第四态）：同日已有异常行后正常归集——正常内容独立落新行（abnormal_flag=false），不进异常行")
    void appendObservationKeepsNormalContentOutOfAbnormalRow() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        // 真实 DB 上合并谓词（abnormal_flag=false）会滤掉异常行 → 查询结果为 null
        when(recordMapper.selectOne(any())).thenReturn(null);
        when(seqGate.nextNo("NR")).thenReturn(REVISED_NO);
        when(recordMapper.insert(any(NursingRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, NursingRecord.class).setId(6L);
            return 1;
        });

        service.appendObservation(VISIT, "脉搏 82 次/分 正常", false, "nurse-01");

        // 正常内容独立落行且标记为正常行（GC19 异常不被稀释的可执行锚）
        verify(recordMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getAbnormalFlag()).isFalse();
        assertThat(rowCaptor.getValue().getAutoGenerated()).isTrue();
        assertThat(rowCaptor.getValue().getObservation()).isEqualTo("脉搏 82 次/分 正常");
        // 不对异常行做任何条件更新
        verify(recordMapper, never()).appendObservation(anyLong(), anyString(), anyString());
        // 合并谓词含 abnormal_flag=false（异常行被排除的根因锚）
        verify(recordMapper).selectOne(queryCaptor.capture());
        LambdaQueryWrapper<NursingRecord> wrapper = rendered(queryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT, true, false);
    }

    @Test
    @DisplayName("观察行归集（I1 第四态）：同日正常行+异常行并存时再次正常归集——单行命中合并进正常行，不抛 TooManyResults")
    void appendObservationMergesSingleNormalRowWhenAbnormalRowAlsoExists() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        // 真实 DB 上 abnormal_flag=false 谓词仅命中正常行（并存异常行被滤除）→ 单行返回
        when(recordMapper.selectOne(any())).thenReturn(autoObservationRow());
        when(recordMapper.appendObservation(eq(9L), eq("血压 120/80 mmHg 正常"), eq("nurse-01")))
                .thenReturn(1);

        service.appendObservation(VISIT, "血压 120/80 mmHg 正常", false, "nurse-01");

        // 合并进正常行（id=9），全程无异常、不新建
        verify(recordMapper).appendObservation(eq(9L), eq("血压 120/80 mmHg 正常"), eq("nurse-01"));
        verify(recordMapper, never()).insert(any(NursingRecord.class));
    }

    @Test
    @DisplayName("观察行归集（I3）：合并条件更新命中 0 行（行被并发删改）→ 落入新建分支，归集内容不静默丢失（GC26）")
    void appendObservationFallsToNewRowWhenMergeUpdatesZeroRows() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(recordMapper.selectOne(any())).thenReturn(autoObservationRow());
        when(recordMapper.appendObservation(eq(9L), eq("脉搏 78 次/分 正常"), eq("nurse-01")))
                .thenReturn(0);
        when(seqGate.nextNo("NR")).thenReturn(REVISED_NO);
        when(recordMapper.insert(any(NursingRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, NursingRecord.class).setId(7L);
            return 1;
        });

        service.appendObservation(VISIT, "脉搏 78 次/分 正常", false, "nurse-01");

        // 0 行不静默：条件更新尝试 1 次，内容改由新建独立行承载
        verify(recordMapper).appendObservation(eq(9L), eq("脉搏 78 次/分 正常"), eq("nurse-01"));
        verify(recordMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getAbnormalFlag()).isFalse();
        assertThat(rowCaptor.getValue().getAutoGenerated()).isTrue();
        assertThat(rowCaptor.getValue().getObservation()).isEqualTo("脉搏 78 次/分 正常");
    }

    @Test
    @DisplayName("创建护理记录：记录类别 code 非法显式拒 NS-1019（W-22⑦ 禁裸 parse 先例），不发号不落库")
    void createRejectsUnknownRecordClass() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());

        assertThatThrownBy(
                        () -> service.create(new NursingRecordCreateRequest(VISIT, "URGENT", null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        verify(recordMapper, never()).insert(any(NursingRecord.class));
        verifyNoInteractions(seqGate);
    }

    @Test
    @DisplayName("护理记录清单：date 非空收敛当日窗口、空取全部，统一按记录时间升序（DB 侧排序钉死）")
    void listByVisitSupportsDayWindowAndOrdersByRecordTime() {
        when(recordMapper.selectList(any())).thenReturn(List.of(submittedRow()));

        List<NursingRecordVO> dated = service.listByVisit(VISIT, LocalDate.of(2026, 9, 22));
        List<NursingRecordVO> all = service.listByVisit(VISIT, null);

        assertThat(dated).hasSize(1);
        assertThat(all).hasSize(1);
        verify(recordMapper, times(2)).selectList(queryCaptor.capture());
        LambdaQueryWrapper<NursingRecord> dayWrapper =
                rendered(queryCaptor.getAllValues().get(0));
        assertThat(dayWrapper.getParamNameValuePairs().values()).contains(VISIT);
        assertThat(dayWrapper.getSqlSegment())
                .contains("record_time >=")
                .contains("record_time <")
                .contains("ORDER BY")
                .contains("record_time");
        LambdaQueryWrapper<NursingRecord> allWrapper =
                rendered(queryCaptor.getAllValues().get(1));
        assertThat(allWrapper.getSqlSegment())
                .doesNotContain("record_time >=")
                .contains("ORDER BY")
                .contains("record_time");
    }

    @Test
    @DisplayName("创建护理记录：病区服务其他业务异常原样透传（仅在区缺失才翻译为 NS-1004）")
    void createPropagatesUnrelatedWardBizException() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "其他校验失败"));

        assertThatThrownBy(() -> service.create(new NursingRecordCreateRequest(VISIT, null, null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
    }

    @Test
    @DisplayName("护理记录详情：记录号不存在拒 NS-1016（未知资源语义位，与病区配置先例同口径）")
    void getRejectsMissingRecord() {
        when(recordMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get(RECORD_NO)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
        });
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 在区详情卡替身（W01/床 01/患者 7；IWardMetaService.detail 的归一数据源）。 */
    private WardPatientDetailVO detailVO() {
        return new WardPatientDetailVO(
                "W01",
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

    /** 已提交锁定行构造（SUBMITTED + 签名留痕，修订/提交用例载体）。 */
    private NursingRecord submittedRow() {
        NursingRecord row = new NursingRecord();
        row.setId(1L);
        row.setRecordNo(RECORD_NO);
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        row.setWardId("W01");
        row.setRecordClass(RecordClass.GENERAL.getCode());
        row.setRecordTime(OffsetDateTime.now());
        row.setObservation("原观察内容");
        row.setMeasures("原措施");
        row.setAutoGenerated(false);
        row.setStatus(RecordStatus.SUBMITTED.getCode());
        row.setSignedOperator("nurse-01");
        row.setSignedAt(OffsetDateTime.now());
        return row;
    }

    /** 草稿行构造（DRAFT，修订链非法性用例载体）。 */
    private NursingRecord draftRow() {
        NursingRecord row = new NursingRecord();
        row.setId(4L);
        row.setRecordNo(RECORD_NO);
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        row.setWardId("W01");
        row.setRecordClass(RecordClass.GENERAL.getCode());
        row.setRecordTime(OffsetDateTime.now());
        row.setObservation("草稿观察");
        row.setAutoGenerated(false);
        row.setStatus(RecordStatus.DRAFT.getCode());
        return row;
    }

    /** 当日自动归集观察行构造（autoGenerated=true、正常范围内容，合并分支载体）。 */
    private NursingRecord autoObservationRow() {
        NursingRecord row = new NursingRecord();
        row.setId(9L);
        row.setRecordNo("NR2026092200009");
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        row.setWardId("W01");
        row.setRecordClass(RecordClass.GENERAL.getCode());
        row.setRecordTime(OffsetDateTime.now());
        row.setObservation("体温 36.5℃ 正常");
        row.setAutoGenerated(true);
        row.setStatus(RecordStatus.DRAFT.getCode());
        return row;
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<NursingRecord> rendered(Wrapper<NursingRecord> captured) {
        LambdaQueryWrapper<NursingRecord> wrapper = (LambdaQueryWrapper<NursingRecord>) captured;
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
                    NursingRecordMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
    }
}
