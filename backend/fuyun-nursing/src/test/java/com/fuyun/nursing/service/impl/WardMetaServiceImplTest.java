package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.controller.WardController;
import com.fuyun.nursing.dto.NurseAssignmentRequest;
import com.fuyun.nursing.entity.NurseAssignment;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.entity.NursingWardConfig;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.NursingLevel;
import com.fuyun.nursing.enums.TaskPriority;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.internal.PatientHealthSummaryListener;
import com.fuyun.nursing.internal.PatientMergedListener;
import com.fuyun.nursing.internal.PatientSplitListener;
import com.fuyun.nursing.mapper.NurseAssignmentMapper;
import com.fuyun.nursing.mapper.NursingWardConfigMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.vo.NurseAssignmentVO;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.WardConfigVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import com.fuyun.nursing.vo.WardPatientVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
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
 * 病区元数据域服务单测（W-34 退役后冻结集）：读面契约（一览床位序/详情卡聚合含 patient api
 * 展示名嵌查）、责任分配（查重/类型一致性/CAS 撤销）、风险标识回写（EX-26 原子追加/移除与并发
 * 护航）、患者合并/拆分成对逆映射、病区配置三班种子，与 <b>退役核验三断言</b>（W-34 判退役
 * 完成的可执行锚）：①controller 公开方法面方法名清单断言（register/remove 已删，仅余 GET
 * 两端点 + assignments 三端点）②WardPatientStatus/WardPatientSource 两枚举类不存在断言
 * （反射 ClassNotFound）③GC39 六字段断言（WardPatientVO record 组件清单逐一 equals）。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；条件更新断言直读
 * @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class WardMetaServiceImplTest {

    /** I 型 14 位合法 visit_id（结构校验守卫链通过值） */
    private static final String VISIT = "I2026092200001";

    /** 端点面冻结清单（W-34 退役后：ward 两 GET + 责任分配三端点；2026-09-22 批复「禁写路径下渗」） */
    private static final Set<String> FROZEN_ENDPOINTS = Set.of(
            "GET /api/v1/nursing/ward-patients",
            "GET /api/v1/nursing/ward-patients/{visitId}",
            "GET /api/v1/nursing/assignments",
            "POST /api/v1/nursing/assignments",
            "DELETE /api/v1/nursing/assignments/{id}");

    /** controller 公开方法面冻结清单（W-34 退役核验断言①：register/remove 两方法已删） */
    private static final Set<String> FROZEN_PUBLIC_METHODS =
            Set.of("listByWard", "detail", "listAssignments", "assign", "unassign");

    /** V801 种子班次定义（与迁移 INSERT 行逐字同源） */
    private static final String SEED_SHIFTS =
            "[{\"code\":\"DAY\",\"name\":\"白班\",\"start\":\"08:00\",\"end\":\"16:00\"},"
                    + "{\"code\":\"EVENING\",\"name\":\"小夜班\",\"start\":\"16:00\",\"end\":\"24:00\"},"
                    + "{\"code\":\"NIGHT\",\"name\":\"大夜班\",\"start\":\"00:00\",\"end\":\"08:00\"}]";

    /** 北京钟面（时区纪律专项 A 类）：validFrom 缺省期望与生产医疗日同源口径的推导，禁裸 now() */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    @Mock
    private NurseAssignmentMapper assignmentMapper;

    @Mock
    private NursingWardConfigMapper wardConfigMapper;

    @Mock
    private PatientNameQuery patientNameQuery;

    @Mock
    private AllergyChecker allergyChecker;

    @Mock
    private INursingTaskService taskService;

    @Captor
    private ArgumentCaptor<Wrapper<NursingWardPatient>> patientQueryCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<NurseAssignment>> assignmentQueryCaptor;

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
                patientNameQuery,
                allergyChecker,
                taskService,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", wardPatientMapper);
        // 链式 lambdaQuery（A.4.3-13）走 getEntityClass（经 mapper 代理元数据解析），mock 下须显式注入
        ReflectionTestUtils.setField(service, "entityClass", NursingWardPatient.class);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
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
    @DisplayName("病区一览：仅本病区在册行（ward 过滤；逻辑删行由 @TableLogic 自动排除——W-34 后在册语义单承载）")
    void listByWardFiltersByWardOnly() {
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(inWardRow(1L, 7L, "W01", "01")));

        List<WardPatientVO> list = service.listByWard("W01");

        verify(wardPatientMapper).selectList(patientQueryCaptor.capture());
        LambdaQueryWrapper<NursingWardPatient> wrapper = renderedPatient(patientQueryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains("W01");
        // W-34 列退役锚：查询谓词不再触达 status（列已 DROP，触达即 SQL 报错）
        assertThat(wrapper.getSqlSegment()).doesNotContain("status");
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("端点面冻结（W-34 退役核验①）：公开方法面=方法名清单断言（register/remove 已删），端点集合逐字等于冻结清单")
    void noAdtWriteEndpointExposed() {
        assertThat(WardController.class.getAnnotation(RequestMapping.class))
                .as("类级 @RequestMapping 不承载（端点集合结构断言需方法级全路径）")
                .isNull();

        Set<String> publicMethods = new HashSet<>();
        Set<String> actual = new HashSet<>();
        for (Method method : WardController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(PatchMapping.class))
                    .as("禁写路径下渗：出现独立 PUT/PATCH 视图属性变更端点：%s", method.getName())
                    .isFalse();
            if (Modifier.isPublic(method.getModifiers())) {
                publicMethods.add(method.getName());
            }
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
        // W-34 退役核验①：controller 公开方法面只剩 GET 两端点 + assignments 三端点（方法名清单断言）
        assertThat(publicMethods)
                .as("W-34 退役：register/remove 两方法面必须删除（任何方法面增删须先回决策点重新上报）")
                .isEqualTo(FROZEN_PUBLIC_METHODS);
        assertThat(actual).as("端点面扩即本用例失败（任何新增端点须先回决策点重新上报）").isEqualTo(FROZEN_ENDPOINTS);
    }

    @Test
    @DisplayName("W-34 退役核验②：WardPatientStatus/WardPatientSource 两枚举类不存在（反射 ClassNotFound 固化退役）")
    void retiredEnumsAreGoneFromClasspath() {
        assertClassNotFound("com.fuyun.nursing.enums.WardPatientStatus");
        assertClassNotFound("com.fuyun.nursing.enums.WardPatientSource");
    }

    @Test
    @DisplayName("GC39 六字段断言（W-34 退役核验③）：WardPatientVO record 组件清单逐一 equals（读面不变契约）")
    void wardPatientVoComponentsFrozen() {
        List<String> components = Arrays.stream(WardPatientVO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        // 读面不变契约（GC39）：六字段名与序逐字冻结——四事件载荷可推导性锚定见 WardPatientVO 类注
        assertThat(components).containsExactly("visitId", "patientId", "wardId", "bedNo", "nursingLevel", "admittedAt");
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
    @DisplayName("详情卡：聚合 patient api 展示名嵌查、过敏实时嵌查与当班责任护士；inFlightTasks 实时填充；不含体征摘要字段")
    void detailAggregatesAllergyAndAssignments() {
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardConfigMapper.selectOne(any())).thenReturn(configRow());
        when(patientNameQuery.displayNamesOf(List.of(7L))).thenReturn(List.of(new PatientDisplayName(7L, "张*")));
        when(allergyChecker.listActiveAllergies(7L))
                .thenReturn(List.of(
                        new AllergyItem(1L, "PENICILLIN", "青霉素", "SEVERE"), new AllergyItem(2L, null, "海鲜", "MILD")));
        // V1114 访问授权种子行（PRIMARY 型 patient/bed 双 NULL）混入清单——详情卡分配段须排除（评审 C-F1）
        when(assignmentMapper.selectList(any()))
                .thenReturn(List.of(
                        assignmentRow(11L, "nurse-09", "BED", "01"),
                        assignmentRow(9114000000000000101L, "admin", "PRIMARY", null)));
        // 在途任务段：详情卡经 INursingTaskService#inFlightByVisit 实时填充
        when(taskService.inFlightByVisit(VISIT)).thenReturn(List.of(inFlightTaskVO()));

        WardPatientDetailVO detail = service.detail(VISIT);

        assertThat(detail.allergies()).hasSize(2);
        // 双 NULL 种子行被消费侧过滤——分配清单仅保留真实责任分配行（评审 C-F1）
        assertThat(detail.assignments()).hasSize(1);
        assertThat(detail.assignments().get(0).nurseId()).isEqualTo("nurse-09");
        // W-34 后展示名来源锚：patient api 嵌查出参（脱敏展示名），非投影行占位列
        assertThat(detail.patientName()).isEqualTo("张*");
        verify(patientNameQuery).displayNamesOf(List.of(7L));
        // 在途任务段由任务服务填充（非空清单 + 字段透传 + 填充来源钉死）
        assertThat(detail.inFlightTasks()).hasSize(1);
        assertThat(detail.inFlightTasks().get(0).taskNo()).isEqualTo("TK2026092200001");
        assertThat(detail.inFlightTasks().get(0).overdueFlag()).isTrue();
        verify(taskService).inFlightByVisit(VISIT);
        assertThat(detail.visitId()).isEqualTo(VISIT);
        assertThat(detail.patientId()).isEqualTo(7L);
        // 详情卡不含体征摘要：前端另调体征查询组装（防 WardMeta ↔ VitalSign 循环依赖；
        // 按「vital」词根检测，"sign" 会误伤 assignments 组件名）
        List<String> components = Arrays.stream(WardPatientDetailVO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("vital"));
        // W-34 退役锚：conditionTags 组件不可推导（GC39）已从详情卡出参面退役
        assertThat(components).doesNotContain("conditionTags");
    }

    @Test
    @DisplayName("详情卡：患者展示名嵌查无命中时 patientName 为 null（患者行缺失不阻断详情卡其余面）")
    void detailToleratesPatientNameMiss() {
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardConfigMapper.selectOne(any())).thenReturn(null);
        when(patientNameQuery.displayNamesOf(List.of(7L))).thenReturn(List.of());
        when(allergyChecker.listActiveAllergies(7L)).thenReturn(List.of());
        when(assignmentMapper.selectList(any())).thenReturn(List.of());
        when(taskService.inFlightByVisit(VISIT)).thenReturn(List.of());

        WardPatientDetailVO detail = service.detail(VISIT);

        assertThat(detail.patientName()).isNull();
        assertThat(detail.inFlightTasks()).isEmpty();
    }

    @Test
    @DisplayName("患者合并消费：在册行 patient_id 收敛为存活主档（载荷 7→99）")
    void mergeListenerCollapsesWardPatientToSurvivor() throws Exception {
        PatientMergedListener listener = new PatientMergedListener(null, wardPatientMapper);

        listener.handle(envelope("patient.patient.merged", "{\"survivorPatientId\":\"99\",\"mergedPatientId\":\"7\"}"));

        verify(wardPatientMapper).casMergePatient(7L, 99L);
        String sql = wardPatientSql("casMergePatient", long.class, long.class);
        assertThat(sql).contains("SET patient_id = #{survivorPatientId}");
        assertThat(sql).contains("WHERE patient_id = #{mergedPatientId}");
        assertThat(sql).contains("deleted = 0");
        // W-34 列退役锚：订阅面 SQL 不再触达 status 列
        assertThat(sql).doesNotContain("status");
    }

    @Test
    @DisplayName("患者拆分消费：merged 成对逆映射，在册行按 restoredPatientId 还原（99→7）")
    void splitListenerRestoresRestoredPatientRow() throws Exception {
        PatientSplitListener listener = new PatientSplitListener(null, wardPatientMapper);

        listener.handle(
                envelope("patient.patient.split", "{\"restoredPatientId\":\"7\",\"survivorPatientId\":\"99\"}"));

        verify(wardPatientMapper).casSplitPatient(99L, 7L);
        String sql = wardPatientSql("casSplitPatient", long.class, long.class);
        assertThat(sql).contains("SET patient_id = #{restoredPatientId}");
        assertThat(sql).contains("WHERE patient_id = #{survivorPatientId}");
        assertThat(sql).contains("deleted = 0");
        assertThat(sql).doesNotContain("status");
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
    @DisplayName("健康档案变更消费：按载荷刷新在册行过敏标识（allergyCodes 不消费不解析）")
    void healthSummaryListenerRefreshesAllergyFlag() throws Exception {
        PatientHealthSummaryListener listener = new PatientHealthSummaryListener(null, wardPatientMapper);

        listener.handle(envelope(
                "patient.health-summary.updated",
                "{\"patientId\":\"7\",\"hasAllergy\":true,\"allergyCodes\":[\"PENICILLIN\"]}"));

        verify(wardPatientMapper).updateAllergyFlag(7L, true);
        assertThat(wardPatientSql("updateAllergyFlag", long.class, boolean.class))
                .contains("SET allergy_flag = #{hasAllergy}")
                .contains("WHERE patient_id = #{patientId}")
                .contains("deleted = 0")
                .doesNotContain("status");
    }

    @Test
    @DisplayName("风险标识回写：追加缺失项，已含标识零写入不重复追加")
    void appendRiskFlagAppendsMissingAndSkipsDuplicate() {
        NursingWardPatient row = inWardRow(5L, 7L, "W01", "01");
        row.setRiskFlags("FALL");
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        when(wardPatientMapper.casAppendRiskFlag(VISIT, "PRESSURE", "nurse-01")).thenReturn(1);

        service.appendRiskFlag(VISIT, "PRESSURE");

        // 断言换锚（EX-26，同 EX-28 先例的 D-21 出口机械换面）：原「整串合并值 FALL,PRESSURE 传
        // updateRiskFlags」锚定的是被本次修复废止的服务层拼串细节；业务语义（追加缺失项发生携带
        // 审计操作者的写）不变，换锚为原子调用只携新标识（合并归 DB 侧拼接），严格度不低于原
        verify(wardPatientMapper).casAppendRiskFlag(VISIT, "PRESSURE", "nurse-01");

        // 已含 FALL：不重复追加、零写入
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        service.appendRiskFlag(VISIT, "FALL");
        verify(wardPatientMapper, never()).casAppendRiskFlag(VISIT, "FALL", "nurse-01");
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
    @DisplayName("在途就诊 SPI：在册投影行存在即真（合并前置检查「命中即阻断」口径；逻辑删自动排除）")
    void ongoingVisitQueryHitsInWardRow() {
        NursingOngoingVisitQuery query = new NursingOngoingVisitQuery(wardPatientMapper);
        when(wardPatientMapper.selectCount(any())).thenReturn(1L);

        assertThat(query.hasOngoingVisit(7L)).isTrue();

        when(wardPatientMapper.selectCount(any())).thenReturn(0L);
        assertThat(query.hasOngoingVisit(7L)).isFalse();
    }

    @Test
    @DisplayName("风险标识回写：在册行不存在拒 NS-1001")
    void appendRiskFlagRejectsMissingRow() {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.appendRiskFlag(VISIT, "FALL"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND));
    }

    @Test
    @DisplayName("详情卡：在册投影行不存在拒 NS-1001")
    void detailRejectsMissingInWardRow() {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.detail(VISIT))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND));
        verifyNoInteractions(assignmentMapper, allergyChecker, patientNameQuery);
    }

    @Test
    @DisplayName("详情卡：病区无配置行时跳过班次过滤仍可出卡（兜底语义）")
    void detailSkipsShiftFilterWhenWardConfigMissing() {
        when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
        when(wardConfigMapper.selectOne(any())).thenReturn(null);
        when(patientNameQuery.displayNamesOf(List.of(7L))).thenReturn(List.of());
        when(allergyChecker.listActiveAllergies(7L)).thenReturn(List.of());
        when(assignmentMapper.selectList(any())).thenReturn(List.of());
        when(taskService.inFlightByVisit(VISIT)).thenReturn(List.of());

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
        when(patientNameQuery.displayNamesOf(List.of(7L))).thenReturn(List.of());
        when(allergyChecker.listActiveAllergies(7L)).thenReturn(List.of());
        when(assignmentMapper.selectList(any())).thenReturn(List.of());
        when(taskService.inFlightByVisit(VISIT)).thenReturn(List.of());

        WardPatientDetailVO detail = service.detail(VISIT);

        assertThat(detail.visitId()).isEqualTo(VISIT);
        verify(assignmentMapper).selectList(assignmentQueryCaptor.capture());
    }

    @Test
    @DisplayName("当班班次分歧时区锚（时区纪律专项 A 类）：默认时区钟面与北京不同班窗时，当班过滤按北京墙钟判班")
    void detailJudgesCurrentShiftByBeijingClockUnderDivergedDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            // 构造与北京当前班窗必然分歧的默认时区：偏移差 +4h/-4h 动态二选一——三班各 8h 窗，
            // 钟面平移 +4h 的班窗分歧区间 [04,08)∪[12,16)∪[20,24) 与 -4h 的 [00,04)∪[08,12)∪[16,20)
            // 并集覆盖全天，任意时刻可复现「非北京时区 JVM 按容器墙钟判错班次」（锚定模式确定性红）
            ZoneId beijing = ZoneId.of("Asia/Shanghai");
            LocalTime beijingClock = LocalTime.now(beijing);
            int divergeMillis = 12 * 3600_000; // Δ=+4h：默认时区 UTC+12，钟面比北京快 4h
            if (shiftCodeOf(beijingClock).equals(shiftCodeOf(beijingClock.plusHours(4)))) {
                divergeMillis = 4 * 3600_000; // +4h 同窗时改用 -4h：默认时区 UTC+4，钟面比北京慢 4h
            }
            // 期望班次按北京墙钟锁定推导（与服务端医疗班次同口径，禁再取服务端外裸 now() 当期望源）
            String expectedShift = shiftCodeOf(beijingClock);
            when(wardPatientMapper.selectOne(any())).thenReturn(inWardRow(5L, 7L, "W01", "01"));
            when(wardConfigMapper.selectOne(any())).thenReturn(configRow());
            when(patientNameQuery.displayNamesOf(List.of(7L))).thenReturn(List.of());
            when(allergyChecker.listActiveAllergies(7L)).thenReturn(List.of());
            when(assignmentMapper.selectList(any())).thenReturn(List.of());
            when(taskService.inFlightByVisit(VISIT)).thenReturn(List.of());
            // setDefault 窗口最小化（锚定模式）：stub 先行 → setDefault → 调用捕获 → finally 恢复
            TimeZone.setDefault(new SimpleTimeZone(
                    divergeMillis,
                    ZoneOffset.ofTotalSeconds(divergeMillis / 1000).getId()));

            service.detail(VISIT);

            // 断言对象=服务端按墙钟判定的当班班次（selectList 谓词参数承载）：缺陷实现（裸
            // LocalTime.now()）在分歧默认时区下按容器墙钟判错班（错挂他班谓词），北京班次断言即红
            verify(assignmentMapper).selectList(assignmentQueryCaptor.capture());
            LambdaQueryWrapper<NurseAssignment> wrapper = renderedAssignment(assignmentQueryCaptor.getValue());
            assertThat(wrapper.getParamNameValuePairs().values()).contains(expectedShift);
        } finally {
            TimeZone.setDefault(original);
        }
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
        // 期望面必然同步北京钟面（时区纪律专项 A 类）：validFrom 缺省已收敛北京钟面医疗日，
        // 裸 now() 期望在非北京时区 JVM 深夜窗（北京 00:00-08:00）日期分歧即碎
        assertThat(vo.validFrom()).isEqualTo(LocalDate.now(BEIJING_TZ));
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
        when(wardPatientMapper.casAppendRiskFlag(VISIT, "FALL", "nurse-01")).thenReturn(1);

        service.appendRiskFlag(VISIT, "FALL");

        // 断言换锚（EX-26）：首标记「无前置逗号」业务规则由 SQL CASE 空串分支承载（调用只携新标识），
        // 换锚后同时钉死调用面与 SQL 面，严格度不低于原「服务层拼好 FALL 整串回写」
        verify(wardPatientMapper).casAppendRiskFlag(VISIT, "FALL", "nurse-01");
        assertThat(wardPatientSql("casAppendRiskFlag", String.class, String.class, String.class))
                .contains("CASE WHEN COALESCE(risk_flags, '') = '' THEN #{flag}");
    }

    @Test
    @DisplayName("风险标识原子追加（EX-26）：非空串追加走 DB 侧拼接单语句，SQL 契约（拼接/去重谓词/在册谓词）钉死")
    void appendRiskFlagAppendsAtomicallyViaDbSideConcat() {
        NursingWardPatient row = inWardRow(5L, 7L, "W01", "01");
        row.setRiskFlags("FALL");
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        when(wardPatientMapper.casAppendRiskFlag(VISIT, "PRESSURE", "nurse-01")).thenReturn(1);

        service.appendRiskFlag(VISIT, "PRESSURE");

        // 原子契约调用面：只携新标识与审计操作者（不再服务层拼整串），禁回退整串置值旧形态
        verify(wardPatientMapper).casAppendRiskFlag(VISIT, "PRESSURE", "nurse-01");
        String sql = wardPatientSql("casAppendRiskFlag", String.class, String.class, String.class);
        // DB 侧拼接锚：非空串分支追加 ','+新标识（行级锁串行化，并发追加互不覆盖）
        assertThat(sql).contains("risk_flags || ',' || #{flag}");
        // 幂等去重锚：首尾补逗 position 定位谓词（与 Java 侧 tokens.contains 逐字等价，并发同标识 0 行）
        assertThat(sql).contains("position(',' || #{flag} || ',' in ',' || COALESCE(risk_flags, '') || ',') = 0");
        // W-34 列退役锚：在册谓词由 deleted=0 单独承载
        assertThat(sql).contains("deleted = 0");
        assertThat(sql).doesNotContain("status");
    }

    @Test
    @DisplayName("风险标识首标记边界（EX-26）：空串行首追加仍只携新标识，无前置逗号语义由 SQL 空串分支承载")
    void appendRiskFlagFirstFlagOnEmptyGoesDbSideWithoutLeadingComma() {
        NursingWardPatient row = inWardRow(5L, 7L, "W01", "01");
        row.setRiskFlags("");
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        when(wardPatientMapper.casAppendRiskFlag(VISIT, "FALL", "nurse-01")).thenReturn(1);

        service.appendRiskFlag(VISIT, "FALL");

        // 空串不含任何标记：前置短路不拦截，原子调用直携首个标识（分隔符处理归 DB 侧 CASE）
        verify(wardPatientMapper).casAppendRiskFlag(VISIT, "FALL", "nurse-01");
    }

    @Test
    @DisplayName("并发追加护航（EX-26）：两并发追加不同标识互不覆盖（两标识都在）；并发同标识谓词去重不重复")
    void appendRiskFlagConcurrentAppendsBothSurvive() {
        // 交错窗口模拟：两并发请求均在对方提交前完成快照读（共享同一陈旧快照 riskFlags=空串），
        // 读-改-写旧形态各基于该快照整串回写互相覆盖只留一个（缺陷锚定）；DB 侧拼接两笔都落
        AtomicReference<String> dbRiskFlags = new AtomicReference<>("");
        NursingWardPatient staleSnapshot = inWardRow(5L, 7L, "W01", "01");
        staleSnapshot.setRiskFlags("");
        when(wardPatientMapper.selectOne(any())).thenReturn(staleSnapshot);
        // 模拟 DB 侧行为（与 @Update SQL 契约逐字对应：行级锁串行化拼接 + 谓词去重）
        when(wardPatientMapper.casAppendRiskFlag(any(), any(), any())).thenAnswer(inv -> {
            String flag = inv.getArgument(1, String.class);
            String current = dbRiskFlags.get();
            // 谓词去重：已含同标记 → 0 行幂等（不产生重复标记）
            if (("," + current + ",").contains("," + flag + ",")) {
                return 0;
            }
            // DB 侧拼接：空串直落标识（无前置逗号），非空串补逗号拼接
            dbRiskFlags.set(current.isEmpty() ? flag : current + "," + flag);
            return 1;
        });

        // 两并发追加不同标识：原子拼接下 DB 终值两标识都在（FALL,PRESSURE）
        service.appendRiskFlag(VISIT, "FALL");
        service.appendRiskFlag(VISIT, "PRESSURE");
        assertThat(dbRiskFlags.get()).contains("FALL", "PRESSURE");

        // 并发同标识重复追加：SQL 侧谓词拦截（0 行），终值无重复
        service.appendRiskFlag(VISIT, "FALL");
        assertThat(dbRiskFlags.get()).isEqualTo("FALL,PRESSURE");
    }

    @Test
    @DisplayName("风险标识原子移除（EX-26 N7 收口对称化）：DB 侧 array_remove 单语句摘除只携目标标识，" + "SQL 契约（移除/含标识谓词/在册谓词）钉死；不含该标识零写入幂等")
    void removeRiskFlagRemovesAtomicallyViaDbSideArrayRemove() {
        // 双标识行移除其一：原子调用只携目标标识与审计操作者（不再服务层拼剩余串整串回写）
        NursingWardPatient row = inWardRow(5L, 7L, "W01", "01");
        row.setRiskFlags("FALL,PRESSURE");
        when(wardPatientMapper.selectOne(any())).thenReturn(row);
        when(wardPatientMapper.casRemoveRiskFlag(VISIT, "PRESSURE", "nurse-01")).thenReturn(1);

        service.removeRiskFlag(VISIT, "PRESSURE");

        verify(wardPatientMapper).casRemoveRiskFlag(VISIT, "PRESSURE", "nurse-01");
        String sql = wardPatientSql("casRemoveRiskFlag", String.class, String.class, String.class);
        // DB 侧移除锚：string_to_array→array_remove→array_to_string 原生数组三连单语句摘除
        // （与追加侧原子拼接共用行级锁串行化，并发追加/移除双向不互吞）
        assertThat(sql).contains("array_to_string(array_remove(string_to_array(risk_flags, ','), #{flag}), ',')");
        // 幂等谓词锚：首尾补逗 position 定位「当前值含该标识」才施写（与追加侧同形态取反向）
        assertThat(sql).contains("position(',' || #{flag} || ',' in ',' || COALESCE(risk_flags, '') || ',') > 0");
        assertThat(sql).contains("deleted = 0");
        assertThat(sql).doesNotContain("status");

        // 仅存标识被移除：同样只携目标标识原子摘除（末位摘除落空串由 array_remove 自然承载）
        NursingWardPatient single = inWardRow(5L, 7L, "W01", "01");
        single.setRiskFlags("PRESSURE");
        when(wardPatientMapper.selectOne(any())).thenReturn(single);

        service.removeRiskFlag(VISIT, "PRESSURE");

        verify(wardPatientMapper, times(2)).casRemoveRiskFlag(VISIT, "PRESSURE", "nurse-01");

        // 不含目标标识（本就非高危的复评）：快照前置短路零写入——原子移除总触达仍为前两段的 2 次
        NursingWardPatient noFlag = inWardRow(5L, 7L, "W01", "01");
        noFlag.setRiskFlags("");
        when(wardPatientMapper.selectOne(any())).thenReturn(noFlag);
        service.removeRiskFlag(VISIT, "PRESSURE");
        verify(wardPatientMapper, times(2)).casRemoveRiskFlag(any(), any(), any());
    }

    @Test
    @DisplayName("风险标识移除 0 行幂等命中（EX-26 N7 收口）：目标已被并发移除先行，重读定性为幂等容忍不报错不重试")
    void removeRiskFlagZeroRowWithFlagAlreadyRemovedToleratedAsIdempotent() {
        // 交错模拟：快照读含目标 → 移除 CAS 施写前并发移除已先行提交（0 行）→ 重读行仍在且不含目标
        NursingWardPatient snapshot = inWardRow(5L, 7L, "W01", "01");
        snapshot.setRiskFlags("PRESSURE");
        NursingWardPatient latest = inWardRow(5L, 7L, "W01", "01");
        latest.setRiskFlags("");
        when(wardPatientMapper.selectOne(any())).thenReturn(snapshot, latest);
        when(wardPatientMapper.casRemoveRiskFlag(VISIT, "PRESSURE", "nurse-01")).thenReturn(0);

        service.removeRiskFlag(VISIT, "PRESSURE");

        // 幂等命中非冲突：恰一次原子移除触达，无异常上抛、无二次施写（复评降级消费正常收敛）
        verify(wardPatientMapper, times(1)).casRemoveRiskFlag(VISIT, "PRESSURE", "nurse-01");
    }

    @Test
    @DisplayName("风险标识移除 0 行重读行已不在区：NS-1001 定性（与入口缺行语义一致，禁静默吞掉）")
    void removeRiskFlagZeroRowWithVanishedRowClassifiedAsNs1001() {
        // 交错模拟：快照读在区 → 移除 CAS 施写前患者已被并发移出病区（0 行）→ 重读无在区行
        NursingWardPatient snapshot = inWardRow(5L, 7L, "W01", "01");
        snapshot.setRiskFlags("PRESSURE");
        when(wardPatientMapper.selectOne(any())).thenReturn(snapshot, (NursingWardPatient) null);
        when(wardPatientMapper.casRemoveRiskFlag(VISIT, "PRESSURE", "nurse-01")).thenReturn(0);

        assertThatThrownBy(() -> service.removeRiskFlag(VISIT, "PRESSURE"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND));
    }

    @Test
    @DisplayName("风险标识移除 0 行重读标识复现：让位并发重新追加（保留最新判级高危标记，重试会误删）")
    void removeRiskFlagZeroRowYieldsToConcurrentReappend() {
        // 交错模拟：移除 CAS 0 行（并发移除先行）后、重读前并发新评估又追加同标识——本轮移除
        // 意向对应旧判级，让位最新判级：正常返回不报错、不再二次施写（防误删新评估高危标记）
        NursingWardPatient snapshot = inWardRow(5L, 7L, "W01", "01");
        snapshot.setRiskFlags("PRESSURE");
        NursingWardPatient latest = inWardRow(5L, 7L, "W01", "01");
        latest.setRiskFlags("PRESSURE");
        when(wardPatientMapper.selectOne(any())).thenReturn(snapshot, latest);
        when(wardPatientMapper.casRemoveRiskFlag(VISIT, "PRESSURE", "nurse-01")).thenReturn(0);

        service.removeRiskFlag(VISIT, "PRESSURE");

        verify(wardPatientMapper, times(1)).casRemoveRiskFlag(any(), any(), any());
    }

    @Test
    @DisplayName("风险标识移除：在册行不存在拒 NS-1001")
    void removeRiskFlagRejectsMissingRow() {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.removeRiskFlag(VISIT, "PRESSURE"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND));
    }

    // ===================== 测试数据与断言辅助 =====================

    /**
     * 退役类不存在断言（W-34 退役核验②）：反射加载必须 ClassNotFound——类重现（如误 revert）
     * 即本断言失败，退役状态被结构固化。
     *
     * @param fqcn 待断言不存在的类全限定名，非空
     */
    private static void assertClassNotFound(String fqcn) {
        try {
            Class.forName(fqcn);
            fail("退役类不得存在于 classpath：%s", fqcn);
        } catch (ClassNotFoundException expected) {
            // 退役固化锚：类不存在即断言通过
        }
    }

    /**
     * 三班种子窗判定（锚定用例期望推导辅助，与 WardMetaServiceImpl.matchesShiftWindow 同口径：
     * 含头不含尾、「24:00」止于当日最大时刻——种子三班覆盖全天恒命中一班）。
     *
     * @param clock 待判定的墙钟时刻，非空；来源：锚定用例按北京钟面取值
     * @return 班次 code（NIGHT/DAY/EVENING），非空
     */
    private String shiftCodeOf(LocalTime clock) {
        if (clock.isBefore(LocalTime.of(8, 0))) {
            return "NIGHT";
        }
        return clock.isBefore(LocalTime.of(16, 0)) ? "DAY" : "EVENING";
    }

    /** 在册投影行构造（W01/NORMAL/无风险；W-34 后无 status/source 形态）。 */
    private NursingWardPatient inWardRow(long id, long patientId, String wardId, String bedNo) {
        NursingWardPatient row = new NursingWardPatient();
        row.setId(id);
        row.setVisitId(VISIT);
        row.setPatientId(patientId);
        row.setWardId(wardId);
        row.setBedNo(bedNo);
        row.setPatientName("");
        row.setNursingLevel(NursingLevel.NORMAL.getCode());
        row.setConditionTags("");
        row.setAllergyFlag(false);
        row.setRiskFlags("");
        row.setAdmittedAt(OffsetDateTime.now());
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

    /** 在途任务出参替身（详情卡在途任务段断言载体：逾期 MEDICATION 任务）。 */
    private NursingTaskVO inFlightTaskVO() {
        NursingTask row = new NursingTask();
        row.setId(601L);
        row.setTaskNo("TK2026092200001");
        row.setPatientId(7L);
        row.setVisitId(VISIT);
        row.setWardId("W01");
        row.setTaskType(TaskType.MEDICATION.getCode());
        row.setSource(TaskSource.MANUAL.getCode());
        row.setPlanTime(OffsetDateTime.now().minusMinutes(60));
        row.setPriority(TaskPriority.NORMAL.getCode());
        row.setOverdueFlag(true);
        row.setEscalationCount(1);
        row.setStatus(TaskStatus.PENDING.getCode());
        return NursingTaskVO.from(row);
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
