package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.controller.WardController;
import com.fuyun.nursing.dto.NurseAssignmentRequest;
import com.fuyun.nursing.dto.WardPatientRegisterRequest;
import com.fuyun.nursing.dto.WardPatientRemoveRequest;
import com.fuyun.nursing.entity.NurseAssignment;
import com.fuyun.nursing.entity.NursingWardConfig;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.NursingLevel;
import com.fuyun.nursing.enums.WardPatientSource;
import com.fuyun.nursing.enums.WardPatientStatus;
import com.fuyun.nursing.internal.NursingEventPublisher;
import com.fuyun.nursing.internal.PatientHealthSummaryListener;
import com.fuyun.nursing.internal.PatientMergedListener;
import com.fuyun.nursing.internal.PatientSplitListener;
import com.fuyun.nursing.mapper.NurseAssignmentMapper;
import com.fuyun.nursing.mapper.NursingWardConfigMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.vo.NurseAssignmentVO;
import com.fuyun.nursing.vo.WardConfigVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import com.fuyun.nursing.vo.WardPatientVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 病区元数据域服务单测（Task 3 十六用例冻结集 + 补充覆盖）：入区登记过渡通道守卫链
 * （visitId 结构校验 → 档案拦截 → 床位占用 → 幂等 upsert）、GC38 四护栏（移出零外发/触达最小/
 * reason 不落库/无 ADT 语义）、端点面冻结结构断言（禁写路径下渗）、患者合并/拆分成对逆映射、
 * 病区配置三班种子。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；
 * 条件更新断言直读 @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class WardMetaServiceImplTest {

    /** I 型 14 位合法 visit_id（结构校验守卫链通过值） */
    private static final String VISIT = "I2026092200001";

    /** 端点面冻结清单（2026-09-22 批复「禁写路径下渗」；方法 + 全路径逐字冻结） */
    private static final Set<String> FROZEN_ENDPOINTS = Set.of(
            "POST /api/v1/nursing/ward-patients",
            "POST /api/v1/nursing/ward-patients/{visitId}/remove",
            "GET /api/v1/nursing/ward-patients",
            "GET /api/v1/nursing/ward-patients/{visitId}",
            "GET /api/v1/nursing/assignments",
            "POST /api/v1/nursing/assignments",
            "DELETE /api/v1/nursing/assignments/{id}");

    /** V801 种子班次定义（与迁移 INSERT 行逐字同源） */
    private static final String SEED_SHIFTS =
            "[{\"code\":\"DAY\",\"name\":\"白班\",\"start\":\"08:00\",\"end\":\"16:00\"},"
                    + "{\"code\":\"EVENING\",\"name\":\"小夜班\",\"start\":\"16:00\",\"end\":\"24:00\"},"
                    + "{\"code\":\"NIGHT\",\"name\":\"大夜班\",\"start\":\"00:00\",\"end\":\"08:00\"}]";

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    @Mock
    private NurseAssignmentMapper assignmentMapper;

    @Mock
    private NursingWardConfigMapper wardConfigMapper;

    @Mock
    private PatientContextResolver patientContextResolver;

    @Mock
    private AllergyChecker allergyChecker;

    @Mock
    private NursingEventPublisher nursingEventPublisher;

    @Mock
    private MessagingGovernance messagingGovernance;

    @Captor
    private ArgumentCaptor<Wrapper<NursingWardPatient>> patientQueryCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<NurseAssignment>> assignmentQueryCaptor;

    @Captor
    private ArgumentCaptor<NursingWardPatient> patientRowCaptor;

    private WardMetaServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体均须手工注册表信息（一览/详情/分配三读面）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardPatient.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NurseAssignment.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardConfig.class);
    }

    @BeforeEach
    void setUp() {
        service = new WardMetaServiceImpl(
                wardPatientMapper,
                assignmentMapper,
                wardConfigMapper,
                patientContextResolver,
                allergyChecker,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", wardPatientMapper);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("入区登记：visitId 结构不合法拒 NS-1003，且不触达任何下游（守卫链首位）")
    void registerRejectsInvalidVisitId() {
        assertThatThrownBy(() -> service.register(registerReq("I20260922", "01")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.VISIT_ID_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1003");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(patientContextResolver, wardPatientMapper);
    }

    @Test
    @DisplayName("入区登记：FROZEN 档案拒 NS-1004，消息含「档案已冻结」")
    void registerRejectsFrozenPatient() {
        when(patientContextResolver.resolve(7L)).thenReturn(new PatientContextView(7L, 7L, "FROZEN", true, "欠费冻结"));

        assertThatThrownBy(() -> service.register(registerReq(VISIT, "01")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PATIENT_BLOCKED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1004");
                    assertThat(e.getMessage()).contains("档案已冻结");
                });
        verifyNoInteractions(wardPatientMapper);
    }

    @Test
    @DisplayName("入区登记：MERGED 档案按 resolvedPatientId 收敛主档落库（CF-3 归一语义）")
    void registerAcceptsMergedPatientByResolvedId() {
        when(patientContextResolver.resolve(7L)).thenReturn(new PatientContextView(7L, 99L, "MERGED", false, ""));
        when(wardPatientMapper.selectOne(any())).thenReturn(null);
        when(wardPatientMapper.selectCount(any())).thenReturn(0L);
        when(wardPatientMapper.insert(any(NursingWardPatient.class))).thenAnswer(inv -> {
            inv.getArgument(0, NursingWardPatient.class).setId(1L);
            return 1;
        });

        WardPatientVO vo = service.register(registerReq(VISIT, "01"));

        verify(wardPatientMapper).insert(patientRowCaptor.capture());
        assertThat(patientRowCaptor.getValue().getPatientId()).isEqualTo(99L);
        assertThat(patientRowCaptor.getValue().getStatus()).isEqualTo(WardPatientStatus.IN_WARD.getCode());
        assertThat(patientRowCaptor.getValue().getSource()).isEqualTo(WardPatientSource.MANUAL.getCode());
        assertThat(vo.patientId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("入区登记：同病区同床位已有在区行拒 NS-1002（不落 insert）")
    void registerRejectsOccupiedBed() {
        when(patientContextResolver.resolve(7L)).thenReturn(new PatientContextView(7L, 7L, "NORMAL", false, ""));
        when(wardPatientMapper.selectOne(any())).thenReturn(null);
        when(wardPatientMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.register(registerReq(VISIT, "01")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.BED_OCCUPIED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1002");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        // 床位占用谓词钉死：等值条件必须落在 (ward_id, bed_no, status=IN_WARD) 三元组上
        verify(wardPatientMapper).selectCount(patientQueryCaptor.capture());
        LambdaQueryWrapper<NursingWardPatient> wrapper = renderedPatient(patientQueryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("W01", "01", WardPatientStatus.IN_WARD.getCode());
        verify(wardPatientMapper, never()).insert(any(NursingWardPatient.class));
    }

    @Test
    @DisplayName("入区登记幂等：同 visit_id 已在区且视图属性全等 → 返回既有行零写入")
    void registerIsIdempotentOnSameVisit() {
        when(patientContextResolver.resolve(7L)).thenReturn(new PatientContextView(7L, 7L, "NORMAL", false, ""));
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));

        WardPatientVO vo = service.register(registerReq(VISIT, "01"));

        assertThat(vo.visitId()).isEqualTo(VISIT);
        assertThat(vo.bedNo()).isEqualTo("01");
        // 零写入钉死：不新建行、不回写、不做床位占用复查
        verify(wardPatientMapper, never()).insert(any(NursingWardPatient.class));
        verify(wardPatientMapper, never()).updateById(any(NursingWardPatient.class));
        verify(wardPatientMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("入区登记幂等：同 visit_id 再登记变更床位 → 更新既有行（insert 未被调），新床位先校验占用")
    void registerUpsertsExistingInWardRow() {
        when(patientContextResolver.resolve(7L)).thenReturn(new PatientContextView(7L, 7L, "NORMAL", false, ""));
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardPatientMapper.selectCount(any())).thenReturn(0L);

        WardPatientVO vo = service.register(registerReq(VISIT, "02"));

        assertThat(vo.bedNo()).isEqualTo("02");
        verify(wardPatientMapper).selectCount(patientQueryCaptor.capture());
        assertThat(renderedPatient(patientQueryCaptor.getValue())
                        .getParamNameValuePairs()
                        .values())
                .contains("W01", "02", WardPatientStatus.IN_WARD.getCode());
        verify(wardPatientMapper).updateById(patientRowCaptor.capture());
        assertThat(patientRowCaptor.getValue().getId()).isEqualTo(5L);
        assertThat(patientRowCaptor.getValue().getBedNo()).isEqualTo("02");
        verify(wardPatientMapper, never()).insert(any(NursingWardPatient.class));
    }

    @Test
    @DisplayName("病区一览：按 bed_no、admitted_at 升序（DB 侧 ORDER BY 钉死）返回床位序")
    void listByWardOrdersByBedNoThenAdmittedAt() {
        when(wardPatientMapper.selectList(any()))
                .thenReturn(List.of(
                        inWardRow(1L, 7L, "W01", "01"),
                        inWardRow(2L, 8L, "W01", "02"),
                        inWardRow(3L, 9L, "W01", "03")));

        List<WardPatientVO> list = service.listByWard("W01");

        assertThat(list).extracting(WardPatientVO::bedNo).containsExactly("01", "02", "03");
        verify(wardPatientMapper).selectList(patientQueryCaptor.capture());
        LambdaQueryWrapper<NursingWardPatient> wrapper = renderedPatient(patientQueryCaptor.getValue());
        assertThat(wrapper.getSqlSegment())
                .contains("ORDER BY")
                .contains("bed_no")
                .contains("admitted_at");
    }

    @Test
    @DisplayName("病区一览：仅本病区 IN_WARD 行（排除 REMOVED 与他病区；语义锁枚举仅二值）")
    void listByWardExcludesRemovedAndOtherWards() {
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(inWardRow(1L, 7L, "W01", "01")));

        List<WardPatientVO> list = service.listByWard("W01");

        verify(wardPatientMapper).selectList(patientQueryCaptor.capture());
        LambdaQueryWrapper<NursingWardPatient> wrapper = renderedPatient(patientQueryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains("W01", WardPatientStatus.IN_WARD.getCode());
        assertThat(list).hasSize(1);
        // GC38 语义锁：枚举值域仅 IN_WARD/REMOVED，无任何 ADT 语义值可写
        assertThat(WardPatientStatus.values())
                .extracting(WardPatientStatus::getCode)
                .containsExactly("IN_WARD", "REMOVED");
    }

    @Test
    @DisplayName("移出病区一览：CAS 置 REMOVED（在区谓词解除）+ reason 仅留痕不落库 + 零跨模块调用")
    void removeMarksStatusAndClearsBedOccupancy() {
        when(wardPatientMapper.casRemove(VISIT, "nurse-01")).thenReturn(1);

        WardPatientVO vo = service.remove(VISIT, new WardPatientRemoveRequest("患者转外院治疗"));

        assertThat(vo.visitId()).isEqualTo(VISIT);
        verify(wardPatientMapper).casRemove(VISIT, "nurse-01");
        // 无实体回写（updateById 载荷不含新列），不触达任何跨模块面/其他 mapper
        verify(wardPatientMapper, never()).updateById(any(NursingWardPatient.class));
        verifyNoInteractions(patientContextResolver, allergyChecker, assignmentMapper, wardConfigMapper);
        String sql = wardPatientSql("casRemove", String.class, String.class);
        // 床位占用谓词解除的可执行锚：在区态条件迁移至 REMOVED
        assertThat(sql).contains("status = 'REMOVED'");
        assertThat(sql).contains("status = 'IN_WARD'");
        assertThat(sql).contains("deleted = 0");
        // reason 仅入审计/日志留痕：SQL 不得承载 reason
        assertThat(sql).doesNotContain("reason");
    }

    @Test
    @DisplayName("移出病区一览（10b）：零外发 + 单表单语句触达最小（级联禁令可执行锚）")
    void removeHasZeroOutboundAndMinimalTouch() {
        when(wardPatientMapper.casRemove(VISIT, "nurse-01")).thenReturn(1);

        service.remove(VISIT, new WardPatientRemoveRequest("演示移出"));

        // ① 零外发：实现类依赖面禁止出现任何事件发布/治理构件（结构性不可达）
        for (Field field : WardMetaServiceImpl.class.getDeclaredFields()) {
            boolean outbound = NursingEventPublisher.class.isAssignableFrom(field.getType())
                    || MessagingGovernance.class.isAssignableFrom(field.getType())
                    || DomainEventSender.class.isAssignableFrom(field.getType());
            assertThat(outbound)
                    .as("移出路径实现类依赖面禁止出现事件发布/治理构件：%s", field.getName())
                    .isFalse();
        }
        verifyNoInteractions(nursingEventPublisher, messagingGovernance);
        // ② 触达最小：仅一条 @Update 于 nursing_ward_patient（表唯一、字段集仅 status + 审计列）
        verify(wardPatientMapper).casRemove(VISIT, "nurse-01");
        verifyNoMoreInteractions(wardPatientMapper);
        verifyNoInteractions(assignmentMapper, wardConfigMapper);
        assertThat(wardPatientSql("casRemove", String.class, String.class))
                .isEqualTo("UPDATE nursing.nursing_ward_patient SET status = 'REMOVED', updated_by = #{updatedBy} "
                        + "WHERE visit_id = #{visitId} AND status = 'IN_WARD' AND deleted = 0");
    }

    @Test
    @DisplayName("端点面冻结：公开端点集合逐字等于冻结清单，无独立 PUT/PATCH 视图属性变更端点")
    void noAdtWriteEndpointExposed() {
        assertThat(WardController.class.getAnnotation(RequestMapping.class))
                .as("类级 @RequestMapping 不承载（端点集合结构断言需方法级全路径）")
                .isNull();

        Set<String> actual = new HashSet<>();
        for (Method method : WardController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(PatchMapping.class))
                    .as("禁写路径下渗：出现独立 PUT/PATCH 视图属性变更端点：%s", method.getName())
                    .isFalse();
            if (method.isAnnotationPresent(GetMapping.class)) {
                actual.add("GET "
                        + firstPath(method.getAnnotation(GetMapping.class).value(), method));
            } else if (method.isAnnotationPresent(PostMapping.class)) {
                actual.add("POST "
                        + firstPath(method.getAnnotation(PostMapping.class).value(), method));
            } else if (method.isAnnotationPresent(DeleteMapping.class)) {
                actual.add("DELETE "
                        + firstPath(method.getAnnotation(DeleteMapping.class).value(), method));
            }
        }
        assertThat(actual).as("端点面扩即本用例失败（任何新增端点须先回决策点重新上报）").isEqualTo(FROZEN_ENDPOINTS);
    }

    @Test
    @DisplayName("责任分配：同（病区,床位,班次,生效日）二次分配拒 NS-1002（查重谓词四元组钉死）")
    void assignRejectsDuplicateBedShift() {
        when(assignmentMapper.selectCount(any())).thenReturn(1L);
        NurseAssignmentRequest req = new NurseAssignmentRequest(
                "W01", "nurse-09", "BED", "DAY", "01", null, LocalDate.of(2026, 9, 22), null);

        assertThatThrownBy(() -> service.assign(req)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.BED_OCCUPIED);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1002");
        });

        verify(assignmentMapper).selectCount(assignmentQueryCaptor.capture());
        LambdaQueryWrapper<NurseAssignment> wrapper = renderedAssignment(assignmentQueryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("W01", "01", "DAY", LocalDate.of(2026, 9, 22), "ACTIVE");
        verify(assignmentMapper, never()).insert(any(NurseAssignment.class));
    }

    @Test
    @DisplayName("详情卡：聚合过敏实时嵌查与当班责任护士；inFlightTasks 占位空清单；不含体征摘要字段")
    void detailAggregatesAllergyAndAssignments() {
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardConfigMapper.selectOne(any())).thenReturn(configRow());
        when(allergyChecker.listActiveAllergies(7L))
                .thenReturn(List.of(
                        new AllergyItem(1L, "PENICILLIN", "青霉素", "SEVERE"), new AllergyItem(2L, null, "海鲜", "MILD")));
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignmentRow(11L, "nurse-09", "BED", "01")));

        WardPatientDetailVO detail = service.detail(VISIT);

        assertThat(detail.allergies()).hasSize(2);
        assertThat(detail.assignments()).hasSize(1);
        assertThat(detail.inFlightTasks()).isNotNull().isEmpty();
        assertThat(detail.visitId()).isEqualTo(VISIT);
        assertThat(detail.patientId()).isEqualTo(7L);
        // 详情卡不含体征摘要：前端另调体征查询组装（防 WardMeta ↔ VitalSign 循环依赖；
        // 按「vital」词根检测，"sign" 会误伤 assignments 组件名）
        List<String> components = Arrays.stream(WardPatientDetailVO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("vital"));
    }

    @Test
    @DisplayName("患者合并消费：IN_WARD 行 patient_id 收敛为存活主档（载荷 7→99）")
    void mergeListenerCollapsesWardPatientToSurvivor() throws Exception {
        PatientMergedListener listener = new PatientMergedListener(null, wardPatientMapper);

        listener.handle(envelope("patient.patient.merged", "{\"survivorPatientId\":\"99\",\"mergedPatientId\":\"7\"}"));

        verify(wardPatientMapper).casMergePatient(7L, 99L);
        String sql = wardPatientSql("casMergePatient", long.class, long.class);
        assertThat(sql).contains("SET patient_id = #{survivorPatientId}");
        assertThat(sql).contains("WHERE patient_id = #{mergedPatientId}");
        assertThat(sql).contains("status = 'IN_WARD'").contains("deleted = 0");
    }

    @Test
    @DisplayName("患者拆分消费：merged 成对逆映射，IN_WARD 行按 restoredPatientId 还原（99→7）")
    void splitListenerRestoresRestoredPatientRow() throws Exception {
        PatientSplitListener listener = new PatientSplitListener(null, wardPatientMapper);

        listener.handle(
                envelope("patient.patient.split", "{\"restoredPatientId\":\"7\",\"survivorPatientId\":\"99\"}"));

        verify(wardPatientMapper).casSplitPatient(99L, 7L);
        String sql = wardPatientSql("casSplitPatient", long.class, long.class);
        assertThat(sql).contains("SET patient_id = #{restoredPatientId}");
        assertThat(sql).contains("WHERE patient_id = #{survivorPatientId}");
        assertThat(sql).contains("status = 'IN_WARD'").contains("deleted = 0");
    }

    @Test
    @DisplayName("病区配置：W01 返回三班种子（DAY/EVENING/NIGHT）且 IoT 自动落卡关闭")
    void wardConfigReturnsThreeShiftsFromSeed() {
        when(wardConfigMapper.selectOne(any())).thenReturn(configRow());

        WardConfigVO vo = service.wardConfig("W01");

        assertThat(vo.shifts())
                .extracting(WardConfigVO.ShiftDefinition::code)
                .containsExactly("DAY", "EVENING", "NIGHT");
        assertThat(vo.iotAutocastEnabled()).isFalse();
    }

    @Test
    @DisplayName("健康档案变更消费：按载荷刷新在区行过敏标识（allergyCodes 不消费不解析）")
    void healthSummaryListenerRefreshesAllergyFlag() throws Exception {
        PatientHealthSummaryListener listener = new PatientHealthSummaryListener(null, wardPatientMapper);

        listener.handle(envelope(
                "patient.health-summary.updated",
                "{\"patientId\":\"7\",\"hasAllergy\":true,\"allergyCodes\":[\"PENICILLIN\"]}"));

        verify(wardPatientMapper).updateAllergyFlag(7L, true);
        assertThat(wardPatientSql("updateAllergyFlag", long.class, boolean.class))
                .contains("SET allergy_flag = #{hasAllergy}")
                .contains("WHERE patient_id = #{patientId}")
                .contains("status = 'IN_WARD'")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("风险标识回写（Task 8 消费面）：追加缺失项，已含标识零写入不重复追加")
    void appendRiskFlagAppendsMissingAndSkipsDuplicate() {
        NursingWardPatient row = inWardRow(5L, 7L, "W01", "01");
        row.setRiskFlags("FALL");
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        when(wardPatientMapper.updateRiskFlags(VISIT, "FALL,PRESSURE", "nurse-01"))
                .thenReturn(1);

        service.appendRiskFlag(VISIT, "PRESSURE");

        verify(wardPatientMapper).updateRiskFlags(VISIT, "FALL,PRESSURE", "nurse-01");

        // 已含 FALL：不重复追加、零写入
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        service.appendRiskFlag(VISIT, "FALL");
        verify(wardPatientMapper, never()).updateRiskFlags(VISIT, "FALL", "nurse-01");
        verify(wardPatientMapper, never()).updateRiskFlags(VISIT, "FALL,FALL", "nurse-01");
    }

    @Test
    @DisplayName("责任分配类型一致性：PRIMARY 缺责任患者 / BED 携患者均拒 NS-1019")
    void assignRejectsTypeComponentMismatch() {
        NurseAssignmentRequest primaryWithoutPatient = new NurseAssignmentRequest(
                "W01", "nurse-09", "PRIMARY", "DAY", null, null, LocalDate.of(2026, 9, 22), null);
        assertThatThrownBy(() -> service.assign(primaryWithoutPatient))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        NurseAssignmentRequest bedWithPatient =
                new NurseAssignmentRequest("W01", "nurse-09", "BED", "DAY", "01", 7L, LocalDate.of(2026, 9, 22), null);
        assertThatThrownBy(() -> service.assign(bedWithPatient))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(assignmentMapper);
    }

    @Test
    @DisplayName("责任分配撤销：ACTIVE→CANCELLED 留痕；不存在的分配拒 NS-1016")
    void unassignCancelsAssignmentOrConflicts() {
        when(assignmentMapper.casCancel(11L, "nurse-01")).thenReturn(1);
        service.unassign(11L);
        verify(assignmentMapper).casCancel(11L, "nurse-01");

        when(assignmentMapper.casCancel(12L, "nurse-01")).thenReturn(0);
        assertThatThrownBy(() -> service.unassign(12L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
        });
    }

    @Test
    @DisplayName("当班分配清单：按病区+班次+ACTIVE 过滤（交接班 Task 9 消费面）")
    void listAssignmentsReturnsActiveByShift() {
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignmentRow(11L, "nurse-09", "BED", "01")));

        List<NurseAssignmentVO> list = service.listAssignments("W01", "DAY");

        verify(assignmentMapper).selectList(assignmentQueryCaptor.capture());
        LambdaQueryWrapper<NurseAssignment> wrapper = renderedAssignment(assignmentQueryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains("W01", "DAY", "ACTIVE");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).nurseId()).isEqualTo("nurse-09");
    }

    @Test
    @DisplayName("在途就诊 SPI：IN_WARD 行存在即真（合并前置检查「命中即阻断」口径）")
    void ongoingVisitQueryHitsInWardRow() {
        NursingOngoingVisitQuery query = new NursingOngoingVisitQuery(wardPatientMapper);
        when(wardPatientMapper.selectCount(any())).thenReturn(1L);

        assertThat(query.hasOngoingVisit(7L)).isTrue();

        when(wardPatientMapper.selectCount(any())).thenReturn(0L);
        assertThat(query.hasOngoingVisit(7L)).isFalse();
    }

    @Test
    @DisplayName("入区登记：护理级别 code 非法显式拒 NS-1019（W-22⑦ 禁裸 parse 先例）")
    void registerRejectsUnknownNursingLevel() {
        WardPatientRegisterRequest req =
                new WardPatientRegisterRequest(VISIT, 7L, "W01", "01", "张三", null, null, "URGENT", null);

        assertThatThrownBy(() -> service.register(req)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
        });
        verifyNoInteractions(wardPatientMapper);
    }

    @Test
    @DisplayName("风险标识回写：在区行不存在拒 NS-1001")
    void appendRiskFlagRejectsMissingRow() {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.appendRiskFlag(VISIT, "FALL"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND));
    }

    @Test
    @DisplayName("入区登记：双唯一约束并发冲突兜底转 NS-1002（禁裸插吞异常）")
    void registerTranslatesUniqueConflictToBedOccupied() {
        when(patientContextResolver.resolve(7L)).thenReturn(new PatientContextView(7L, 7L, "NORMAL", false, ""));
        when(wardPatientMapper.selectOne(any())).thenReturn(null);
        when(wardPatientMapper.selectCount(any())).thenReturn(0L);
        when(wardPatientMapper.insert(any(NursingWardPatient.class)))
                .thenThrow(new DuplicateKeyException("uk_ward_patient_visit"));

        assertThatThrownBy(() -> service.register(registerReq(VISIT, "01")))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.BED_OCCUPIED));
    }

    @Test
    @DisplayName("移出病区一览：在区行不存在（CAS 0 行）拒 NS-1001")
    void removeRejectsMissingInWardRow() {
        when(wardPatientMapper.casRemove(VISIT, "nurse-01")).thenReturn(0);

        assertThatThrownBy(() -> service.remove(VISIT, new WardPatientRemoveRequest("误操作移出")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("移出病区一览：无登录上下文时操作者回退 system（审计列默认同源）")
    void removeFallsBackToSystemOperatorWhenContextEmpty() {
        OperatorContextHolder.clear();
        when(wardPatientMapper.casRemove(VISIT, "system")).thenReturn(1);

        WardPatientVO vo = service.remove(VISIT, new WardPatientRemoveRequest("夜班批量清场"));

        assertThat(vo.visitId()).isEqualTo(VISIT);
        verify(wardPatientMapper).casRemove(VISIT, "system");
    }

    @Test
    @DisplayName("详情卡：在区行不存在拒 NS-1001")
    void detailRejectsMissingInWardRow() {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.detail(VISIT))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND));
        verifyNoInteractions(assignmentMapper, allergyChecker);
    }

    @Test
    @DisplayName("详情卡：病区无配置行时跳过班次过滤仍可出卡（兜底语义）")
    void detailSkipsShiftFilterWhenWardConfigMissing() {
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardConfigMapper.selectOne(any())).thenReturn(null);
        when(allergyChecker.listActiveAllergies(7L)).thenReturn(List.of());
        when(assignmentMapper.selectList(any())).thenReturn(List.of());

        WardPatientDetailVO detail = service.detail(VISIT);

        assertThat(detail.inFlightTasks()).isEmpty();
        assertThat(detail.assignments()).isEmpty();
    }

    @Test
    @DisplayName("详情卡：病区配置班次时刻非法时服务端数据异常显式失败（禁静默错卡）")
    void detailRejectsBrokenShiftTimeConfig() {
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardConfigMapper.selectOne(any()))
                .thenReturn(configRow("[{\"code\":\"BAD\",\"name\":\"坏班\",\"start\":\"25:99\",\"end\":\"07:00\"}]"));

        assertThatThrownBy(() -> service.detail(VISIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("班次时刻解析失败");
    }

    @Test
    @DisplayName("详情卡：跨零点班次（start > end）按环绕窗口判定不误判（边界语义）")
    void detailToleratesOvernightShiftWindow() {
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardConfigMapper.selectOne(any()))
                .thenReturn(
                        configRow("[{\"code\":\"NIGHT-X\",\"name\":\"跨零班\",\"start\":\"22:00\",\"end\":\"06:00\"}]"));
        when(allergyChecker.listActiveAllergies(7L)).thenReturn(List.of());
        when(assignmentMapper.selectList(any())).thenReturn(List.of());

        WardPatientDetailVO detail = service.detail(VISIT);

        assertThat(detail.visitId()).isEqualTo(VISIT);
        verify(assignmentMapper).selectList(assignmentQueryCaptor.capture());
    }

    @Test
    @DisplayName("责任分配：类型 code 非法显式拒 NS-1019")
    void assignRejectsUnknownTypeCode() {
        NurseAssignmentRequest req =
                new NurseAssignmentRequest("W01", "nurse-09", "LEAD", "DAY", null, 7L, LocalDate.of(2026, 9, 22), null);

        assertThatThrownBy(() -> service.assign(req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(assignmentMapper);
    }

    @Test
    @DisplayName("责任分配：BED 型床位空白视同缺失拒 NS-1019（禁脏行入库）")
    void assignRejectsBedShiftWithBlankBedNo() {
        NurseAssignmentRequest req =
                new NurseAssignmentRequest("W01", "nurse-09", "BED", "DAY", " ", null, LocalDate.of(2026, 9, 22), null);

        assertThatThrownBy(() -> service.assign(req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(assignmentMapper);
    }

    @Test
    @DisplayName("责任分配：PRIMARY 型查重通过后落 ACTIVE 行（责任组写路径）")
    void assignAcceptsPrimaryShiftWhenNoDuplicate() {
        when(assignmentMapper.selectCount(any())).thenReturn(0L);
        when(assignmentMapper.insert(any(NurseAssignment.class))).thenAnswer(inv -> {
            inv.getArgument(0, NurseAssignment.class).setId(21L);
            return 1;
        });
        NurseAssignmentRequest req = new NurseAssignmentRequest(
                "W01", "nurse-09", "PRIMARY", "DAY", null, 7L, LocalDate.of(2026, 9, 22), null);

        NurseAssignmentVO vo = service.assign(req);

        assertThat(vo.assignmentType()).isEqualTo("PRIMARY");
        assertThat(vo.patientId()).isEqualTo(7L);
        assertThat(vo.status()).isEqualTo("ACTIVE");
        verify(assignmentMapper).selectCount(assignmentQueryCaptor.capture());
        assertThat(renderedAssignment(assignmentQueryCaptor.getValue())
                        .getParamNameValuePairs()
                        .values())
                .contains("W01", 7L, "DAY", LocalDate.of(2026, 9, 22), "ACTIVE");
    }

    @Test
    @DisplayName("责任分配：BED 型生效日期缺省当日（validFrom 空缺省语义）")
    void assignAcceptsBedShiftWithDefaultValidFrom() {
        when(assignmentMapper.selectCount(any())).thenReturn(0L);
        when(assignmentMapper.insert(any(NurseAssignment.class))).thenAnswer(inv -> {
            inv.getArgument(0, NurseAssignment.class).setId(22L);
            return 1;
        });
        NurseAssignmentRequest req =
                new NurseAssignmentRequest("W01", "nurse-09", "BED", "DAY", "02", null, null, null);

        NurseAssignmentVO vo = service.assign(req);

        assertThat(vo.bedNo()).isEqualTo("02");
        assertThat(vo.validFrom()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("责任分配：部分唯一索引并发冲突兜底转 NS-1002")
    void assignTranslatesUniqueConflictToBedOccupied() {
        when(assignmentMapper.selectCount(any())).thenReturn(0L);
        when(assignmentMapper.insert(any(NurseAssignment.class)))
                .thenThrow(new DuplicateKeyException("uk_assignment_bed_shift"));
        NurseAssignmentRequest req = new NurseAssignmentRequest(
                "W01", "nurse-09", "BED", "DAY", "01", null, LocalDate.of(2026, 9, 22), null);

        assertThatThrownBy(() -> service.assign(req))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.BED_OCCUPIED));
    }

    @Test
    @DisplayName("病区配置：未知病区拒 NS-1016（CONFLICT 资源冲突语义位）")
    void wardConfigRejectsUnknownWard() {
        when(wardConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.wardConfig("W99")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
        });
    }

    @Test
    @DisplayName("病区配置：体征频次 JSON 损坏显式服务端数据异常（禁静默错频次）")
    void wardConfigRejectsCorruptVitalFreqJson() {
        NursingWardConfig config = configRow(SEED_SHIFTS);
        config.setVitalFreqConfig("not-json");
        when(wardConfigMapper.selectOne(any())).thenReturn(config);

        assertThatThrownBy(() -> service.wardConfig("W01"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("体征频次解析失败");
    }

    @Test
    @DisplayName("病区配置：班次定义 JSON 损坏显式服务端数据异常（禁静默错班次）")
    void wardConfigRejectsCorruptShiftJson() {
        when(wardConfigMapper.selectOne(any())).thenReturn(configRow("not-json"));

        assertThatThrownBy(() -> service.wardConfig("W01"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("班次定义解析失败");
    }

    @Test
    @DisplayName("风险标识回写：既有串为空时直接落首个标识（无前置逗号）")
    void appendRiskFlagJoinsOntoEmptyFlags() {
        NursingWardPatient row = inWardRow(5L, 7L, "W01", "01");
        row.setRiskFlags("");
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        when(wardPatientMapper.updateRiskFlags(VISIT, "FALL", "nurse-01")).thenReturn(1);

        service.appendRiskFlag(VISIT, "FALL");

        verify(wardPatientMapper).updateRiskFlags(VISIT, "FALL", "nurse-01");
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 登记入参构造（patientId 固定 7，其余缺省）。 */
    private WardPatientRegisterRequest registerReq(String visitId, String bedNo) {
        return new WardPatientRegisterRequest(visitId, 7L, "W01", bedNo, "张三", null, null, null, null);
    }

    /** 在区视图行构造（W01/ NORMAL/无风险，status=IN_WARD、source=MANUAL）。 */
    private NursingWardPatient inWardRow(long id, long patientId, String wardId, String bedNo) {
        NursingWardPatient row = new NursingWardPatient();
        row.setId(id);
        row.setVisitId(VISIT);
        row.setPatientId(patientId);
        row.setWardId(wardId);
        row.setBedNo(bedNo);
        row.setPatientName("张三");
        row.setNursingLevel(NursingLevel.NORMAL.getCode());
        row.setConditionTags("");
        row.setAllergyFlag(false);
        row.setRiskFlags("");
        row.setAdmittedAt(OffsetDateTime.now());
        row.setStatus(WardPatientStatus.IN_WARD.getCode());
        row.setSource(WardPatientSource.MANUAL.getCode());
        return row;
    }

    /** V801 种子同源配置行（三班 JSON + 缺省体征频次 + IoT 关）。 */
    private NursingWardConfig configRow() {
        return configRow(SEED_SHIFTS);
    }

    /** 自定义班次 JSON 配置行（损坏 JSON/跨零点班次用例载体）。 */
    private NursingWardConfig configRow(String shiftDefinitions) {
        NursingWardConfig config = new NursingWardConfig();
        config.setId(1L);
        config.setWardId("W01");
        config.setVitalFreqConfig("{\"SPECIAL\":60,\"CRITICAL\":240,\"NORMAL\":480}");
        config.setIotAutocastEnabled(false);
        config.setShiftDefinitions(shiftDefinitions);
        return config;
    }

    /** 分配行构造（ACTIVE）。 */
    private NurseAssignment assignmentRow(long id, String nurseId, String type, String bedNo) {
        NurseAssignment row = new NurseAssignment();
        row.setId(id);
        row.setWardId("W01");
        row.setNurseId(nurseId);
        row.setAssignmentType(type);
        row.setShiftCode("DAY");
        row.setBedNo(bedNo);
        row.setValidFrom(LocalDate.of(2026, 9, 22));
        row.setStatus("ACTIVE");
        return row;
    }

    /** 患者合并/拆分/健康档案事件信封替身（Long 以 string 承载与线格式同源）。 */
    private EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return new EventEnvelope(
                "ev-1", Instant.now(), "patient", eventType, "1", "trace-1", new ObjectMapper().readTree(payloadJson));
    }

    /** 取捕获的患者查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    private LambdaQueryWrapper<NursingWardPatient> renderedPatient(Wrapper<NursingWardPatient> captured) {
        LambdaQueryWrapper<NursingWardPatient> wrapper = (LambdaQueryWrapper<NursingWardPatient>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /** 取捕获的分配查询 wrapper 并渲染 SQL 片段（同上）。 */
    private LambdaQueryWrapper<NurseAssignment> renderedAssignment(Wrapper<NurseAssignment> captured) {
        LambdaQueryWrapper<NurseAssignment> wrapper = (LambdaQueryWrapper<NurseAssignment>) captured;
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
    private String wardPatientSql(String method, Class<?>... paramTypes) {
        try {
            Update update =
                    NursingWardPatientMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
    }

    /** 取映射路径首元素（端点结构断言辅助）。 */
    private String firstPath(String[] paths, Method method) {
        assertThat(paths).as("端点 %s 必须携带方法级全路径", method.getName()).isNotEmpty();
        return paths[0];
    }
}
