package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.entity.NurseAssignment;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.mapper.NurseAssignmentMapper;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.vo.NurseBoardVO;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 护士站大屏快照服务单测（Task 11 brief Step 1 用例组）：①四段聚合组装（床位墙分配解析
 * BED 优先/PRIMARY 回退/未指派 null、逾期清单 PENDING 过滤映射、出入院时间线 ADMIT/DISCHARGE
 * 合并降序、危急值恒空段+generatedAt）②缓存 read-through 命中直返/穿透回写（TTL 5s）/
 * JSON 损坏与 Redis 读异常降级直算/写异常不阻断 ③危急值空段缺位注记（M07）。MP 3.5.17
 * 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。JaCoCo nursing.service.impl
 * 1.00 行覆盖红线：本类承载 NurseBoardServiceImpl 全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class NurseBoardServiceImplTest {

    /** 病区编码 */
    private static final String WARD = "W01";

    /** 床号墙行数断言基准（三床：管床匹配/责任组回退/未指派） */
    private static final int BED_COUNT = 3;

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    @Mock
    private NurseAssignmentMapper assignmentMapper;

    @Mock
    private NursingTaskMapper taskMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    /** JSON 转换器（Boot 容器同构：findAndRegisterModules 注册 jsr310——OffsetDateTime 序列化前提） */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private NurseBoardServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（投影/分配/任务三面）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardPatient.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NurseAssignment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingTask.class);
    }

    @BeforeEach
    void setUp() {
        service =
                new NurseBoardServiceImpl(wardPatientMapper, assignmentMapper, taskMapper, redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("①四段聚合：床位墙三床分配解析（BED 优先/PRIMARY 回退/未指派 null）+逾期清单映射+出入院合并降序+危急值空段")
    void boardAggregatesFourSegments() {
        // 缓存穿透（缺席直算）
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(NurseBoardServiceImpl.BOARD_SNAPSHOT_KEY_PREFIX + WARD))
                .thenReturn(null);
        // 床位墙行（床号序）——床 01 管床匹配、床 02 责任组按患者回退、床 03 未指派
        OffsetDateTime admittedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        when(wardPatientMapper.selectList(any()))
                .thenReturn(List.of(
                        wardPatient("01", "I2026100200001", 7001L, admittedAt, "FALL"),
                        wardPatient("02", "I2026100200002", 7002L, admittedAt.plusMinutes(10), ""),
                        wardPatient("03", "I2026100200003", 7003L, admittedAt.plusMinutes(20), null)))
                // 出入院动态第二查：近 24h 入科行（与床位墙同源窗口）
                .thenReturn(List.of(wardPatient("01", "I2026100200001", 7001L, admittedAt, null)));
        when(assignmentMapper.selectList(any()))
                .thenReturn(List.of(bedAssignment("01", "N1"), primaryAssignment(7002L, "N2")));
        when(taskMapper.selectList(any()))
                .thenReturn(List.of(
                        overdueTask("TK2026100200001", "TURN", 1), overdueTask("TK2026100200002", "PATROL", 2)));
        // 出院行（逻辑删面）：updated_at 近似出院时点
        NursingWardPatient discharged = wardPatient("02", "I2026100200002", 7002L, admittedAt.plusMinutes(10), null);
        discharged.setUpdatedAt(admittedAt.plusMinutes(30));
        when(wardPatientMapper.selectDischargedSince(eq(WARD), any(OffsetDateTime.class)))
                .thenReturn(List.of(discharged));

        NurseBoardVO board = service.board(WARD);

        // 段一床位墙：三行 + 分配解析三态 + 风险标记镜像 + 护理级别
        assertThat(board.beds()).hasSize(BED_COUNT);
        assertThat(board.beds().get(0).assigneeName()).as("BED 型管床匹配优先").isEqualTo("N1");
        assertThat(board.beds().get(1).assigneeName()).as("PRIMARY 型责任组按患者回退").isEqualTo("N2");
        assertThat(board.beds().get(2).assigneeName()).as("未指派为 null").isNull();
        assertThat(board.beds().get(0).riskFlags()).isEqualTo("FALL");
        assertThat(board.beds().get(0).nursingLevel()).isEqualTo("NORMAL");
        // 段二逾期清单：taskNo/taskType/planTime/escalationCount 四组件映射
        assertThat(board.overdueTasks()).hasSize(2);
        assertThat(board.overdueTasks().get(0).taskNo()).isEqualTo("TK2026100200001");
        assertThat(board.overdueTasks().get(1).escalationCount()).isEqualTo(2);
        // 段三出入院：ADMIT+DISCHARGE 合并，时点降序（出院 updated_at 晚于入科 admitted_at 居首）
        assertThat(board.admissions()).hasSize(2);
        assertThat(board.admissions().get(0).type()).isEqualTo(NurseBoardVO.TYPE_DISCHARGE);
        assertThat(board.admissions().get(1).type()).isEqualTo(NurseBoardVO.TYPE_ADMIT);
        // 段四危急值：固定空数组（M07 缺位降级明示）+ 快照元数据
        assertThat(board.criticalValues()).as("危急值段固定空数组（M07 缺位注记）").isEmpty();
        assertThat(board.wardId()).isEqualTo(WARD);
        assertThat(board.generatedAt()).isNotNull();
        // 缓存穿透回写：String JSON + 显式 TTL 5s（GC13 禁无 TTL 键红线）
        verify(valueOperations)
                .set(
                        eq(NurseBoardServiceImpl.BOARD_SNAPSHOT_KEY_PREFIX + WARD),
                        anyString(),
                        eq(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("②缓存命中直返：反序列化快照原样返回且零触库（read-through 命中面）")
    void boardCacheHitReturnsCachedSnapshotWithoutDbAccess() throws Exception {
        NurseBoardVO cached = new NurseBoardVO(
                WARD,
                List.of(new NurseBoardVO.BedRow("01", "I2026100200001", 7001L, "NORMAL", null, "N1", "")),
                List.of(),
                List.of(),
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(NurseBoardServiceImpl.BOARD_SNAPSHOT_KEY_PREFIX + WARD))
                .thenReturn(objectMapper.writeValueAsString(cached));

        NurseBoardVO board = service.board(WARD);

        assertThat(board.beds()).hasSize(1);
        assertThat(board.beds().get(0).assigneeName()).isEqualTo("N1");
        // 命中面零触库：三 mapper 均不交互
        verifyNoInteractions(wardPatientMapper, assignmentMapper, taskMapper);
    }

    @Test
    @DisplayName("②缓存 JSON 损坏：warn 降级直算覆盖（缓存可重建不阻断聚合主链）")
    void boardCorruptedCacheFallsBackToDirectAggregation() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(NurseBoardServiceImpl.BOARD_SNAPSHOT_KEY_PREFIX + WARD))
                .thenReturn("{bad-json");
        // 空病区面：直算返回零行四段（床位墙空清单早退——分配面不触达）
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(wardPatientMapper.selectDischargedSince(eq(WARD), any(OffsetDateTime.class)))
                .thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of());

        NurseBoardVO board = service.board(WARD);

        assertThat(board.beds()).isEmpty();
        assertThat(board.criticalValues()).isEmpty();
        verify(assignmentMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    @DisplayName("②Redis 读异常：降级直算（缓存面缺席不阻断）+ 写异常不阻断出参")
    void boardRedisFailuresDegradeWithoutBlocking() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 读路径异常：直算承接
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("连接失败"));
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(wardPatientMapper.selectDischargedSince(eq(WARD), any(OffsetDateTime.class)))
                .thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of());
        // 写路径异常：不阻断（快照照常出参）
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("写失败"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        NurseBoardVO board = service.board(WARD);

        assertThat(board).isNotNull();
        assertThat(board.wardId()).isEqualTo(WARD);
    }

    @Test
    @DisplayName("③wardId 空白守卫：NS-1019 400（快照定位键缺失显式拒绝）")
    void boardRejectsBlankWardId() {
        assertThatThrownBy(() -> service.board(" "))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(wardPatientMapper, taskMapper, redisTemplate);
    }

    // ===================== 测试数据与断言辅助 =====================

    /**
     * 在册投影行替身（床位墙/入科动态两段共用）。
     *
     * @param bedNo      床号
     * @param visitId    住院就诊号
     * @param patientId  患者主索引
     * @param admittedAt 入科时点
     * @param riskFlags  风险标记（可空）
     * @return 投影行替身，非空
     */
    private static NursingWardPatient wardPatient(
            String bedNo, String visitId, long patientId, OffsetDateTime admittedAt, String riskFlags) {
        NursingWardPatient row = new NursingWardPatient();
        row.setWardId(WARD);
        row.setBedNo(bedNo);
        row.setVisitId(visitId);
        row.setPatientId(patientId);
        row.setNursingLevel("NORMAL");
        row.setRiskFlags(riskFlags);
        row.setAdmittedAt(admittedAt);
        return row;
    }

    /**
     * BED 型管床分配替身（床位维度匹配锚）。
     *
     * @param bedNo 床号
     * @param nurseId 护士标识
     * @return 分配行替身，非空
     */
    private static NurseAssignment bedAssignment(String bedNo, String nurseId) {
        NurseAssignment row = new NurseAssignment();
        row.setWardId(WARD);
        row.setNurseId(nurseId);
        row.setAssignmentType("BED");
        row.setShiftCode("DAY");
        row.setBedNo(bedNo);
        row.setStatus("ACTIVE");
        return row;
    }

    /**
     * PRIMARY 型责任组分配替身（患者维度回退锚）。
     *
     * @param patientId 责任患者
     * @param nurseId   护士标识
     * @return 分配行替身，非空
     */
    private static NurseAssignment primaryAssignment(long patientId, String nurseId) {
        NurseAssignment row = new NurseAssignment();
        row.setWardId(WARD);
        row.setNurseId(nurseId);
        row.setAssignmentType("PRIMARY");
        row.setShiftCode("DAY");
        row.setPatientId(patientId);
        row.setStatus("ACTIVE");
        return row;
    }

    /**
     * 逾期在途任务替身（PENDING+overdue_flag=true——board 段过滤键）。
     *
     * @param taskNo 任务号
     * @param taskType 任务类型
     * @param escalationCount 升级次数
     * @return 任务行替身，非空
     */
    private static NursingTask overdueTask(String taskNo, String taskType, int escalationCount) {
        NursingTask row = new NursingTask();
        row.setTaskNo(taskNo);
        row.setWardId(WARD);
        row.setTaskType(taskType);
        row.setStatus(TaskStatus.PENDING.getCode());
        row.setOverdueFlag(true);
        row.setEscalationCount(escalationCount);
        row.setPlanTime(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(45));
        return row;
    }
}
