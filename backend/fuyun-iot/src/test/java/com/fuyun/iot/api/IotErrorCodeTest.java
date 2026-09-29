package com.fuyun.iot.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * M14 模块错误码枚举契约测试（backend 宪法 A.3-4：格式 {@code <模块助记>-<4位数字>}，全项目唯一）。
 * P2 PR-2 Task 1 随 GC12 骨架扩段同步扩断言：IOT-1001~IOT-1022 逐位连续 + HTTP 状态映射全量
 * 精确匹配（新契约全量冻结，严格度不降）；Task 11 网关管理域三码接续顺延扩至 IOT-1025
 * （同款扩段断言形态——逐位连续与全量映射严格度不变，仅冻结全集扩容）；EX-19 词表外收口
 * 接续顺延扩至 IOT-1026（BE-C3-05，严格度不变仅冻结全集扩容）。
 */
class IotErrorCodeTest {

    /** GC12 冻结的 HTTP 状态映射（BizException 抛出点须携带的语义：401 鉴权失败/404 查无/409 状态冲突/400 入参非法/503 管理面不可达） */
    private static final Map<IotErrorCode, HttpStatus> EXPECTED_HTTP_STATUS = Map.ofEntries(
            Map.entry(IotErrorCode.FALLBACK_AUTH_FAILED, HttpStatus.UNAUTHORIZED),
            Map.entry(IotErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.PRODUCT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.METRIC_DICT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.METRIC_MAPPING_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.DEVICE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.DEVICE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.DEVICE_ALREADY_EXISTS, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.BINDING_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.BINDING_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.BINDING_CHECK_INVALID, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.ALARM_RULE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.ALARM_RULE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.COMMAND_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.COMMAND_CONFIRM_INVALID, HttpStatus.BAD_REQUEST),
            Map.entry(IotErrorCode.COMMAND_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.LINKAGE_RULE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.LINKAGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.TELEMETRY_QUERY_INVALID, HttpStatus.BAD_REQUEST),
            Map.entry(IotErrorCode.CONSUME_ERROR_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.REGISTRY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(IotErrorCode.GATEWAY_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(IotErrorCode.GATEWAY_ALREADY_EXISTS, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.GATEWAY_STANDBY_INVALID, HttpStatus.CONFLICT),
            Map.entry(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST));

    @Test
    @DisplayName("全部错误码格式为 IOT-4位数字且枚举内无重复")
    void allCodesFollowModuleFormatAndAreUnique() {
        Set<String> seen = new HashSet<>();
        for (IotErrorCode errorCode : IotErrorCode.values()) {
            // IOT- 前缀 + 4 位数字（模块助记 IOT 归 M14 独占，当前全项目无其他占用）
            assertThat(errorCode.getCode())
                    .as("错误码 %s 应符合 IOT-xxxx 格式", errorCode)
                    .matches("IOT-\\d{4}");
            // 枚举内唯一性（全项目唯一由评审与号段台账保障，此处守住模块内不撞号）
            assertThat(seen.add(errorCode.getCode()))
                    .as("错误码 %s 在枚举内应唯一", errorCode.getCode())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("兜底通道鉴权失败码实现 common ErrorCode 契约且声明值与 getCode 一致")
    void fallbackAuthCodeImplementsCommonContract() {
        // 对外契约：BizException 携带本枚举，经全局渲染输出 ProblemDetail.properties.errorCode（B4.3 兜底端点使用）
        assertThat(IotErrorCode.FALLBACK_AUTH_FAILED.getCode()).isEqualTo("IOT-1001");
    }

    @Test
    @DisplayName("错误码全集 IOT-1001~IOT-1026 逐位连续，总数二十六无重号（GC12 扩段 + Task 11 网关域 + EX-19 词表外收口顺延）")
    void errorCodesFollowFrozenSequenceWithoutDuplicates() {
        List<String> codes =
                Arrays.stream(IotErrorCode.values()).map(IotErrorCode::getCode).toList();
        assertThat(codes).as("错误码存在重号").doesNotHaveDuplicates();
        assertThat(codes).as("错误码总数偏离冻结全集（应为 26 条）").hasSize(26);
        for (int i = 0; i < 26; i++) {
            // 逐位连续断言：全部枚举码必须恰为 IOT-(1001+i)，插码/跳号/改号任一漂移即红灯
            assertThat(codes.get(i))
                    .as("第 %d 个错误码偏离接续序列（期望 IOT-%04d）", i + 1, 1001 + i)
                    .isEqualTo(String.format("IOT-%04d", 1001 + i));
        }
    }

    @Test
    @DisplayName("HTTP 状态映射全量冻结：覆盖全部枚举常量且取值与 GC12 一致")
    void httpStatusMappingCoversAllCodesExactly() {
        // 全量覆盖断言：映射表大小必须等于枚举常量数——新增码未登记映射（或删码留孤映射）即红灯
        assertThat(EXPECTED_HTTP_STATUS)
                .as("HTTP 状态映射应覆盖全部枚举常量（当前枚举 %d 项）", IotErrorCode.values().length)
                .hasSize(IotErrorCode.values().length);
        // 关键语义锚逐项断言（其余项由映射表数据冻结）：401 兜底鉴权、400 入参面、503 管理面不可达
        assertThat(EXPECTED_HTTP_STATUS.get(IotErrorCode.FALLBACK_AUTH_FAILED)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(EXPECTED_HTTP_STATUS.get(IotErrorCode.COMMAND_CONFIRM_INVALID))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(EXPECTED_HTTP_STATUS.get(IotErrorCode.TELEMETRY_QUERY_INVALID))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(EXPECTED_HTTP_STATUS.get(IotErrorCode.ENUM_CODE_INVALID)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(EXPECTED_HTTP_STATUS.get(IotErrorCode.REGISTRY_UNAVAILABLE))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
