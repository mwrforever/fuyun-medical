package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.fuyun.nursing.dto.IoRecordCreateRequest;
import com.fuyun.nursing.dto.IoSummaryCreateRequest;
import com.fuyun.nursing.entity.IoRecord;
import com.fuyun.nursing.entity.IoSummary;
import com.fuyun.nursing.entity.TemperatureChartEntry;
import com.fuyun.nursing.mapper.IoRecordMapper;
import com.fuyun.nursing.mapper.IoSummaryMapper;
import com.fuyun.nursing.mapper.TemperatureChartEntryMapper;
import com.fuyun.nursing.service.ITemperatureChartService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.IoRecordVO;
import com.fuyun.nursing.vo.IoSummaryVO;
import com.fuyun.nursing.vo.WardConfigVO;
import com.fuyun.nursing.vo.WardConfigVO.ShiftDefinition;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 出入量域服务单测（Task 6 冻结八用例 + 守卫分支补充锚）：数量两位小数规整与 D-18 string
 * 出参、词表外/预留源拒收（NS-1019）、班次窗口聚合（含 24:00 结束与跨零点班次窗）、24h 全天
 * 窗、体温单 DAILY_VALUE 条目写入与引用回填、同周期幂等（唯一冲突 NS-1016 不覆盖首值）、
 * 明细按日过滤升序。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class IoRecordServiceImplTest {

    /** I 型 14 位合法 visit_id（在区校验守卫链通过值） */
    private static final String VISIT = "I2026092200001";

    /** 病区编码（在区行归一数据源，班次定义读取键） */
    private static final String WARD = "W01";

    /** 首条插入明细行固定 id（insert 桩回填值） */
    private static final long ROW_ID = 601L;

    /** 小结行固定 id（insert 桩回填值/幂等命中行 id） */
    private static final long SUMMARY_ID = 801L;

    /** 体温单月页固定 id（ensurePage 桩回填值） */
    private static final long PAGE_ID = 501L;

    /** 体温单 DAILY_VALUE 条目固定 id（条目定位桩回填值，引用回填断言基准） */
    private static final long ENTRY_ID = 901L;

    /** 服务器时区（窗口边界断言与实现同源） */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Mock
    private IoRecordMapper recordMapper;

    @Mock
    private IoSummaryMapper summaryMapper;

    @Mock
    private TemperatureChartEntryMapper entryMapper;

    @Mock
    private IWardMetaService wardMetaService;

    @Mock
    private ITemperatureChartService chartService;

    @Captor
    private ArgumentCaptor<IoRecord> rowCaptor;

    @Captor
    private ArgumentCaptor<IoSummary> summaryCaptor;

    @Captor
    private ArgumentCaptor<OffsetDateTime> fromCaptor;

    @Captor
    private ArgumentCaptor<OffsetDateTime> toCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<IoRecord>> queryCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<IoSummary>> summaryQueryCaptor;

    private IoRecordServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（明细/小结/条目三读面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IoRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IoSummary.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TemperatureChartEntry.class);
    }

    @BeforeEach
    void setUp() {
        service = new IoRecordServiceImpl(recordMapper, summaryMapper, entryMapper, wardMetaService, chartService);
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("明细录入：quantity=\"1200.50\" 落库 NUMERIC(10,2) 两位小数规整，VO 回读字符串 \"1200.50\"（D-18）")
    void createStoresQuantityAsScaledDecimal() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(recordMapper.insert(any(IoRecord.class))).thenAnswer(insertWithId(ROW_ID));

        IoRecordVO vo =
                service.create(new IoRecordCreateRequest(VISIT, "INTAKE", "IV_FLUID", "1200.50", null, null, null));

        // D-18 口径：数量出参 string 承载，两位小数原样回读
        assertThat(vo.quantity()).isEqualTo("1200.50");
        verify(recordMapper).insert(rowCaptor.capture());
        IoRecord row = rowCaptor.getValue();
        assertThat(row.getQuantity()).isEqualByComparingTo("1200.50");
        // NUMERIC(10,2) 规整锚：标度恒为 2
        assertThat(row.getQuantity().scale()).isEqualTo(2);
        // patient/ward 由在区行服务端装配（不信客户端）；itemName 服务端词表冗余
        assertThat(row.getPatientId()).isEqualTo(7L);
        assertThat(row.getWardId()).isEqualTo(WARD);
        assertThat(row.getItemName()).isEqualTo("静脉输液");
        assertThat(row.getIoType()).isEqualTo("INTAKE");
        assertThat(row.getItemCode()).isEqualTo("IV_FLUID");
        // GC25：发生时间一律服务器时间；缺省单位 ml、缺省数据源 MANUAL
        assertThat(row.getOccurAt()).isNotNull();
        assertThat(row.getUnit()).isEqualTo("ml");
        assertThat(row.getSource()).isEqualTo("MANUAL");
        assertThat(row.getRecorderId()).isEqualTo("nurse-01");
        // P1 手工行：班次未归属、来源单据引用无写入方
        assertThat(row.getShiftCode()).isNull();
        assertThat(row.getSourceRef()).isNull();
    }

    @Test
    @DisplayName("明细录入：source=\"SENSOR\" 词表外拒 NS-1019，守卫链第一步、在区校验与落库未触达")
    void createRejectsUnknownSource() {
        assertThatThrownBy(() -> service.create(
                        new IoRecordCreateRequest(VISIT, "INTAKE", "IV_FLUID", "1200.50", null, "SENSOR", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(recordMapper, summaryMapper, entryMapper, wardMetaService, chartService);
    }

    @Test
    @DisplayName("班次小结：3 条入量+2 条出量聚合 → totalIntake=1800.00、totalOutput=950.00、balance=850.00（两位小数）")
    void summarizeShiftAggregatesIntakeAndOutputOfPeriod() {
        stubSummaryChain();
        stubWardConfig();
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any()))
                .thenReturn(List.of(typeSum("INTAKE", "1800.00"), typeSum("OUTPUT", "950.00")));

        IoSummaryVO vo = service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY"));

        // D-18：小结三数量 string 出参且断言到两位小数；balance = 总入量 - 总出量
        assertThat(vo.totalIntake()).isEqualTo("1800.00");
        assertThat(vo.totalOutput()).isEqualTo("950.00");
        assertThat(vo.balance()).isEqualTo("850.00");
        assertThat(vo.summaryType()).isEqualTo("SHIFT");
        assertThat(vo.shiftCode()).isEqualTo("DAY");
        assertThat(vo.visitId()).isEqualTo(VISIT);
    }

    @Test
    @DisplayName("班次小结窗口：聚合窗口=病区 DAY 班定义当日窗 [08:00, 16:00)，他班次行因窗口谓词不计入")
    void summarizeShiftExcludesOtherShiftRows() {
        stubSummaryChain();
        stubWardConfig();
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any()))
                .thenReturn(List.of(typeSum("INTAKE", "1500.00")));

        service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY"));

        // 排他性根因锚：窗口含头不含尾 [班次起, 班次止)，EVENING/NIGHT 行落在窗外
        verify(recordMapper).sumByTypeAndPeriod(eq(VISIT), fromCaptor.capture(), toCaptor.capture());
        LocalDate today = LocalDate.now();
        assertThat(fromCaptor.getValue())
                .isEqualTo(today.atTime(8, 0).atZone(ZONE).toOffsetDateTime());
        assertThat(toCaptor.getValue())
                .isEqualTo(today.atTime(16, 0).atZone(ZONE).toOffsetDateTime());
    }

    @Test
    @DisplayName("24h 总结窗口：当日 00:00–24:00 全天求和（[当日 00:00, 次日 00:00)），前一日 23:00 行因 occur_at >= from 不计入")
    void summarize24HCoversFullDay() {
        stubSummaryChain();
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any()))
                .thenReturn(List.of(typeSum("INTAKE", "2000.00"), typeSum("OUTPUT", "1500.00")));

        IoSummaryVO vo = service.summarize(new IoSummaryCreateRequest(VISIT, "24H", null));

        verify(recordMapper).sumByTypeAndPeriod(eq(VISIT), fromCaptor.capture(), toCaptor.capture());
        LocalDate today = LocalDate.now();
        assertThat(fromCaptor.getValue()).isEqualTo(today.atStartOfDay(ZONE).toOffsetDateTime());
        assertThat(toCaptor.getValue())
                .isEqualTo(today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        assertThat(vo.balance()).isEqualTo("500.00");
        verify(summaryMapper).insert(summaryCaptor.capture());
        // 24H 行班次恒空（生成列 shift_key 落空串键，幂等键不含班次维度）
        assertThat(summaryCaptor.getValue().getShiftCode()).isNull();
    }

    @Test
    @DisplayName(
            "小结回写体温单：appendDailyValue 被调 1 次（dailyValueType=IO_SUMMARY_SHIFT、值文本「入 X / 出 Y / 平衡 Z」），条目引用回填 chart_entry_ref")
    void summarizeWritesChartDailyValueEntry() {
        stubSummaryChain();
        stubWardConfig();
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any()))
                .thenReturn(List.of(typeSum("INTAKE", "1800.00"), typeSum("OUTPUT", "950.00")));

        service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY"));

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(chartService, times(1))
                .appendDailyValue(eq(VISIT), typeCaptor.capture(), textCaptor.capture(), eq("nurse-01"));
        // V802 daily_value_type 词表冻结值 + 简报冻结值文本形态（红双线由前端渲染）
        assertThat(typeCaptor.getValue()).isEqualTo("IO_SUMMARY_SHIFT");
        assertThat(textCaptor.getValue()).isEqualTo("入 1800.00 / 出 950.00 / 平衡 850.00");
        // 同事务回填：小结行携带体温单条目引用
        verify(summaryMapper).updateById(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getChartEntryRef()).isEqualTo(ENTRY_ID);
    }

    @Test
    @DisplayName("小结幂等：同 (visit_id, summary_type, period_start, shift_code) 二次汇总返回既有行，聚合/落库/条目/回填均不重复")
    void summarizeIsIdempotentOnSamePeriod() {
        stubSummaryChain();
        stubWardConfig();
        when(summaryMapper.selectOne(any())).thenReturn(null, existingSummary());
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any()))
                .thenReturn(List.of(typeSum("INTAKE", "1800.00"), typeSum("OUTPUT", "950.00")));

        IoSummaryVO first = service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY"));
        IoSummaryVO second = service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY"));

        assertThat(second.id()).isEqualTo(first.id()).isEqualTo(SUMMARY_ID);
        // 首值权威：二次命中幂等检查直接返回，全程零重复写入（聚合/insert/条目/回填各仅首次一次）
        verify(recordMapper, times(1)).sumByTypeAndPeriod(anyString(), any(), any());
        verify(summaryMapper, times(1)).insert(any(IoSummary.class));
        verify(chartService, times(1)).appendDailyValue(anyString(), anyString(), anyString(), anyString());
        verify(summaryMapper, times(1)).updateById(any(IoSummary.class));
    }

    @Test
    @DisplayName("明细按日清单：date 非空收敛当日窗口 [00:00, 次日 00:00) 谓词，跨日数据仅返回目标日且按发生时间升序")
    void listByVisitFiltersByDateOrderedByOccurAt() {
        LocalDate date = LocalDate.of(2026, 9, 22);
        OffsetDateTime dayStart = date.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime t0810 = date.atTime(8, 10).atZone(ZONE).toOffsetDateTime();
        OffsetDateTime t0930 = date.atTime(9, 30).atZone(ZONE).toOffsetDateTime();
        // 桩即 DB 谓词后的结果：仅目标日两行（跨日行被 occur_at 窗口滤除），发生时间升序
        when(recordMapper.selectList(any())).thenReturn(List.of(ioRow(1L, t0810), ioRow(2L, t0930)));

        List<IoRecordVO> vos = service.listByVisit(VISIT, date);

        assertThat(vos).extracting(IoRecordVO::id).containsExactly(1L, 2L);
        assertThat(vos).extracting(IoRecordVO::occurAt).isSorted();
        assertThat(vos.get(0).quantity()).isEqualTo("100.00");
        // 谓词根因锚：visit_id 过滤 + 当日窗口含头不含尾 + 发生时间升序（DB 侧排序钉死）
        verify(recordMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<IoRecord> wrapper = rendered(queryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT, dayStart, dayStart.plusDays(1));
        assertThat(wrapper.getSqlSegment())
                .contains("occur_at >=")
                .contains("occur_at <")
                .contains("ORDER BY")
                .contains("occur_at");
    }

    // ===================== 守卫分支与窗口形态补充锚（JaCoCo 1.00 分支覆盖） =====================

    @Test
    @DisplayName("明细录入：ioType 词表外、itemCode 词表外、项目与类型不一致均拒 NS-1019，不落库")
    void createRejectsUnknownIoTypeOrMismatchedItem() {
        IoRecordCreateRequest badType = new IoRecordCreateRequest(VISIT, "FLUID", "IV_FLUID", "100", null, null, null);
        IoRecordCreateRequest badItem = new IoRecordCreateRequest(VISIT, "INTAKE", "DRINK", "100", null, null, null);
        // 出量项目配入量类型：项目与类型一致性违例
        IoRecordCreateRequest mismatched = new IoRecordCreateRequest(VISIT, "INTAKE", "URINE", "100", null, null, null);

        assertThatThrownBy(() -> service.create(badType)).isInstanceOfSatisfying(BizException.class, e -> assertThat(
                        e.getErrorCode().getCode())
                .isEqualTo("NS-1019"));
        assertThatThrownBy(() -> service.create(badItem)).isInstanceOfSatisfying(BizException.class, e -> assertThat(
                        e.getErrorCode().getCode())
                .isEqualTo("NS-1019"));
        assertThatThrownBy(() -> service.create(mismatched))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getMessage()).contains("不一致"));
        verifyNoInteractions(recordMapper, wardMetaService, chartService);
    }

    @Test
    @DisplayName("明细录入：数量空白/非数字文本/零/负数均拒 NS-1019（含「数量」文案），不落库")
    void createRejectsInvalidQuantity() {
        IoRecordCreateRequest blank = new IoRecordCreateRequest(VISIT, "INTAKE", "IV_FLUID", "  ", null, null, null);
        IoRecordCreateRequest notNumeric =
                new IoRecordCreateRequest(VISIT, "INTAKE", "IV_FLUID", "abc", null, null, null);
        IoRecordCreateRequest zero = new IoRecordCreateRequest(VISIT, "OUTPUT", "URINE", "0", null, null, null);
        IoRecordCreateRequest negative = new IoRecordCreateRequest(VISIT, "OUTPUT", "URINE", "-5", null, null, null);

        assertThatThrownBy(() -> service.create(blank)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
            assertThat(e.getMessage()).contains("数量").contains("不能为空");
        });
        assertThatThrownBy(() -> service.create(notNumeric)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
            assertThat(e.getMessage()).contains("数量");
        });
        assertThatThrownBy(() -> service.create(zero))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getMessage()).contains("正数"));
        assertThatThrownBy(() -> service.create(negative))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getMessage()).contains("正数"));
        verifyNoInteractions(recordMapper, wardMetaService, chartService);
    }

    @Test
    @DisplayName("明细录入：P1 预留源（INFUSION_AUTO 等 P2/P4 写入方）显式拒 NS-1019（消息含「P2」），防落卡后悬置")
    void createRejectsReservedAutoSourceInP1() {
        assertThatThrownBy(() -> service.create(
                        new IoRecordCreateRequest(VISIT, "INTAKE", "IV_FLUID", "100", null, "INFUSION_AUTO", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                    assertThat(e.getMessage()).contains("P2");
                });
        verifyNoInteractions(recordMapper, wardMetaService, chartService);
    }

    @Test
    @DisplayName("明细录入：在区校验查无在区行拒 NS-1004（患者不在区），不落库")
    void createRejectsNotInWardVisit() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(
                        NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + VISIT));

        assertThatThrownBy(() ->
                        service.create(new IoRecordCreateRequest(VISIT, "INTAKE", "IV_FLUID", "100", null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PATIENT_BLOCKED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1004");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(recordMapper, never()).insert(any(IoRecord.class));
        verifyNoInteractions(chartService, summaryMapper);
    }

    @Test
    @DisplayName("明细录入：病区服务其他业务异常原样透传（仅在区缺失才翻译为 NS-1004）")
    void createPropagatesUnrelatedWardBizException() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "其他校验失败"));

        assertThatThrownBy(() ->
                        service.create(new IoRecordCreateRequest(VISIT, "INTAKE", "IV_FLUID", "100", null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verify(recordMapper, never()).insert(any(IoRecord.class));
    }

    @Test
    @DisplayName("小结入参：summaryType 词表外、SHIFT 缺班次 code 均拒 NS-1019，在区校验与聚合未触达")
    void summarizeRejectsUnknownTypeOrMissingShift() {
        assertThatThrownBy(() -> service.summarize(new IoSummaryCreateRequest(VISIT, "WEEK", null)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019"));
        assertThatThrownBy(() -> service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", " ")))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getMessage()).contains("班次"));
        verifyNoInteractions(recordMapper, summaryMapper, entryMapper, wardMetaService, chartService);
    }

    @Test
    @DisplayName("小结入参：SHIFT 班次不在病区班次定义内拒 NS-1019，聚合与落库未触达")
    void summarizeRejectsShiftOutsideWardDefinition() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfigVO());

        assertThatThrownBy(() -> service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "MID")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                    assertThat(e.getMessage()).contains("班次");
                });
        verify(recordMapper, never()).sumByTypeAndPeriod(anyString(), any(), any());
        verifyNoInteractions(summaryMapper, chartService);
    }

    @Test
    @DisplayName("小结入参：病区无班次定义（shifts 空）时 SHIFT 拒 NS-1019，聚合与落库未触达")
    void summarizeRejectsShiftWhenDefinitionsMissing() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(wardMetaService.wardConfig(WARD)).thenReturn(new WardConfigVO(WARD, Map.of("NORMAL", 480), false, null));

        assertThatThrownBy(() -> service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                    assertThat(e.getMessage()).contains("班次");
                });
        verify(recordMapper, never()).sumByTypeAndPeriod(anyString(), any(), any());
        verifyNoInteractions(summaryMapper, chartService);
    }

    @Test
    @DisplayName("小结周期推导：病区班次定义时刻文本损坏定性服务端配置异常（IllegalStateException，非入参错误）")
    void summarizeFailsOnMalformedShiftDefinition() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(wardMetaService.wardConfig(WARD))
                .thenReturn(new WardConfigVO(
                        WARD,
                        Map.of("NORMAL", 480),
                        false,
                        List.of(new ShiftDefinition("BAD", "坏班", "xx:yy", "08:00"))));

        assertThatThrownBy(() -> service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "BAD")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("班次时刻解析失败");
        verifyNoInteractions(summaryMapper, chartService);
    }

    @Test
    @DisplayName("24h 总结：客户端误携 shiftCode 被强制置空（幂等检查走 shift_code IS NULL 谓词）")
    void summarize24HNormalizesStrayShiftCodeToNull() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any())).thenReturn(List.of());
        when(chartService.ensurePage(eq(VISIT), any())).thenReturn(PAGE_ID);
        when(entryMapper.selectOne(any())).thenReturn(chartEntry());
        when(summaryMapper.insert(any(IoSummary.class))).thenAnswer(insertSummaryWithId(SUMMARY_ID));

        service.summarize(new IoSummaryCreateRequest(VISIT, "24H", "DAY"));

        // 幂等前置检查谓词锚：24H 以 shift_code IS NULL 命中空串生成键（shift_key=''）
        verify(summaryMapper).selectOne(summaryQueryCaptor.capture());
        LambdaQueryWrapper<IoSummary> wrapper = renderedSummary(summaryQueryCaptor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("shift_code IS NULL");
        verify(summaryMapper).insert(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getShiftCode()).isNull();
    }

    @Test
    @DisplayName("并发同周期小结：前置查无命中而唯一索引冲突 → NS-1016 幂等拒绝（不覆盖首值），条目写入未触达")
    void summarizeConcurrentPeriodConflictRejected() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfigVO());
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(summaryMapper.insert(any(IoSummary.class))).thenThrow(new DuplicateKeyException("uk_io_summary_period"));

        assertThatThrownBy(() -> service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // PG 同事务重查不可达：冲突即拒绝，不回查不覆盖；体温单条目链零触达
        verify(summaryMapper, times(1)).selectOne(any());
        verify(summaryMapper, never()).updateById(any(IoSummary.class));
        verifyNoInteractions(entryMapper, chartService);
    }

    @Test
    @DisplayName("条目引用回填兜底：appendDailyValue 后条目回读缺失拒 NS-1016，不落引用")
    void summarizeFailsWhenChartEntryVanishes() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfigVO());
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any())).thenReturn(List.of());
        when(chartService.ensurePage(eq(VISIT), any())).thenReturn(PAGE_ID);
        when(entryMapper.selectOne(any())).thenReturn(null);
        when(summaryMapper.insert(any(IoSummary.class))).thenAnswer(insertSummaryWithId(SUMMARY_ID));

        assertThatThrownBy(() -> service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "DAY")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getMessage()).contains("回读");
                });
        verify(summaryMapper, never()).updateById(any(IoSummary.class));
    }

    @Test
    @DisplayName("班次窗口形态：EVENING 16:00–24:00 窗口止=次日 00:00（种子「24:00」约定）")
    void summarizeShiftEndingAtMidnightEndsNextMidnight() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfigVO());
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any())).thenReturn(List.of());
        when(chartService.ensurePage(eq(VISIT), any())).thenReturn(PAGE_ID);
        when(entryMapper.selectOne(any())).thenReturn(chartEntry());
        when(summaryMapper.insert(any(IoSummary.class))).thenAnswer(insertSummaryWithId(SUMMARY_ID));

        service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "EVENING"));

        verify(recordMapper).sumByTypeAndPeriod(eq(VISIT), fromCaptor.capture(), toCaptor.capture());
        LocalDate today = LocalDate.now();
        assertThat(fromCaptor.getValue())
                .isEqualTo(today.atTime(16, 0).atZone(ZONE).toOffsetDateTime());
        assertThat(toCaptor.getValue())
                .isEqualTo(today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    }

    @Test
    @DisplayName("跨零点班次窗口：22:00–06:00 班次取 [昨日 22:00, 今日 06:00) 环绕窗")
    void summarizeShiftCrossMidnightUsesWrappedWindow() {
        WardConfigVO wrapped = new WardConfigVO(
                WARD, Map.of("NORMAL", 480), false, List.of(new ShiftDefinition("NIGHT2", "跨零点班", "22:00", "06:00")));
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(wardMetaService.wardConfig(WARD)).thenReturn(wrapped);
        when(recordMapper.sumByTypeAndPeriod(eq(VISIT), any(), any())).thenReturn(List.of());
        when(chartService.ensurePage(eq(VISIT), any())).thenReturn(PAGE_ID);
        when(entryMapper.selectOne(any())).thenReturn(chartEntry());
        when(summaryMapper.insert(any(IoSummary.class))).thenAnswer(insertSummaryWithId(SUMMARY_ID));

        service.summarize(new IoSummaryCreateRequest(VISIT, "SHIFT", "NIGHT2"));

        verify(recordMapper).sumByTypeAndPeriod(eq(VISIT), fromCaptor.capture(), toCaptor.capture());
        LocalDate today = LocalDate.now();
        assertThat(fromCaptor.getValue())
                .isEqualTo(today.minusDays(1).atTime(22, 0).atZone(ZONE).toOffsetDateTime());
        assertThat(toCaptor.getValue())
                .isEqualTo(today.atTime(6, 0).atZone(ZONE).toOffsetDateTime());
    }

    @Test
    @DisplayName("小结清单：date 非空收敛 period_start 当日窗口谓词且按周期起升序；date 空=全量不设窗口")
    void summariesFilterByPeriodWindowOrdered() {
        OffsetDateTime periodStart = LocalDate.now().atTime(8, 0).atZone(ZONE).toOffsetDateTime();
        when(summaryMapper.selectList(any())).thenReturn(List.of(existingSummary()));

        List<IoSummaryVO> windowed = service.summaries(VISIT, LocalDate.now());
        List<IoSummaryVO> all = service.summaries(VISIT, null);

        assertThat(windowed).hasSize(1);
        assertThat(windowed.get(0).id()).isEqualTo(SUMMARY_ID);
        assertThat(all).hasSize(1);
        verify(summaryMapper, times(2)).selectList(summaryQueryCaptor.capture());
        LambdaQueryWrapper<IoSummary> windowWrapper =
                renderedSummary(summaryQueryCaptor.getAllValues().get(0));
        assertThat(windowWrapper.getParamNameValuePairs().values()).contains(VISIT);
        assertThat(windowWrapper.getSqlSegment())
                .contains("period_start >=")
                .contains("period_start <")
                .contains("ORDER BY")
                .contains("period_start");
        LambdaQueryWrapper<IoSummary> allWrapper =
                renderedSummary(summaryQueryCaptor.getAllValues().get(1));
        assertThat(allWrapper.getSqlSegment()).doesNotContain("period_start >=");
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 小结全链桩（在区 + 月页 + 条目定位 + 小结 insert；SHIFT 用例另行桩病区班次定义）。 */
    private void stubSummaryChain() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(chartService.ensurePage(eq(VISIT), any())).thenReturn(PAGE_ID);
        when(entryMapper.selectOne(any())).thenReturn(chartEntry());
        when(summaryMapper.insert(any(IoSummary.class))).thenAnswer(insertSummaryWithId(SUMMARY_ID));
    }

    /** 病区班次定义桩（SHIFT 周期推导消费；24H 不读取班次定义）。 */
    private void stubWardConfig() {
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfigVO());
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

    /** 病区配置替身（V801 种子三班：DAY/EVENING/NIGHT，EVENING 止于「24:00」）。 */
    private WardConfigVO wardConfigVO() {
        return new WardConfigVO(
                WARD,
                Map.of("NORMAL", 480),
                false,
                List.of(
                        new ShiftDefinition("DAY", "白班", "08:00", "16:00"),
                        new ShiftDefinition("EVENING", "小夜班", "16:00", "24:00"),
                        new ShiftDefinition("NIGHT", "大夜班", "00:00", "08:00")));
    }

    /** 分型聚合行替身（sumByTypeAndPeriod 结果：仅 ioType/quantity 两字段有效）。 */
    private IoRecord typeSum(String ioType, String total) {
        IoRecord row = new IoRecord();
        row.setIoType(ioType);
        row.setQuantity(new BigDecimal(total));
        return row;
    }

    /** 明细行替身（按日清单用例载体，数量两位小数）。 */
    private IoRecord ioRow(long id, OffsetDateTime occurAt) {
        IoRecord row = new IoRecord();
        row.setId(id);
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        row.setWardId(WARD);
        row.setOccurAt(occurAt);
        row.setIoType("INTAKE");
        row.setItemCode("IV_FLUID");
        row.setItemName("静脉输液");
        row.setQuantity(new BigDecimal("100.00"));
        row.setUnit("ml");
        row.setSource("MANUAL");
        row.setRecorderId("nurse-01");
        return row;
    }

    /** 体温单 DAILY_VALUE 条目替身（条目引用回填定位桩，固定 id）。 */
    private TemperatureChartEntry chartEntry() {
        TemperatureChartEntry entry = new TemperatureChartEntry();
        entry.setId(ENTRY_ID);
        entry.setPageId(PAGE_ID);
        entry.setEntryType("DAILY_VALUE");
        entry.setTypeKey("IO_SUMMARY_SHIFT");
        return entry;
    }

    /** 既有小结替身（幂等命中行/小结清单用例载体，数量两位小数）。 */
    private IoSummary existingSummary() {
        IoSummary row = new IoSummary();
        row.setId(SUMMARY_ID);
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        row.setWardId(WARD);
        row.setSummaryType("SHIFT");
        row.setPeriodStart(LocalDate.now().atTime(8, 0).atZone(ZONE).toOffsetDateTime());
        row.setPeriodEnd(LocalDate.now().atTime(16, 0).atZone(ZONE).toOffsetDateTime());
        row.setTotalIntake(new BigDecimal("1800.00"));
        row.setTotalOutput(new BigDecimal("950.00"));
        row.setBalance(new BigDecimal("850.00"));
        row.setShiftCode("DAY");
        row.setChartEntryRef(ENTRY_ID);
        row.setRecorderId("nurse-01");
        return row;
    }

    /** 明细 insert 桩：回填固定 id 并返回影响行数 1。 */
    private org.mockito.stubbing.Answer<Integer> insertWithId(long id) {
        return inv -> {
            inv.getArgument(0, IoRecord.class).setId(id);
            return 1;
        };
    }

    /** 小结 insert 桩：回填固定 id 并返回影响行数 1。 */
    private org.mockito.stubbing.Answer<Integer> insertSummaryWithId(long id) {
        return inv -> {
            inv.getArgument(0, IoSummary.class).setId(id);
            return 1;
        };
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<IoRecord> rendered(Wrapper<IoRecord> captured) {
        LambdaQueryWrapper<IoRecord> wrapper = (LambdaQueryWrapper<IoRecord>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /** 同 rendered（小结实体侧，泛型不同须分开承载）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<IoSummary> renderedSummary(Wrapper<IoSummary> captured) {
        LambdaQueryWrapper<IoSummary> wrapper = (LambdaQueryWrapper<IoSummary>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }
}
