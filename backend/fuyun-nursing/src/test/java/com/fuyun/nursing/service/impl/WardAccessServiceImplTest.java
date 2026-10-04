package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.constants.NursingSecurityConstants;
import com.fuyun.nursing.entity.NurseAssignment;
import com.fuyun.nursing.mapper.NurseAssignmentMapper;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
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

/**
 * 病区归属校验服务单测（PR-4C Task 4，W-40 方案 A）：①当班绑定集窗口谓词锚（nurse_id/status
 * 等值 + valid_from<=北京当日<=valid_to 双边窗（valid_to NULL 长期行入窗、过期行由谓词滤除）
 * + ward_id 精确投影 + 去重）②REST 守卫面（集内放行/集外 403 NS-1028/无绑定 fail-closed/
 * 上下文缺失 403）③哨兵豁免（防御纵深，零 mapper 触达）④显式传参版与 ThreadLocal 版语义
 * 等价（WS broker 线程消费面）。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工
 * 注册表信息。JaCoCo nursing.service.impl 1.00 行覆盖红线：本类承载 WardAccessServiceImpl
 * 全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class WardAccessServiceImplTest {

    /** 普通操作者标识（当班绑定 W01/W02 的护士 userId 十进制串） */
    private static final String OPERATOR = "1";

    /** 大屏哨兵操作者标识（NursingSecurityConstants 镜像，与 system 侧同源约定） */
    private static final String SENTINEL = NursingSecurityConstants.BIGSCREEN_SENTINEL_OPERATOR_ID;

    @Mock
    private NurseAssignmentMapper assignmentMapper;

    @Captor
    private ArgumentCaptor<Wrapper<NurseAssignment>> queryCaptor;

    private WardAccessServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（条件锚渲染前提）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NurseAssignment.class);
    }

    @BeforeEach
    void setUp() {
        // 上下文空用例依赖干净起点：前置清理防前序用例残留串号
        OperatorContextHolder.clear();
        service = new WardAccessServiceImpl(assignmentMapper);
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 清理防线：操作者上下文不跨用例泄漏（OperatorContextHolder 契约）
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("①集内放行：当班绑定集含目标病区即通过")
    void assertWardAllowedPassesWhenWardInActiveBindings() {
        OperatorContextHolder.set(OPERATOR);
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment("W01"), assignment("W02")));
        assertThatCode(() -> service.assertWardAllowed("W01")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("②集外拒：有绑定但目标病区越界 → 403 NS-1028")
    void assertWardAllowedRejectsWardOutsideBindings() {
        OperatorContextHolder.set(OPERATOR);
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment("W01")));
        assertThatThrownBy(() -> service.assertWardAllowed("W03"))
                .isInstanceOf(BizException.class)
                .satisfies(ex ->
                        assertThat(((BizException) ex).getErrorCode().getCode()).isEqualTo("NS-1028"))
                .satisfies(ex -> assertThat(((BizException) ex).getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("③fail-closed：无 ACTIVE 绑定行一律 403（ADMIN 亦无豁免——D-29）")
    void assertWardAllowedFailsClosedWhenNoActiveBinding() {
        OperatorContextHolder.set(OPERATOR);
        when(assignmentMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.assertWardAllowed("W01"))
                .isInstanceOf(BizException.class)
                .satisfies(ex ->
                        assertThat(((BizException) ex).getErrorCode().getCode()).isEqualTo("NS-1028"));
    }

    @Test
    @DisplayName("④哨兵豁免：大屏哨兵操作者直通过任意病区且零 mapper 触达（HTTP 层已限行——防御纵深）")
    void assertWardAllowedExemptsSentinelOperator() {
        OperatorContextHolder.set(SENTINEL);
        assertThatCode(() -> service.assertWardAllowed("1001")).doesNotThrowAnyException();
        verifyNoInteractions(assignmentMapper);
    }

    @Test
    @DisplayName("⑤上下文缺失拒：ThreadLocal 无操作者（null/空白）一律 403（fail-closed）")
    void assertWardAllowedRejectsWhenContextMissing() {
        assertThatThrownBy(() -> service.assertWardAllowed("W01"))
                .isInstanceOf(BizException.class)
                .satisfies(ex ->
                        assertThat(((BizException) ex).getErrorCode().getCode()).isEqualTo("NS-1028"));
        // 空白串同语义：身份形同缺失不放行（不回退查询全量绑定）
        OperatorContextHolder.set("   ");
        assertThatThrownBy(() -> service.assertWardAllowed("W01")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("⑥窗口谓词锚：nurse_id/status 等值+北京当日双边窗（同日承载两边界谓词）+ward_id 精确投影+去重")
    void activeBoundWardIdsAnchorsValidWindowPredicates() {
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment("W01"), assignment("W01")));

        List<String> wards = service.activeBoundWardIds(OPERATOR);

        // 重复绑定行不放大集合（病区集去重）
        assertThat(wards).containsExactly("W01");
        verify(assignmentMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<NurseAssignment> wrapper = rendered(queryCaptor.getValue());
        // 谓词根因锚：等值条件（操作者/ACTIVE 态）入参；当日窗口取北京钟面医疗日
        LocalDate today = LocalDate.now(TimeConstants.HEALTHCARE_TZ);
        assertThat(wrapper.getParamNameValuePairs().values()).contains(OPERATOR, "ACTIVE", today);
        // 同一医疗日承载两个边界谓词（valid_from<=当日 与 valid_to>=当日），裸 now() 漂移即失配
        assertThat(Collections.frequency(wrapper.getParamNameValuePairs().values(), today))
                .isEqualTo(2);
        // valid_to IS NULL OR valid_to >= 当日：NULL 长期行入窗、过期行不进集由该谓词承载
        assertThat(wrapper.getSqlSegment())
                .contains("nurse_id =")
                .contains("status =")
                .contains("valid_from <=")
                .contains("valid_to IS NULL")
                .contains("valid_to >=");
        // 精确投影锚：仅取 ward_id 列（backend 宪法 A.4.3-14）
        assertThat(wrapper.getSqlSelect()).contains("ward_id");
    }

    @Test
    @DisplayName("⑦显式传参版等价：无 ThreadLocal 亦同放同拒，与 ThreadLocal 版语义一致")
    void assertWardAllowedForMatchesThreadLocalSemantics() {
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment("W01")));
        // WS broker 线程形态：无 ThreadLocal 上下文，显式传参照常校验（集内过/集外 403 NS-1028）
        assertThatCode(() -> service.assertWardAllowedFor(OPERATOR, "W01")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.assertWardAllowedFor(OPERATOR, "W03"))
                .isInstanceOf(BizException.class)
                .satisfies(ex ->
                        assertThat(((BizException) ex).getErrorCode().getCode()).isEqualTo("NS-1028"));
        // 等价锚：ThreadLocal 版=显式版取上下文后直通（同绑定集同判定）
        OperatorContextHolder.set(OPERATOR);
        assertThatCode(() -> service.assertWardAllowed("W01")).doesNotThrowAnyException();
        // 显式版身份缺失同 fail-closed；哨兵显式传参同豁免
        assertThatThrownBy(() -> service.assertWardAllowedFor(null, "W01")).isInstanceOf(BizException.class);
        assertThatCode(() -> service.assertWardAllowedFor(SENTINEL, "1001")).doesNotThrowAnyException();
    }

    /** 造当班绑定行（ACTIVE 态、窗口覆盖北京当日，nurse_id=操作者——六用例共用底座）。 */
    private NurseAssignment assignment(String wardId) {
        NurseAssignment row = new NurseAssignment();
        row.setWardId(wardId);
        row.setNurseId(OPERATOR);
        row.setStatus("ACTIVE");
        row.setValidFrom(LocalDate.now(TimeConstants.HEALTHCARE_TZ).minusDays(1));
        row.setValidTo(LocalDate.now(TimeConstants.HEALTHCARE_TZ).plusDays(1));
        return row;
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    private LambdaQueryWrapper<NurseAssignment> rendered(Wrapper<NurseAssignment> captured) {
        LambdaQueryWrapper<NurseAssignment> wrapper = (LambdaQueryWrapper<NurseAssignment>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }
}
