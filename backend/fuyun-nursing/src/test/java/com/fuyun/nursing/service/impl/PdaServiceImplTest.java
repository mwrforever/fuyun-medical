package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.PdaPatrolRequest;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.TaskPriority;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.enums.WardPatientStatus;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.service.IVitalSignService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.PdaPatientSummaryVO;
import com.fuyun.nursing.vo.VitalSignVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientIdentityQuery;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
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
import org.springframework.http.HttpStatus;

/**
 * PDA 后端面服务单测（Task 10 八用例冻结集 + 覆盖补充锚）：标识三形态解析（腕带就诊编码/
 * 就诊卡号/证件号）、PAT-1001 → NS-1003 错误码映射（M05 出口唯一）、FROZEN 拦截与 MERGED
 * 收敛主档、不在区降级摘要（PDA 患者查询不限在区）、脱敏输出结构断言（无证件号/手机号字段，
 * 姓名经 SensitiveMasker 掩码）、巡视打卡四参透传与扫错腕带跨患者拒收。MP 3.5.17 单测范式：
 * lambdaQuery 触达实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class PdaServiceImplTest {

    /** I 型 14 位合法 visit_id（腕带就诊编码路径载体） */
    private static final String VISIT = "I2026092200001";

    /** 病区编码（在区行/详情卡上下文） */
    private static final String WARD = "W01";

    /** 床位号（详情卡上下文） */
    private static final String BED = "12";

    /** 就诊卡号形态标识（15 位数字串 → VISIT_CARD 词表） */
    private static final String CARD = "VC2026092200001";

    /** 证件号形态标识（18 位 X 结尾 → ID_CARD 词表） */
    private static final String ID_CARD = "11010119900101123X";

    /** 标识解析归一后的主档患者主索引 */
    private static final long PATIENT_ID = 7L;

    /** MERGED 收敛后的存活主档患者主索引 */
    private static final long SURVIVOR_ID = 99L;

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    @Mock
    private PatientIdentityQuery identityQuery;

    @Mock
    private PatientContextResolver contextResolver;

    @Mock
    private AllergyChecker allergyChecker;

    @Mock
    private IWardMetaService wardMetaService;

    @Mock
    private IVitalSignService vitalSignService;

    @Mock
    private INursingTaskService taskService;

    private PdaServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（在区行双路定位）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardPatient.class);
    }

    @BeforeEach
    void setUp() {
        service = new PdaServiceImpl(
                wardPatientMapper,
                identityQuery,
                contextResolver,
                allergyChecker,
                wardMetaService,
                vitalSignService,
                taskService);
    }

    @Test
    @DisplayName("患者摘要：标识解析归一主档 + 病区上下文四字段逐值断言（过敏/体征/在途计数随行）")
    void patientSummaryResolvesByIdentifierAndReturnsWardContext() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(wardRow(VISIT, PATIENT_ID, WARD)));
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO(WARD, BED, "NORMAL", "张三", 2));
        when(allergyChecker.listActiveAllergies(PATIENT_ID))
                .thenReturn(List.of(new AllergyItem(1L, "PENICILLIN", "青霉素", "SEVERE")));
        OffsetDateTime latest = OffsetDateTime.now().minusHours(1);
        when(vitalSignService.latestByPatient(PATIENT_ID)).thenReturn(vitalVO(12L, latest));

        PdaPatientSummaryVO vo = service.patientSummary(CARD);

        // 冻结四字段：wardId/bedNo/nursingLevel/patientId 逐值断言（详情卡聚合透传）
        assertThat(vo.patientId()).isEqualTo(PATIENT_ID);
        assertThat(vo.wardId()).isEqualTo(WARD);
        assertThat(vo.bedNo()).isEqualTo(BED);
        assertThat(vo.nursingLevel()).isEqualTo("NORMAL");
        // 随行字段：过敏实时嵌查、最近一次体征（单行点查直取）、在途任务计数
        assertThat(vo.allergies()).hasSize(1);
        assertThat(vo.latestVitals().id()).isEqualTo(12L);
        assertThat(vo.inFlightTaskCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("患者摘要：FROZEN 档案拦截 NS-1004（409），零病区/过敏/体征触达")
    void patientSummaryRejectsFrozenPatient() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "FROZEN", true));

        assertThatThrownBy(() -> service.patientSummary(CARD)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PATIENT_BLOCKED);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1004");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verifyNoInteractions(wardMetaService, allergyChecker, vitalSignService, wardPatientMapper);
    }

    @Test
    @DisplayName("患者摘要：MERGED 从档解析收敛主档 patientId=99（过敏/体征按收敛后主档取数）")
    void patientSummaryUsesSurvivorIdForMergedPatient() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, SURVIVOR_ID, "MERGED", false));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(wardRow(VISIT, SURVIVOR_ID, WARD)));
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO(WARD, BED, "NORMAL", "李四", 0));
        when(allergyChecker.listActiveAllergies(SURVIVOR_ID)).thenReturn(List.of());
        when(vitalSignService.latestByPatient(SURVIVOR_ID)).thenReturn(null);

        PdaPatientSummaryVO vo = service.patientSummary(CARD);

        // MERGED 收敛：出参 patientId 为存活主档（业务数据一律挂收敛主档，CF-3）
        assertThat(vo.patientId()).isEqualTo(SURVIVOR_ID);
        verify(allergyChecker).listActiveAllergies(SURVIVOR_ID);
        verify(vitalSignService).latestByPatient(SURVIVOR_ID);
    }

    @Test
    @DisplayName("患者摘要：不在区降级——detail 抛 NS-1001 仍返回基本信息（wardId/bedNo 空、在途计数 0）")
    void patientSummaryReturnsEmptyWhenNotInWard() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(wardRow(VISIT, PATIENT_ID, WARD)));
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(
                        NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + VISIT));
        when(allergyChecker.listActiveAllergies(PATIENT_ID))
                .thenReturn(List.of(new AllergyItem(2L, null, "海鲜", "MODERATE")));
        when(vitalSignService.latestByPatient(PATIENT_ID)).thenReturn(null);

        PdaPatientSummaryVO vo = service.patientSummary(CARD);

        // PDA 患者查询不限在区：病区上下文降级为空，患者基本信息（过敏/体征）仍返回
        assertThat(vo.patientId()).isEqualTo(PATIENT_ID);
        assertThat(vo.wardId()).isNull();
        assertThat(vo.bedNo()).isNull();
        assertThat(vo.inFlightTaskCount()).isZero();
        assertThat(vo.allergies()).hasSize(1);
        assertThat(vo.latestVitals()).isNull();
    }

    @Test
    @DisplayName("患者摘要：标识未命中 PAT-1001 映射 NS-1003（400），禁透传 PAT- 前缀错误码")
    void patientSummaryRejectsUnresolvableIdentifier() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD))
                .thenThrow(new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "标识未登记或已失效"));

        assertThatThrownBy(() -> service.patientSummary(CARD)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.VISIT_ID_INVALID);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1003");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).doesNotContain("PAT-");
        });
        verifyNoInteractions(contextResolver, wardMetaService, allergyChecker, vitalSignService, wardPatientMapper);
    }

    @Test
    @DisplayName("患者摘要脱敏：VO 无证件号/手机号字段（反射结构断言），姓名经 SensitiveMasker 掩码（张三 → 张*）")
    void patientSummaryHidesSensitiveFields() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(wardRow(VISIT, PATIENT_ID, WARD)));
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO(WARD, BED, "NORMAL", "张三", 0));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(vitalSignService.latestByPatient(PATIENT_ID)).thenReturn(null);

        PdaPatientSummaryVO vo = service.patientSummary(CARD);

        // 结构断言：出参字段名不含证件号/手机号类载体（bedNo 为床位号业务字段不受限）
        List<String> sensitive = Arrays.stream(PdaPatientSummaryVO.class.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .filter(name -> name.contains("idcard")
                        || name.contains("phone")
                        || name.contains("mobile")
                        || name.equals("no"))
                .toList();
        assertThat(sensitive).as("PDA 摘要出参禁携证件号/手机号字段（P1 最小脱敏口径）").isEmpty();
        // 值断言：姓名为掩码形态（保留姓氏、其余打星，SensitiveMasker.maskName 语义）
        assertThat(vo.patientName()).isEqualTo("张*");
    }

    @Test
    @DisplayName("巡视打卡：四参透传（patientId/visitId/wardId/identifier）并返回 COMPLETED 任务")
    void patrolDelegatesToTaskServiceWithOperator() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectOne(any())).thenReturn(wardRow(VISIT, PATIENT_ID, WARD));
        when(taskService.patrol(PATIENT_ID, VISIT, WARD, CARD))
                .thenReturn(taskVO("TK2026092200002", TaskStatus.COMPLETED.getCode()));

        NursingTaskVO vo = service.patrol(new PdaPatrolRequest(CARD, VISIT));

        // 四参逐字断言：标识解析出的患者 + 请求就诊号 + 在区行病区 + 原样扫码标识（落 sourceRef 留痕）
        ArgumentCaptor<Long> patientIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> visitIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> wardIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> identifierCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService)
                .patrol(
                        patientIdCaptor.capture(),
                        visitIdCaptor.capture(),
                        wardIdCaptor.capture(),
                        identifierCaptor.capture());
        assertThat(patientIdCaptor.getValue()).isEqualTo(PATIENT_ID);
        assertThat(visitIdCaptor.getValue()).isEqualTo(VISIT);
        assertThat(wardIdCaptor.getValue()).isEqualTo(WARD);
        assertThat(identifierCaptor.getValue()).isEqualTo(CARD);
        assertThat(vo.status()).isEqualTo(TaskStatus.COMPLETED.getCode());
    }

    @Test
    @DisplayName("巡视打卡：visitId 不属于扫码患者（扫错腕带）拒 NS-1016（409 资源冲突语义），零委托")
    void patrolRejectsCrossWardVisit() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        // 在区行属另一位患者：扫码标识与就诊号归属不符
        when(wardPatientMapper.selectOne(any())).thenReturn(wardRow(VISIT, 8L, WARD));

        assertThatThrownBy(() -> service.patrol(new PdaPatrolRequest(CARD, VISIT)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(taskService, never()).patrol(anyLong(), any(), any(), any());
    }

    // ===================== 覆盖补充锚（JaCoCo service.impl LINE=1.00 名单） =====================

    @Test
    @DisplayName("患者摘要（腕带编码路径）：14 位 I 头标识直查在区行解析，不经 PatientIdentityQuery")
    void patientSummaryResolvesByVisitCodeWristband() {
        when(wardPatientMapper.selectOne(any())).thenReturn(wardRow(VISIT, PATIENT_ID, WARD));
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO(WARD, BED, "NORMAL", "张三", 1));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(vitalSignService.latestByPatient(PATIENT_ID)).thenReturn(null);

        PdaPatientSummaryVO vo = service.patientSummary(VISIT);

        assertThat(vo.patientId()).isEqualTo(PATIENT_ID);
        assertThat(vo.wardId()).isEqualTo(WARD);
        assertThat(vo.bedNo()).isEqualTo(BED);
        assertThat(vo.inFlightTaskCount()).isEqualTo(1);
        verifyNoInteractions(identityQuery);
    }

    @Test
    @DisplayName("患者摘要（腕带编码路径）：就诊号无在区行拒 NS-1001（404），零解析触达")
    void patientSummaryRejectsUnknownVisitCode() {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.patientSummary(VISIT)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.WARD_PATIENT_NOT_FOUND);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1001");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verifyNoInteractions(identityQuery, contextResolver, wardMetaService, allergyChecker, vitalSignService);
    }

    @Test
    @DisplayName("患者摘要（卡路径无在区行）：不触 detail 直接降级（wardId/bedNo 空、在途计数 0）")
    void patientSummaryDegradesWithoutDetailWhenNoInWardRow() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of());
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(vitalSignService.latestByPatient(PATIENT_ID)).thenReturn(null);

        PdaPatientSummaryVO vo = service.patientSummary(CARD);

        assertThat(vo.wardId()).isNull();
        assertThat(vo.bedNo()).isNull();
        assertThat(vo.nursingLevel()).isNull();
        assertThat(vo.inFlightTaskCount()).isZero();
        verify(wardMetaService, never()).detail(any());
    }

    @Test
    @DisplayName("患者摘要：detail 非 NS-1001 异常原样上抛（仅并发移出一种降级场景，不吞其他失败）")
    void patientSummaryRethrowsNonNotFoundDetailFailure() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(wardRow(VISIT, PATIENT_ID, WARD)));
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "未知病区"));

        assertThatThrownBy(() -> service.patientSummary(CARD)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
        });
        verifyNoInteractions(allergyChecker, vitalSignService);
    }

    @Test
    @DisplayName("患者摘要（证件号形态）：18 位 X 结尾标识按 ID_CARD 词表解析")
    void patientSummaryClassifiesIdCardForm() {
        when(identityQuery.resolveActivePatientId("ID_CARD", ID_CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(wardRow(VISIT, PATIENT_ID, WARD)));
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO(WARD, BED, "NORMAL", "张三", 0));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(vitalSignService.latestByPatient(PATIENT_ID)).thenReturn(null);

        PdaPatientSummaryVO vo = service.patientSummary(ID_CARD);

        assertThat(vo.patientId()).isEqualTo(PATIENT_ID);
        // 词表判定锚：证件号形态必须以 ID_CARD 类型送解析（盲索引等值查的类型键）
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(identityQuery).resolveActivePatientId(typeCaptor.capture(), valueCaptor.capture());
        assertThat(typeCaptor.getValue()).isEqualTo("ID_CARD");
        assertThat(valueCaptor.getValue()).isEqualTo(ID_CARD);
    }

    @Test
    @DisplayName("患者摘要入参卫兵：空白标识拒 NS-1019（400），零解析触达")
    void patientSummaryRejectsBlankIdentifier() {
        assertThatThrownBy(() -> service.patientSummary("  ")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
        });
        verifyNoInteractions(identityQuery, contextResolver, wardPatientMapper);
    }

    @Test
    @DisplayName("巡视打卡入参卫兵：标识/就诊号空白拒 NS-1019（服务面覆盖模块内直调场景）")
    void patrolRejectsBlankFields() {
        assertThatThrownBy(() -> service.patrol(new PdaPatrolRequest(" ", VISIT)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        assertThatThrownBy(() -> service.patrol(new PdaPatrolRequest(CARD, " ")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        verifyNoInteractions(identityQuery, wardPatientMapper, taskService);
    }

    @Test
    @DisplayName("巡视打卡：就诊号无在区行拒 NS-1016（无法核对归属），零委托")
    void patrolRejectsVisitNotInWard() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", CARD)).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.patrol(new PdaPatrolRequest(CARD, VISIT)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                });
        verify(taskService, never()).patrol(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("巡视打卡（腕带编码路径）：标识与就诊号同为腕带编码时双路在区查询后正常委托")
    void patrolDelegatesByVisitCodeIdentifier() {
        NursingWardPatient row = wardRow(VISIT, PATIENT_ID, WARD);
        when(wardPatientMapper.selectOne(any())).thenReturn(row, row);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(taskService.patrol(PATIENT_ID, VISIT, WARD, VISIT))
                .thenReturn(taskVO("TK2026092200003", TaskStatus.COMPLETED.getCode()));

        NursingTaskVO vo = service.patrol(new PdaPatrolRequest(VISIT, VISIT));

        assertThat(vo.status()).isEqualTo(TaskStatus.COMPLETED.getCode());
        verify(taskService).patrol(PATIENT_ID, VISIT, WARD, VISIT);
        verifyNoInteractions(identityQuery);
    }

    @Test
    @DisplayName("巡视打卡（短标识）：4 位内标识日志脱敏回退全星（等保红线——明文禁入日志）")
    void patrolMasksShortIdentifierInLogs() {
        when(identityQuery.resolveActivePatientId("VISIT_CARD", "BZ01")).thenReturn(PATIENT_ID);
        when(contextResolver.resolve(PATIENT_ID)).thenReturn(context(PATIENT_ID, PATIENT_ID, "NORMAL", false));
        when(wardPatientMapper.selectOne(any())).thenReturn(wardRow(VISIT, PATIENT_ID, WARD));
        when(taskService.patrol(PATIENT_ID, VISIT, WARD, "BZ01"))
                .thenReturn(taskVO("TK2026092200004", TaskStatus.COMPLETED.getCode()));

        NursingTaskVO vo = service.patrol(new PdaPatrolRequest("BZ01", VISIT));

        // 短标识（≤4 位）走全星脱敏分支且不影响打卡主链
        assertThat(vo.status()).isEqualTo(TaskStatus.COMPLETED.getCode());
        verify(taskService).patrol(PATIENT_ID, VISIT, WARD, "BZ01");
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 患者上下文视图替身（blocked=false 常规；resolvedPatientId 控制收敛语义）。 */
    private PatientContextView context(long patientId, long resolvedPatientId, String status, boolean blocked) {
        return new PatientContextView(patientId, resolvedPatientId, status, blocked, blocked ? "档案冻结" : "");
    }

    /** 在区行替身（PDA 双路定位载体：按就诊号/按患者主索引）。 */
    private NursingWardPatient wardRow(String visitId, long patientId, String wardId) {
        NursingWardPatient row = new NursingWardPatient();
        row.setWardId(wardId);
        row.setBedNo(BED);
        row.setPatientId(patientId);
        row.setVisitId(visitId);
        row.setPatientName("张三");
        row.setNursingLevel("NORMAL");
        row.setStatus(WardPatientStatus.IN_WARD.getCode());
        row.setAdmittedAt(OffsetDateTime.now().minusDays(1));
        return row;
    }

    /** 详情卡替身（PDA 摘要病区上下文来源；在途任务按计数复制同一出参行）。 */
    private WardPatientDetailVO detailVO(
            String wardId, String bedNo, String nursingLevel, String patientName, int inFlightCount) {
        return new WardPatientDetailVO(
                wardId,
                bedNo,
                PATIENT_ID,
                VISIT,
                patientName,
                "M",
                45,
                nursingLevel,
                "",
                false,
                "",
                OffsetDateTime.now().minusDays(1),
                List.of(),
                List.of(),
                Collections.nCopies(inFlightCount, taskVO("TK2026092200001", TaskStatus.PENDING.getCode())));
    }

    /** 任务出参替身（在途计数与打卡返回载体）。 */
    private NursingTaskVO taskVO(String taskNo, String status) {
        return new NursingTaskVO(
                1L,
                taskNo,
                PATIENT_ID,
                VISIT,
                WARD,
                BED,
                TaskType.PATROL.getCode(),
                TaskSource.MANUAL.getCode(),
                CARD,
                OffsetDateTime.now(),
                "nurse-01",
                TaskPriority.NORMAL.getCode(),
                false,
                0,
                status,
                OffsetDateTime.now(),
                null);
    }

    /** 体征出参替身（最近一次体征摘要载体，仅时点与 id 有区分度）。 */
    private VitalSignVO vitalVO(long id, OffsetDateTime measuredAt) {
        return new VitalSignVO(
                id,
                VISIT,
                PATIENT_ID,
                WARD,
                measuredAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "MANUAL",
                "CONFIRMED",
                "nurse-01",
                measuredAt,
                false,
                null,
                null,
                null);
    }
}
