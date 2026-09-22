package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.SpecialEventRequest;
import com.fuyun.nursing.entity.TemperatureChartEntry;
import com.fuyun.nursing.entity.TemperatureChartPage;
import com.fuyun.nursing.enums.ChartEntryType;
import com.fuyun.nursing.enums.ChartPageStatus;
import com.fuyun.nursing.mapper.TemperatureChartEntryMapper;
import com.fuyun.nursing.mapper.TemperatureChartPageMapper;
import com.fuyun.nursing.vo.ChartEntryVO;
import com.fuyun.nursing.vo.TemperatureChartVO;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
 * 体温单域服务单测（Task 4 六用例冻结集）：月页自动创建幂等（ensurePage 二次零写入）、
 * VITAL 条目类型键唯一（同刻同部位幂等拒绝 NS-1016 / 同刻异部位并存）、特殊事件类型键写入、
 * 三类条目分组升序出参、DAILY_VALUE 日行值写入（Task 6 出入量小结消费面）。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class TemperatureChartServiceImplTest {

    /** I 型 14 位合法 visit_id */
    private static final String VISIT = "I2026092200001";

    /** 条目固定时点 07:00（同刻异部位用例基准） */
    private static final OffsetDateTime T0700 = OffsetDateTime.of(2026, 9, 22, 7, 0, 0, 0, ZoneOffset.UTC);

    /** 条目固定时点 09:00 */
    private static final OffsetDateTime T0900 = OffsetDateTime.of(2026, 9, 22, 9, 0, 0, 0, ZoneOffset.UTC);

    /** 条目固定时点 10:00 */
    private static final OffsetDateTime T1000 = OffsetDateTime.of(2026, 9, 22, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private TemperatureChartPageMapper pageMapper;

    @Mock
    private TemperatureChartEntryMapper entryMapper;

    @Captor
    private ArgumentCaptor<TemperatureChartEntry> entryCaptor;

    private TemperatureChartServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（月页/条目两读面）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TemperatureChartPage.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TemperatureChartEntry.class);
    }

    @BeforeEach
    void setUp() {
        service = new TemperatureChartServiceImpl(pageMapper, entryMapper);
        ReflectionTestUtils.setField(service, "baseMapper", pageMapper);
    }

    @Test
    @DisplayName("月页自动创建：同 (visitId, 2026-09) 二次调用返回同一 pageId（第二次数 0 写入）")
    void ensurePageCreatesMonthPageOnce() {
        when(pageMapper.selectOne(any())).thenReturn(null).thenReturn(pageRow());
        when(pageMapper.insert(any(TemperatureChartPage.class))).thenAnswer(inv -> {
            inv.getArgument(0, TemperatureChartPage.class).setId(77L);
            return 1;
        });

        Long first = service.ensurePage(VISIT, YearMonth.of(2026, 9));
        Long second = service.ensurePage(VISIT, YearMonth.of(2026, 9));

        assertThat(first).isEqualTo(77L);
        assertThat(second).isEqualTo(77L);
        verify(pageMapper, times(1)).insert(any(TemperatureChartPage.class));
    }

    @Test
    @DisplayName("VITAL 条目唯一：同 (page_id, entry_time, VITAL, 部位键) 二次写入唯一约束冲突转 NS-1016（幂等拒绝不覆盖首值）")
    void appendVitalEntryRejectsDuplicateKey() {
        when(pageMapper.selectOne(any())).thenReturn(pageRow());
        when(entryMapper.insert(any(TemperatureChartEntry.class)))
                .thenThrow(new DuplicateKeyException("uk_chart_entry_key"));

        assertThatThrownBy(() -> service.appendVitalEntry(VISIT, T0700.toInstant(), 101L, "AXILLARY"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 幂等拒绝：仅首插尝试一次，无更新兜底（不覆盖首值）
        verify(entryMapper, times(1)).insert(any(TemperatureChartEntry.class));
    }

    @Test
    @DisplayName("VITAL 条目：同刻先 AXILLARY 后 ORAL 两条并存（type_key 各异、vitalRef 各自指向对应体征行）")
    void appendVitalEntryAllowsSameTimeDifferentSite() {
        when(pageMapper.selectOne(any())).thenReturn(pageRow());
        when(entryMapper.insert(any(TemperatureChartEntry.class))).thenAnswer(inv -> {
            inv.getArgument(0, TemperatureChartEntry.class).setId(System.nanoTime());
            return 1;
        });

        service.appendVitalEntry(VISIT, T0700.toInstant(), 101L, "AXILLARY");
        service.appendVitalEntry(VISIT, T0700.toInstant(), 102L, "ORAL");

        verify(entryMapper, times(2)).insert(entryCaptor.capture());
        List<TemperatureChartEntry> entries = entryCaptor.getAllValues();
        assertThat(entries).extracting(TemperatureChartEntry::getTypeKey).containsExactly("AXILLARY", "ORAL");
        assertThat(entries).extracting(TemperatureChartEntry::getVitalRef).containsExactly(101L, 102L);
        assertThat(entries)
                .extracting(TemperatureChartEntry::getEntryType)
                .containsOnly(ChartEntryType.VITAL.getCode());
        assertThat(entries).extracting(TemperatureChartEntry::getPageId).containsOnly(77L);
        // 同刻并存：时点为同一时刻（TIMESTAMPTZ 按时刻比较，不绑定存储偏移）
        assertThat(entries).extracting(row -> row.getEntryTime().toInstant()).containsOnly(T0700.toInstant());
    }

    @Test
    @DisplayName("特殊事件写入：PHYSICAL_COOLING 落 SPECIAL_EVENT 条目且 type_key=事件类型、remark 回读一致")
    void addSpecialEventWritesEntryWithTypeKey() {
        when(pageMapper.selectOne(any())).thenReturn(pageRow());
        when(entryMapper.insert(any(TemperatureChartEntry.class))).thenAnswer(inv -> {
            inv.getArgument(0, TemperatureChartEntry.class).setId(5L);
            return 1;
        });

        ChartEntryVO vo = service.addSpecialEvent(VISIT, new SpecialEventRequest("PHYSICAL_COOLING", "物理降温前体温 39.2℃"));

        verify(entryMapper).insert(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getEntryType()).isEqualTo(ChartEntryType.SPECIAL_EVENT.getCode());
        assertThat(entryCaptor.getValue().getTypeKey()).isEqualTo("PHYSICAL_COOLING");
        assertThat(entryCaptor.getValue().getSpecialEventType()).isEqualTo("PHYSICAL_COOLING");
        assertThat(entryCaptor.getValue().getRemark()).isEqualTo("物理降温前体温 39.2℃");
        assertThat(vo.entryType()).isEqualTo(ChartEntryType.SPECIAL_EVENT.getCode());
        assertThat(vo.typeKey()).isEqualTo("PHYSICAL_COOLING");
        assertThat(vo.remark()).isEqualTo("物理降温前体温 39.2℃");
    }

    @Test
    @DisplayName("体温单查询：VITAL/SPECIAL_EVENT/DAILY_VALUE 混合条目按三段分组且各段按 entryTime 升序")
    void getChartGroupsEntriesByTypeAndTime() {
        when(pageMapper.selectOne(any())).thenReturn(pageRow());
        OffsetDateTime dailyAt = T0900.plusMinutes(30);
        when(entryMapper.selectList(any()))
                .thenReturn(List.of(
                        entry(1L, ChartEntryType.VITAL.getCode(), "", T1000),
                        entry(2L, ChartEntryType.SPECIAL_EVENT.getCode(), "ADMISSION", T0900),
                        entry(3L, ChartEntryType.VITAL.getCode(), "ORAL", T0700),
                        entry(4L, ChartEntryType.DAILY_VALUE.getCode(), "IO_SUMMARY_24H", dailyAt)));

        TemperatureChartVO vo = service.getChart(VISIT, "2026-09");

        assertThat(vo.visitId()).isEqualTo(VISIT);
        assertThat(vo.chartMonth()).isEqualTo("2026-09");
        assertThat(vo.pageId()).isEqualTo(77L);
        // VITAL 段升序：07:00（id=3）先于 10:00（id=1）
        assertThat(vo.vitals()).extracting(ChartEntryVO::id).containsExactly(3L, 1L);
        assertThat(vo.vitals()).extracting(ChartEntryVO::typeKey).containsExactly("ORAL", "");
        assertThat(vo.specialEvents()).extracting(ChartEntryVO::id).containsExactly(2L);
        assertThat(vo.dailyValues()).extracting(ChartEntryVO::id).containsExactly(4L);
    }

    @Test
    @DisplayName("日行值写入（Task 6 消费面）：IO_SUMMARY_24H 条目 type_key=日行值类型、valueText 回读一致")
    void appendDailyValueWritesIoSummaryRef() {
        when(pageMapper.selectOne(any())).thenReturn(pageRow());
        when(entryMapper.insert(any(TemperatureChartEntry.class))).thenAnswer(inv -> {
            inv.getArgument(0, TemperatureChartEntry.class).setId(6L);
            return 1;
        });

        service.appendDailyValue(VISIT, "IO_SUMMARY_24H", "入 2500 / 出 2100", "nurse-01");

        verify(entryMapper).insert(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getEntryType()).isEqualTo(ChartEntryType.DAILY_VALUE.getCode());
        assertThat(entryCaptor.getValue().getTypeKey()).isEqualTo("IO_SUMMARY_24H");
        assertThat(entryCaptor.getValue().getDailyValueType()).isEqualTo("IO_SUMMARY_24H");
        assertThat(entryCaptor.getValue().getValueText()).isEqualTo("入 2500 / 出 2100");
        assertThat(entryCaptor.getValue().getRecorderId()).isEqualTo("nurse-01");
        // GC25 护理文书红线：条目时点为服务器时间
        assertThat(entryCaptor.getValue().getEntryTime()).isNotNull();
    }

    @Test
    @DisplayName("体温单查询：月页未创建返回空出参（三段空清单，未产生过条目属常态非错误）")
    void getChartReturnsEmptyVoWhenPageMissing() {
        when(pageMapper.selectOne(any())).thenReturn(null);

        TemperatureChartVO vo = service.getChart(VISIT, "2026-09");

        assertThat(vo.pageId()).isNull();
        assertThat(vo.pageStatus()).isNull();
        assertThat(vo.vitals()).isEmpty();
        assertThat(vo.specialEvents()).isEmpty();
        assertThat(vo.dailyValues()).isEmpty();
        verifyNoInteractions(entryMapper);
    }

    @Test
    @DisplayName("体温单查询：month 格式非法显式拒 NS-1019（W-22⑦ 禁裸 parse 先例），不触达任何 mapper")
    void getChartRejectsMalformedMonth() {
        assertThatThrownBy(() -> service.getChart(VISIT, "2026/09")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
        });
        verifyNoInteractions(pageMapper, entryMapper);
    }

    @Test
    @DisplayName("特殊事件录入：事件类型 code 非法显式拒 NS-1019，不开页不落条目")
    void addSpecialEventRejectsUnknownEventType() {
        assertThatThrownBy(() -> service.addSpecialEvent(VISIT, new SpecialEventRequest("BREAK", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        verifyNoInteractions(pageMapper, entryMapper);
    }

    @Test
    @DisplayName("日行值写入：值文本空白显式拒 NS-1019（禁脏条目入库），不开页不落条目")
    void appendDailyValueRejectsBlankValueText() {
        assertThatThrownBy(() -> service.appendDailyValue(VISIT, "IO_SUMMARY_24H", " ", "nurse-01"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        verifyNoInteractions(pageMapper, entryMapper);
    }

    @Test
    @DisplayName("月页并发创建：唯一索引冲突转 NS-1016 幂等拒绝（PG 同事务重查不可达，调用方整单重试）")
    void ensurePageRejectsConcurrentDuplicateKey() {
        when(pageMapper.selectOne(any())).thenReturn(null);
        when(pageMapper.insert(any(TemperatureChartPage.class)))
                .thenThrow(new DuplicateKeyException("uk_chart_page_visit_month"));

        assertThatThrownBy(() -> service.ensurePage(VISIT, YearMonth.of(2026, 9)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // I2 可执行锚：仅首查一次（无同事务重查——PG 语句失败事务 aborted，25P02）
        verify(pageMapper, times(1)).selectOne(any());
        verify(pageMapper, times(1)).insert(any(TemperatureChartPage.class));
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 既有月页替身（id=77、2026-09、ACTIVE）。 */
    private TemperatureChartPage pageRow() {
        TemperatureChartPage page = new TemperatureChartPage();
        page.setId(77L);
        page.setVisitId(VISIT);
        page.setChartMonth("2026-09");
        page.setStatus(ChartPageStatus.ACTIVE.getCode());
        return page;
    }

    /** 体温单条目替身（getChart 分组用例载体）。 */
    private TemperatureChartEntry entry(long id, String entryType, String typeKey, OffsetDateTime entryTime) {
        TemperatureChartEntry row = new TemperatureChartEntry();
        row.setId(id);
        row.setPageId(77L);
        row.setEntryTime(entryTime);
        row.setEntryType(entryType);
        row.setTypeKey(typeKey);
        return row;
    }
}
