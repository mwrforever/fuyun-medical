package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.entity.NursingWardConfig;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.NursingWardConfigMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.vo.RoutineTaskGenerateVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
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

/**
 * 常规模板批量生成器单测（P2 PR-3 Task 9）：模板切片生成（frequencyMinutes 当日余下切片×
 * 在区患者投影）、幂等业务去重（templateCode+visitId+planTime，Instant 归一防 DB 往返 offset
 * 漂移）、空模板/无在区患者/过往日期零生成、未知病区 NS-1016、模板配置不合规 NS-1019、
 * 任务号唯一冲突幂等跳过。MP 3.5.17 单测范式：LambdaQueryWrapper 触达实体 @BeforeAll 注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class RoutineTaskGeneratorImplTest {

    /** 病区编码 */
    private static final String WARD = "W01";

    /** 在区患者就诊号 */
    private static final String VISIT = "I2026100200001";

    /** 患者主索引 */
    private static final long PATIENT_ID = 801L;

    /** 床号 */
    private static final String BED_NO = "01";

    /** 翻身 q2h 模板（V1107 列契约形态） */
    private static final String TURN_TEMPLATE =
            "[{\"templateCode\":\"TURN_Q2H\",\"name\":\"翻身\",\"frequencyMinutes\":120,\"taskType\":\"TURN\"}]";

    /** 北京时区（切片钟面断言基准，与生成器同源） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private NursingWardConfigMapper wardConfigMapper;

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    @Mock
    private NursingTaskMapper taskMapper;

    @Mock
    private NursingSeqGate seqGate;

    @Captor
    private ArgumentCaptor<NursingTask> rowCaptor;

    private RoutineTaskGeneratorImpl generator;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：LambdaQueryWrapper 触达的实体须手工注册表信息（配置/投影/查重三查面）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardConfig.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardPatient.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingTask.class);
    }

    @BeforeEach
    void setUp() {
        generator = new RoutineTaskGeneratorImpl(
                wardConfigMapper, wardPatientMapper, taskMapper, seqGate, new ObjectMapper());
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("模板切片生成：未来日期全日 12 片（q2h）×1 在区患者逐片落库，ROUTINE 源+sourceRef=模板码")
    void generatesSlicedTasksForInWardPatient() {
        // 未来两日日期：窗口=全日切片（当日余下面全量确定性等价——跨双日午夜不可达，无钟面竞态）
        LocalDate date = LocalDate.now(ZONE).plusDays(2);
        when(wardConfigMapper.selectOne(any())).thenReturn(config(TURN_TEMPLATE));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(inWardPatient()));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("TK")).thenReturn("TK2026100400001");
        when(taskMapper.insert(any(NursingTask.class))).thenReturn(1);

        RoutineTaskGenerateVO vo = generator.generateForWard(WARD, date);

        assertThat(vo.createdTasks()).isEqualTo(12);
        verify(taskMapper, org.mockito.Mockito.times(12)).insert(rowCaptor.capture());
        List<NursingTask> rows = rowCaptor.getAllValues();
        // 切片钟面锚：首片=当日 00:00 北京钟面、末片=22:00（120 分钟步进 12 片）
        DateTimeFormatter planFormat = DateTimeFormatter.ofPattern("HH:mm");
        assertThat(rows.get(0).getPlanTime().atZoneSameInstant(ZONE).format(planFormat))
                .isEqualTo("00:00");
        assertThat(rows.get(11).getPlanTime().atZoneSameInstant(ZONE).format(planFormat))
                .isEqualTo("22:00");
        for (NursingTask row : rows) {
            // 落库面：ROUTINE 源 + sourceRef=模板码（幂等键承载）+ PENDING 态 + 投影四键映射
            assertThat(row.getSource()).isEqualTo(TaskSource.ROUTINE.getCode());
            assertThat(row.getSourceRef()).isEqualTo("TURN_Q2H");
            assertThat(row.getTaskType()).isEqualTo(TaskType.TURN.getCode());
            assertThat(row.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
            assertThat(row.getVisitId()).isEqualTo(VISIT);
            assertThat(row.getPatientId()).isEqualTo(PATIENT_ID);
            assertThat(row.getWardId()).isEqualTo(WARD);
            assertThat(row.getBedNo()).isEqualTo(BED_NO);
            assertThat(row.getOverdueFlag()).isFalse();
            assertThat(row.getEscalationCount()).isZero();
        }
    }

    @Test
    @DisplayName("幂等去重：既有 (templateCode+visitId+planTime) 行跳过（Instant 归一——offset 表示差异不漏判）")
    void skipsExistingBusinessKeyRows() {
        LocalDate date = LocalDate.now(ZONE).plusDays(2);
        OffsetDateTime dayStart = date.atStartOfDay(ZONE).toOffsetDateTime();
        when(wardConfigMapper.selectOne(any())).thenReturn(config(TURN_TEMPLATE));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(inWardPatient()));
        // 既有行：同 templateCode+visitId+首片 planTime——以 UTC offset 表示（Instant 归一去重应命中）
        NursingTask existing = new NursingTask();
        existing.setSourceRef("TURN_Q2H");
        existing.setVisitId(VISIT);
        existing.setPlanTime(dayStart.toInstant().atOffset(java.time.ZoneOffset.UTC));
        when(taskMapper.selectList(any())).thenReturn(List.of(existing));
        when(seqGate.nextNo("TK")).thenReturn("TK2026100400001");
        when(taskMapper.insert(any(NursingTask.class))).thenReturn(1);

        RoutineTaskGenerateVO vo = generator.generateForWard(WARD, date);

        // 首片已存在：11 片新生成
        assertThat(vo.createdTasks()).isEqualTo(11);
        verify(taskMapper, org.mockito.Mockito.times(11)).insert(rowCaptor.capture());
        assertThat(rowsPlanTimes(rowCaptor.getAllValues())).doesNotContain(dayStart.toInstant());
    }

    @Test
    @DisplayName("空模板零生成：routine_task_templates 空数组/空白文本均直接返回（无投影查询触达——种子缺位注记面）")
    void emptyTemplatesGenerateNothing() {
        // 空数组形态（V1107 列默认）
        when(wardConfigMapper.selectOne(any())).thenReturn(config("[]"));
        assertThat(generator.generateForWard(WARD, null).createdTasks()).isZero();

        // 空白文本形态（列值未维护的兜底分支）
        when(wardConfigMapper.selectOne(any())).thenReturn(config("  "));
        assertThat(generator.generateForWard(WARD, null).createdTasks()).isZero();

        verifyNoInteractions(wardPatientMapper, taskMapper, seqGate);
    }

    @Test
    @DisplayName("未知病区拒 NS-1016：ward_config 无配置行")
    void unknownWardRejected() {
        when(wardConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> generator.generateForWard("W99", null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(wardPatientMapper, taskMapper, seqGate);
    }

    @Test
    @DisplayName("模板 JSON 不合规拒 NS-1019：列配置形态违例显式暴露")
    void malformedTemplatesRejected() {
        when(wardConfigMapper.selectOne(any())).thenReturn(config("not-json"));

        assertThatThrownBy(() -> generator.generateForWard(WARD, null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("模板字段不合规拒 NS-1019：frequencyMinutes 非正（切片步进不可为零）")
    void invalidTemplateFieldRejected() {
        when(wardConfigMapper.selectOne(any()))
                .thenReturn(
                        config(
                                "[{\"templateCode\":\"TURN_Q2H\",\"name\":\"翻身\",\"frequencyMinutes\":0,\"taskType\":\"TURN\"}]"));

        assertThatThrownBy(() -> generator.generateForWard(WARD, null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("无在区患者零生成：投影空集零插入（查重查询亦不触达）")
    void noInWardPatientsGenerateNothing() {
        when(wardConfigMapper.selectOne(any())).thenReturn(config(TURN_TEMPLATE));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of());

        assertThat(generator.generateForWard(WARD, null).createdTasks()).isZero();

        verify(taskMapper, never()).insert(any(NursingTask.class));
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("过往日期零生成：当日余下面为空（全部切片早于当前时刻）")
    void pastDateGeneratesNothing() {
        when(wardConfigMapper.selectOne(any())).thenReturn(config(TURN_TEMPLATE));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(inWardPatient()));
        when(taskMapper.selectList(any())).thenReturn(List.of());

        assertThat(generator
                        .generateForWard(WARD, LocalDate.now(ZONE).minusDays(1))
                        .createdTasks())
                .isZero();

        verify(taskMapper, never()).insert(any(NursingTask.class));
    }

    @Test
    @DisplayName("任务号唯一冲突幂等跳过：单行 DuplicateKeyException 不阻断批次、计数不含跳过行")
    void duplicateTaskNoSkippedWithoutBreakingBatch() {
        LocalDate date = LocalDate.now(ZONE).plusDays(2);
        when(wardConfigMapper.selectOne(any())).thenReturn(config(TURN_TEMPLATE));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(inWardPatient()));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("TK")).thenReturn("TK2026100400001");
        // 首片插入撞任务号唯一索引（发号器异常回绕极端并发场景），其余片正常
        when(taskMapper.insert(any(NursingTask.class)))
                .thenThrow(new DuplicateKeyException("uk_nursing_task_no"))
                .thenReturn(1);

        RoutineTaskGenerateVO vo = generator.generateForWard(WARD, date);

        assertThat(vo.createdTasks()).isEqualTo(11);
        verify(taskMapper, org.mockito.Mockito.times(12)).insert(any(NursingTask.class));
    }

    /**
     * 病区配置行替身。
     *
     * @param templates 常规模板 JSON 文本
     * @return 配置行替身，非空
     */
    private NursingWardConfig config(String templates) {
        NursingWardConfig row = new NursingWardConfig();
        row.setWardId(WARD);
        row.setRoutineTaskTemplates(templates);
        return row;
    }

    /**
     * 在区患者投影行替身（deleted=0 即在区——W-34 退役后无 status 列）。
     *
     * @return 投影行替身，非空
     */
    private NursingWardPatient inWardPatient() {
        NursingWardPatient row = new NursingWardPatient();
        row.setWardId(WARD);
        row.setBedNo(BED_NO);
        row.setPatientId(PATIENT_ID);
        row.setVisitId(VISIT);
        return row;
    }

    /**
     * 提取插入行的计划时间 Instant 集（去重断言用）。
     *
     * @param rows 插入行清单，非空
     * @return Instant 集，非空
     */
    private static List<java.time.Instant> rowsPlanTimes(List<NursingTask> rows) {
        return rows.stream().map(row -> row.getPlanTime().toInstant()).collect(Collectors.toList());
    }
}
