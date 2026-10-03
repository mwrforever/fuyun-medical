package com.fuyun.nursing.service.impl;

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
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.NursingTaskLinkagePort;
import com.fuyun.nursing.api.NursingTaskLinkageRequest;
import com.fuyun.nursing.api.NursingTaskLinkageResult;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.vo.NursingTaskVO;
import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatus;

/**
 * 联动任务创建端口单测（P2 PR-3 Task 12 Step 1 TDD）：IOT_LINKAGE 幂等创建三路——首建
 * created=true（source=IOT_LINKAGE + source_ref=linkageNo 幂等锚经 create 入参透传断言）、
 * 同 linkageNo 回查 created=false（零 create 触达）、底层 create 的 BizException 原样透传
 * （iot 侧据此走失败重试路）；参数缺失防御 NS-1019。回查谓词根因锚（source+source_ref 双键）
 * 经 wrapper 参数表断言。真实 SQL 行为归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
class NursingTaskLinkagePortImplTest {

    /** 联动执行业务号（幂等锚，iot LinkageExecutor 取号产物） */
    private static final String LINKAGE_NO = "LG2026100200001";

    /** 首建返回的任务号（发号器桩固定值） */
    private static final String TASK_NO = "TK2026100200001";

    /** 回查命中的既有任务号（幂等重放载体） */
    private static final String EXISTING_TASK_NO = "TK2026093000007";

    /** I 型 14 位合法 visit_id */
    private static final String VISIT = "I2026092200001";

    @Mock
    private INursingTaskService taskService;

    @Captor
    private ArgumentCaptor<Wrapper<NursingTask>> queryCaptor;

    @Captor
    private ArgumentCaptor<NursingTaskCreateRequest> createCaptor;

    private NursingTaskLinkagePort port;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambda 条件的列名解析依赖 TableInfo（容器外手工注册一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingTask.class);
    }

    @BeforeEach
    void setUp() {
        port = new NursingTaskLinkagePortImpl(taskService);
    }

    @Test
    @DisplayName("首建：回查零命中 → 复用 create 创建（source=IOT_LINKAGE + sourceRef=linkageNo 幂等锚），created=true")
    void firstCreationReturnsCreatedTrueWithIdempotencyAnchors() {
        when(taskService.list(org.mockito.ArgumentMatchers.<Wrapper<NursingTask>>any()))
                .thenReturn(List.of());
        when(taskService.create(any())).thenReturn(vo(TASK_NO));

        NursingTaskLinkageResult result = port.createTask(request());

        assertThat(result.taskNo()).isEqualTo(TASK_NO);
        assertThat(result.created()).isTrue();
        // 幂等锚断言：source 固定 IOT_LINKAGE（端口语义，调用方不可伪造他源）、sourceRef=linkageNo
        verify(taskService).create(createCaptor.capture());
        NursingTaskCreateRequest captured = createCaptor.getValue();
        assertThat(captured.source()).isEqualTo(TaskSource.IOT_LINKAGE.getCode());
        assertThat(captured.sourceRef()).isEqualTo(LINKAGE_NO);
        assertThat(captured.patientId()).isEqualTo(7L);
        assertThat(captured.visitId()).isEqualTo(VISIT);
        assertThat(captured.wardId()).isEqualTo("W01");
        assertThat(captured.taskType()).isEqualTo(TaskType.IOT_LINKAGE.getCode());
        assertThat(captured.planTime()).isNotNull();
    }

    @Test
    @DisplayName("幂等回查谓词：按 source=IOT_LINKAGE + source_ref=linkageNo 双键查行（deleted=0 由 @TableLogic 自动携带）")
    void lookupQueriesByIdempotencyKeyPair() {
        when(taskService.list(org.mockito.ArgumentMatchers.<Wrapper<NursingTask>>any()))
                .thenReturn(List.of());
        when(taskService.create(any())).thenReturn(vo(TASK_NO));

        port.createTask(request());

        verify(taskService).list(queryCaptor.capture());
        LambdaQueryWrapper<NursingTask> wrapper = rendered(queryCaptor.getValue());
        // 谓词根因锚：source + source_ref 双键（幂等回查唯一依据，缺一键即幂等失效）
        assertThat(wrapper.getParamNameValuePairs().values()).contains(TaskSource.IOT_LINKAGE.getCode(), LINKAGE_NO);
    }

    @Test
    @DisplayName("同 linkageNo 回查：命中既有行返回原 taskNo created=false，零 create 触达（不重复建任务不发事件）")
    void replayReturnsExistingTaskNoWithCreatedFalse() {
        NursingTask existing = new NursingTask();
        existing.setTaskNo(EXISTING_TASK_NO);
        existing.setSource(TaskSource.IOT_LINKAGE.getCode());
        existing.setSourceRef(LINKAGE_NO);
        when(taskService.list(org.mockito.ArgumentMatchers.<Wrapper<NursingTask>>any()))
                .thenReturn(List.of(existing));

        NursingTaskLinkageResult result = port.createTask(request());

        assertThat(result.taskNo()).isEqualTo(EXISTING_TASK_NO);
        assertThat(result.created()).isFalse();
        verify(taskService, never()).create(any());
    }

    @Test
    @DisplayName("BizException 透传：底层 create 业务拒绝（如 NS-1016 补录越窗）原样上抛——iot 侧走失败重试路")
    void propagatesBizExceptionFromUnderlyingCreate() {
        when(taskService.list(org.mockito.ArgumentMatchers.<Wrapper<NursingTask>>any()))
                .thenReturn(List.of());
        when(taskService.create(any()))
                .thenThrow(new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "计划时间补录越窗（测试桩）"));

        assertThatThrownBy(() -> port.createTask(request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("补录越窗");
    }

    @Test
    @DisplayName("参数缺失防御：linkageNo/patientId/visitId/wardId 任一缺失拒 NS-1019，零服务触达（DB NOT NULL 前置拦截）")
    void rejectsMissingIdempotencyAndAnchorParams() {
        // 逐字段置空断言：消息定位缺失字段名，方便联动链路排障
        assertRejects(
                new NursingTaskLinkageRequest(
                        null, "W01", 7L, VISIT, TaskType.IOT_LINKAGE.getCode(), null, OffsetDateTime.now()),
                "linkageNo");
        assertRejects(
                new NursingTaskLinkageRequest(
                        LINKAGE_NO, "W01", null, VISIT, TaskType.IOT_LINKAGE.getCode(), null, OffsetDateTime.now()),
                "patientId");
        assertRejects(
                new NursingTaskLinkageRequest(
                        LINKAGE_NO, "W01", 7L, null, TaskType.IOT_LINKAGE.getCode(), null, OffsetDateTime.now()),
                "visitId");
        assertRejects(
                new NursingTaskLinkageRequest(
                        LINKAGE_NO, null, 7L, VISIT, TaskType.IOT_LINKAGE.getCode(), null, OffsetDateTime.now()),
                "wardId");
        verifyNoInteractions(taskService);
    }

    /** 缺参断言辅助：断言 NS-1019 且消息定位缺失字段。 */
    private void assertRejects(NursingTaskLinkageRequest mutated, String missingField) {
        assertThatThrownBy(() -> port.createTask(mutated)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains(missingField);
        });
    }

    /** 标准联动请求替身（iot LinkageExecutor 同构入参：IOT_LINKAGE 型即联即办任务）。 */
    private static NursingTaskLinkageRequest request() {
        return new NursingTaskLinkageRequest(
                LINKAGE_NO, "W01", 7L, VISIT, TaskType.IOT_LINKAGE.getCode(), "输液告急联动确认", OffsetDateTime.now());
    }

    /** create 出参替身（首建 PENDING 态最小载体）。 */
    private static NursingTaskVO vo(String taskNo) {
        NursingTask row = new NursingTask();
        row.setTaskNo(taskNo);
        row.setSource(TaskSource.IOT_LINKAGE.getCode());
        row.setSourceRef(LINKAGE_NO);
        return NursingTaskVO.from(row);
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<NursingTask> rendered(Wrapper<NursingTask> captured) {
        LambdaQueryWrapper<NursingTask> wrapper = (LambdaQueryWrapper<NursingTask>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }
}
