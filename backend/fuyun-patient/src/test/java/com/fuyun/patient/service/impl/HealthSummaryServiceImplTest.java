package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientHealthSummaryUpdatedPayload;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.dto.HealthItemCorrectRequest;
import com.fuyun.patient.dto.HealthItemCreateRequest;
import com.fuyun.patient.entity.HealthItem;
import com.fuyun.patient.entity.HealthSummary;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.mapper.HealthItemMapper;
import com.fuyun.patient.mapper.HealthSummaryMapper;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.vo.HealthItemVO;
import com.fuyun.patient.vo.HealthSummaryVO;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 健康档案实现单测（FU-M02-05）：新增落 ACTIVE 行并发 health-summary.updated（载荷聚合过敏 code）、
 * 纠错留痕（旧行 CORRECTED + 新行回链）、过敏校验接口位仅取 ACTIVE、无聚合行空摘要懒创建口径，
 * 以及患者/明细不存在守卫（PAT-1001/PAT-1017）。
 */
@ExtendWith(MockitoExtension.class)
class HealthSummaryServiceImplTest {

    @Mock
    private IPatientService patientService;

    @Mock
    private HealthItemMapper healthItemMapper;

    /** 聚合行 baseMapper 替身：lambdaQuery().one() 经 baseMapper.selectOne 承载（模块内 ServiceImpl 单测同款） */
    @Mock
    private HealthSummaryMapper healthSummaryMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private HealthSummaryServiceImpl healthSummaryService;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（lambda 列名解析依赖 TableInfo，模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), HealthSummary.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), HealthItem.class);
    }

    @BeforeEach
    void setUp() {
        healthSummaryService = new HealthSummaryServiceImpl(patientService, healthItemMapper, eventPublisher);
        ReflectionTestUtils.setField(healthSummaryService, "baseMapper", healthSummaryMapper);
        ReflectionTestUtils.setField(healthSummaryService, "entityClass", HealthSummary.class);
    }

    /** ACTIVE 过敏行夹具（可变对象，供 stub 与状态断言共用同一引用） */
    private HealthItem allergyRow(Long id, Long patientId, String itemCode, String severity) {
        HealthItem row = new HealthItem();
        row.setId(id);
        row.setPatientId(patientId);
        row.setItemType("ALLERGY");
        row.setItemCode(itemCode);
        row.setItemName("青霉素");
        row.setSeverity(severity);
        row.setStatus("ACTIVE");
        row.setSource("DOCTOR_STATION");
        return row;
    }

    /** 聚合行夹具（仅主键与患者挂接，摘要字段留空） */
    private HealthSummary summaryRow(Long id, Long patientId) {
        HealthSummary row = new HealthSummary();
        row.setId(id);
        row.setPatientId(patientId);
        return row;
    }

    @Test
    @DisplayName("新增过敏项：落 ACTIVE 首录行（无纠错回链）并发布 health-summary.updated，载荷含新过敏 code")
    void addItemPersistsActiveRowAndPublishesUpdatedWithAllergyCode() {
        when(patientService.getById(5L)).thenReturn(new Patient());
        // 模拟 ASSIGN_ID 插入期回填主键（与生产参数处理器行为一致）
        when(healthItemMapper.insert(any(HealthItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, HealthItem.class).setId(71L);
            return 1;
        });
        // 无聚合行：touchSummary 走懒创建路径
        when(healthSummaryMapper.selectOne(any())).thenReturn(null);
        // 事件聚合读库态：库中已有该 ACTIVE 过敏行（载荷 allergyCodes 应含其 code）
        when(healthItemMapper.selectList(any())).thenReturn(List.of(allergyRow(71L, 5L, "PENICILLIN", "SEVERE")));

        HealthItemVO vo = healthSummaryService.addItem(
                5L,
                new HealthItemCreateRequest(
                        "ALLERGY", "PENICILLIN", "青霉素", "SEVERE", "2026-01-01", "DOCTOR_STATION", "皮试阳性"));

        assertThat(vo.id()).isEqualTo(71L);
        ArgumentCaptor<HealthItem> insertCaptor = ArgumentCaptor.forClass(HealthItem.class);
        verify(healthItemMapper).insert(insertCaptor.capture());
        HealthItem saved = insertCaptor.getValue();
        assertThat(saved.getPatientId()).isEqualTo(5L);
        assertThat(saved.getItemType()).isEqualTo("ALLERGY");
        assertThat(saved.getItemCode()).isEqualTo("PENICILLIN");
        assertThat(saved.getItemName()).isEqualTo("青霉素");
        assertThat(saved.getSeverity()).isEqualTo("SEVERE");
        assertThat(saved.getOnsetDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        // 首录行恒为有效态且无纠错回链（纠错链仅由 correct 建立）
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getSource()).isEqualTo("DOCTOR_STATION");
        assertThat(saved.getNote()).isEqualTo("皮试阳性");
        assertThat(saved.getCorrectOfItemId()).isNull();
        // 聚合行懒创建落库且 summary_updated_at 事件时间锚点刷新
        ArgumentCaptor<HealthSummary> summaryCaptor = ArgumentCaptor.forClass(HealthSummary.class);
        verify(healthSummaryMapper).insert(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getPatientId()).isEqualTo(5L);
        verify(healthSummaryMapper).updateById(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getSummaryUpdatedAt()).isNotNull();
        // 事件断言：类型 + 载荷 hasAllergy/allergyCodes 聚合自 ACTIVE 过敏项
        ArgumentCaptor<PatientDomainEvent> eventCaptor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(PatientMessagingConstants.EVENT_HEALTH_SUMMARY_UPDATED);
        PatientHealthSummaryUpdatedPayload payload =
                (PatientHealthSummaryUpdatedPayload) eventCaptor.getValue().payload();
        assertThat(payload.patientId()).isEqualTo(5L);
        assertThat(payload.hasAllergy()).isTrue();
        assertThat(payload.allergyCodes()).containsExactly("PENICILLIN");
    }

    @Test
    @DisplayName("纠错留痕：旧行置 CORRECTED 保留，新行 correct_of_item_id 回链且纠错说明落新行 note")
    void correctMarksOldRowCorrectedAndInsertsLinkedNewRow() {
        HealthItem old = allergyRow(71L, 5L, "PENICILLIN", "SEVERE");
        when(healthItemMapper.selectById(71L)).thenReturn(old);
        when(healthItemMapper.insert(any(HealthItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, HealthItem.class).setId(72L);
            return 1;
        });
        // 已有聚合行：touchSummary 走更新路径
        when(healthSummaryMapper.selectOne(any())).thenReturn(summaryRow(9L, 5L));
        // 纠错后库态无 ACTIVE 过敏项：载荷 hasAllergy=false 且清单为空
        when(healthItemMapper.selectList(any())).thenReturn(List.of());

        HealthItemVO vo = healthSummaryService.correct(
                71L,
                new HealthItemCorrectRequest("青霉素（皮试阳性）", "PENICILLIN", "MODERATE", "2026-02-01", "原录名称有误，纠正为皮试阳性"));

        // 纠错不改原记录：旧行仅在原行上置 CORRECTED 留痕
        assertThat(old.getStatus()).isEqualTo("CORRECTED");
        verify(healthItemMapper).updateById(old);
        ArgumentCaptor<HealthItem> insertCaptor = ArgumentCaptor.forClass(HealthItem.class);
        verify(healthItemMapper).insert(insertCaptor.capture());
        HealthItem fresh = insertCaptor.getValue();
        assertThat(fresh.getPatientId()).isEqualTo(5L);
        assertThat(fresh.getItemType()).isEqualTo("ALLERGY");
        assertThat(fresh.getItemName()).isEqualTo("青霉素（皮试阳性）");
        assertThat(fresh.getItemCode()).isEqualTo("PENICILLIN");
        assertThat(fresh.getSeverity()).isEqualTo("MODERATE");
        assertThat(fresh.getOnsetDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(fresh.getStatus()).isEqualTo("ACTIVE");
        // 来源沿旧行（纠错请求无 source 字段）
        assertThat(fresh.getSource()).isEqualTo("DOCTOR_STATION");
        // 纠错说明落新行 note，回链指向被纠错原行
        assertThat(fresh.getNote()).isEqualTo("原录名称有误，纠正为皮试阳性");
        assertThat(fresh.getCorrectOfItemId()).isEqualTo(71L);
        assertThat(vo.id()).isEqualTo(72L);
        ArgumentCaptor<PatientDomainEvent> eventCaptor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(PatientMessagingConstants.EVENT_HEALTH_SUMMARY_UPDATED);
        PatientHealthSummaryUpdatedPayload payload =
                (PatientHealthSummaryUpdatedPayload) eventCaptor.getValue().payload();
        assertThat(payload.patientId()).isEqualTo(5L);
        assertThat(payload.hasAllergy()).isFalse();
        assertThat(payload.allergyCodes()).isEmpty();
    }

    @Test
    @DisplayName("过敏校验接口位：仅 ACTIVE 过敏项入清单（wrapper 条件锁定 patient_id/item_type/status）")
    void listActiveAllergiesReturnsOnlyActiveAllergyItems() {
        when(healthItemMapper.selectList(any())).thenReturn(List.of(allergyRow(71L, 5L, "PENICILLIN", "SEVERE")));

        List<AllergyItem> items = healthSummaryService.listActiveAllergies(5L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).itemId()).isEqualTo(71L);
        assertThat(items.get(0).itemCode()).isEqualTo("PENICILLIN");
        assertThat(items.get(0).itemName()).isEqualTo("青霉素");
        assertThat(items.get(0).severity()).isEqualTo("SEVERE");
        // wrapper 断言（3.5.17：条件参数在 getSqlSegment 惰性求值时才写入 paramNameValuePairs）
        ArgumentCaptor<Wrapper<HealthItem>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(healthItemMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<HealthItem> wrapper = (LambdaQueryWrapper<HealthItem>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .contains("patient_id")
                .contains("item_type")
                .contains("status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(5L, "ALLERGY", "ACTIVE");
    }

    @Test
    @DisplayName("无聚合行懒创建口径：getSummary 返回空摘要（非 null）且不落库，items 查全量")
    void getSummaryWithoutAggregateRowReturnsEmptySummaryWithoutPersisting() {
        when(patientService.getById(5L)).thenReturn(new Patient());
        when(healthSummaryMapper.selectOne(any())).thenReturn(null);
        // 空患者：明细亦为空，出参 items 空清单
        when(healthItemMapper.selectList(any())).thenReturn(List.of());

        HealthSummaryVO vo = healthSummaryService.getSummary(5L);

        assertThat(vo.patientId()).isEqualTo(5L);
        assertThat(vo.bloodType()).isNull();
        assertThat(vo.rhType()).isNull();
        assertThat(vo.pastHistory()).isNull();
        assertThat(vo.familyHistory()).isNull();
        assertThat(vo.summaryUpdatedAt()).isNull();
        assertThat(vo.items()).isEmpty();
        // 懒创建：查询路径不落库（建行仅发生在 touchSummary 写路径）
        verify(healthSummaryMapper, never()).insert(any(HealthSummary.class));
    }

    @Test
    @DisplayName("患者不存在：addItem 抛 PAT-1001（404），不落明细不发布事件")
    void addItemOnMissingPatientFailsAsPat1001() {
        when(patientService.getById(99L)).thenReturn(null);

        assertThatThrownBy(() -> healthSummaryService.addItem(
                        99L, new HealthItemCreateRequest("ALLERGY", null, "青霉素", null, null, "MANUAL", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(healthItemMapper, never()).insert(any(HealthItem.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("纠错明细不存在：PAT-1017（404），不动聚合行不发布事件")
    void correctOnMissingItemFailsAsPat1017() {
        when(healthItemMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> healthSummaryService.correct(
                        404L, new HealthItemCorrectRequest("青霉素", null, null, null, "纠错说明")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.HEALTH_ITEM_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(healthItemMapper, never()).insert(any(HealthItem.class));
        verifyNoInteractions(eventPublisher);
    }
}
