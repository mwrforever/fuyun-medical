package com.fuyun.outpatient.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/**
 * 门诊模块枚举 fromCode 词表外失败语义单测（EX-19 A/C 类收口锚定用例集，BE-C3-05 双层错误模型）：
 * A 类——code 来源含 API 入参/外部入参的 8 枚举，词表外由裸 IAE（全局渲染 500 无业务码）收口为
 * BizException OP-1019（400）显式拒绝（对齐 BUG-05 词表守卫先例）；C 类——code 来源纯 DB 列读取的
 * 6 枚举保留 IAE（脏数据属数据异常 500 语义，非用户输入路径，零行为变化）。
 */
class OutpatientEnumFromCodeTest {

    /**
     * A 类枚举参数源：业务名 + fromCode 转换函数 + 词表内代表值 + 词表外探针值。
     *
     * @return 8 组参数（ApptChannel/ApptType/PoolStatus/ScheduleStatus/SessionType/OrderType/
     *         TicketStatus/TriageAction）
     */
    static Stream<Arguments> apiFacingEnums() {
        return Stream.of(
                Arguments.of("预约渠道", (Function<String, ?>) ApptChannel::fromCode, ApptChannel.WINDOW, "FAX"),
                Arguments.of("号别", (Function<String, ?>) ApptType::fromCode, ApptType.GENERAL, "VIP"),
                Arguments.of("号源池状态", (Function<String, ?>) PoolStatus::fromCode, PoolStatus.ACTIVE, "FROZEN"),
                Arguments.of("排班状态", (Function<String, ?>) ScheduleStatus::fromCode, ScheduleStatus.NORMAL, "PAUSED"),
                Arguments.of("门诊时段", (Function<String, ?>) SessionType::fromCode, SessionType.MORNING, "MIDNIGHT"),
                Arguments.of("申请单类型", (Function<String, ?>) OrderType::fromCode, OrderType.EXAM, "MYSTERY"),
                Arguments.of("候诊票据状态", (Function<String, ?>) TicketStatus::fromCode, TicketStatus.WAITING, "LOST"),
                Arguments.of("分诊动作", (Function<String, ?>) TriageAction::fromCode, TriageAction.CHECK_IN, "MAGIC"));
    }

    /**
     * C 类枚举参数源：业务名 + fromCode 转换函数 + 词表内代表值 + 脏数据探针值。
     *
     * @return 6 组参数（ApptStatus/FeeStatusType/OrderStatus/TicketType/VisitStatus/VisitType）
     */
    static Stream<Arguments> dbFacingEnums() {
        return Stream.of(
                Arguments.of("预约单状态", (Function<String, ?>) ApptStatus::fromCode, ApptStatus.RESERVED, "__DIRTY__"),
                Arguments.of("挂号费状态", (Function<String, ?>) FeeStatusType::fromCode, FeeStatusType.UNPAID, "__DIRTY__"),
                Arguments.of("申请单状态", (Function<String, ?>) OrderStatus::fromCode, OrderStatus.CREATED, "__DIRTY__"),
                Arguments.of("候诊票别", (Function<String, ?>) TicketType::fromCode, TicketType.FIRST, "__DIRTY__"),
                Arguments.of("就诊状态", (Function<String, ?>) VisitStatus::fromCode, VisitStatus.REGISTERED, "__DIRTY__"),
                Arguments.of("就诊类型", (Function<String, ?>) VisitType::fromCode, VisitType.GENERAL, "__DIRTY__"));
    }

    @ParameterizedTest(name = "[{index}] {0}：合法 code 命中枚举常量")
    @MethodSource("apiFacingEnums")
    @DisplayName("A 类收口：API 入参语义枚举词表内 code——枚举常量精确命中")
    void apiFacingEnumMapsValidCode(String bizName, Function<String, ?> fromCode, Enum<?> valid, String invalidCode) {
        assertThat(fromCode.apply(valid.name())).isEqualTo(valid);
    }

    @ParameterizedTest(name = "[{index}] {0}：词表外 {3} 拒 OP-1019（400）")
    @MethodSource("apiFacingEnums")
    @DisplayName("A 类收口：API 入参语义枚举词表外——BizException OP-1019（400）显式拒绝，消息携带违例值")
    void apiFacingEnumRejectsOutOfVocabularyCode(
            String bizName, Function<String, ?> fromCode, Enum<?> valid, String invalidCode) {
        assertThatThrownBy(() -> fromCode.apply(invalidCode)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains(invalidCode);
        });
    }

    @ParameterizedTest(name = "[{index}] {0}：合法 code 命中枚举常量")
    @MethodSource("dbFacingEnums")
    @DisplayName("C 类收口：DB 列读取语义枚举词表内 code——枚举常量精确命中")
    void dbFacingEnumMapsValidCode(String bizName, Function<String, ?> fromCode, Enum<?> valid, String dirtyCode) {
        assertThat(fromCode.apply(valid.name())).isEqualTo(valid);
    }

    @ParameterizedTest(name = "[{index}] {0}：词表外 {3} 抛 IAE（数据异常 500 语义保留）")
    @MethodSource("dbFacingEnums")
    @DisplayName("C 类收口：DB 列读取语义枚举词表外——保留 IAE（脏数据属数据异常，非用户输入路径）")
    void dbFacingEnumKeepsIaeOnDirtyCode(
            String bizName, Function<String, ?> fromCode, Enum<?> valid, String dirtyCode) {
        assertThatThrownBy(() -> fromCode.apply(dirtyCode)).isInstanceOf(IllegalArgumentException.class);
    }
}
