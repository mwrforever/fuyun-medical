package com.fuyun.ward.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * M16 病房域错误码接续锚（Global Constraints「WD-1001 起连续无重号」红线的可执行化）：
 * 枚举全集无重号、WD-1001~WD-1008 逐位连续、八项码值逐字冻结、HTTP 状态映射全量覆盖——
 * 中途插码/删码/改号/状态映射漂移任一即红灯，防 ProblemDetail.errorCode 契约漂移破坏
 * 前端提示映射与跨模块排障约定（P2 PR-2 Task 1 骨架落位，随 Task 12 业务实装消费）。
 * EX-19 收口扩段（D-21 断言现代化留痕，2026-09-29）：WD-1007（枚举 code 词表外，A 类六枚举
 * fromCode 收口）与 WD-1008（设备源禁手工入口，B 类）接续顺延，冻结全集 6→8 扩容——
 * 同款扩段断言形态（逐位连续与全量映射严格度不变，仅冻结全集扩容），iot IotErrorCodeTest 先例同构。
 */
class WardErrorCodeTest {

    /** GC12 冻结的 HTTP 状态映射（BizException 抛出点须携带的语义：404 查无/409 状态机与配置冲突/400 入参非法） */
    private static final Map<WardErrorCode, HttpStatus> EXPECTED_HTTP_STATUS = Map.ofEntries(
            Map.entry(WardErrorCode.CALL_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(WardErrorCode.CALL_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(WardErrorCode.CALL_ROUTING_NOT_CONFIGURED, HttpStatus.CONFLICT),
            Map.entry(WardErrorCode.COLD_CHAIN_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(WardErrorCode.COLD_CHAIN_RECORD_INVALID, HttpStatus.BAD_REQUEST),
            Map.entry(WardErrorCode.COLD_CHAIN_STATE_NOT_ALLOWED, HttpStatus.CONFLICT),
            Map.entry(WardErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST),
            Map.entry(WardErrorCode.CALL_SOURCE_IOT_FORBIDDEN, HttpStatus.BAD_REQUEST));

    @Test
    @DisplayName("错误码全集 WD-1001~WD-1008 逐位连续，总数八无重号")
    void errorCodesFollowFrozenSequenceWithoutDuplicates() {
        List<String> codes = Arrays.stream(WardErrorCode.values())
                .map(WardErrorCode::getCode)
                .toList();
        assertThat(codes).as("错误码存在重号").doesNotHaveDuplicates();
        assertThat(codes).as("错误码总数偏离冻结全集（应为 8 条）").hasSize(8);
        for (int i = 0; i < 8; i++) {
            // 逐位连续断言：全部枚举码必须恰为 WD-(1001+i)，插码/跳号/改号任一漂移即红灯
            assertThat(codes.get(i))
                    .as("第 %d 个错误码偏离接续序列（期望 WD-%04d）", i + 1, 1001 + i)
                    .isEqualTo(String.format("WD-%04d", 1001 + i));
        }
    }

    @Test
    @DisplayName("八项错误码名称与码值逐字绑定（GC12 全文，改名即破坏跨模块排障约定）")
    void eachEnumNameCarriesFrozenCodeValue() {
        // 逐字断言：名称↔码值绑定关系冻结，恒定词表供前端提示映射与跨模块排障
        assertThat(WardErrorCode.CALL_NOT_FOUND.getCode()).isEqualTo("WD-1001");
        assertThat(WardErrorCode.CALL_STATE_NOT_ALLOWED.getCode()).isEqualTo("WD-1002");
        assertThat(WardErrorCode.CALL_ROUTING_NOT_CONFIGURED.getCode()).isEqualTo("WD-1003");
        assertThat(WardErrorCode.COLD_CHAIN_NOT_FOUND.getCode()).isEqualTo("WD-1004");
        assertThat(WardErrorCode.COLD_CHAIN_RECORD_INVALID.getCode()).isEqualTo("WD-1005");
        assertThat(WardErrorCode.COLD_CHAIN_STATE_NOT_ALLOWED.getCode()).isEqualTo("WD-1006");
        assertThat(WardErrorCode.ENUM_CODE_INVALID.getCode()).isEqualTo("WD-1007");
        assertThat(WardErrorCode.CALL_SOURCE_IOT_FORBIDDEN.getCode()).isEqualTo("WD-1008");
    }

    @Test
    @DisplayName("HTTP 状态映射全量冻结：覆盖全部枚举常量且取值与 GC12 一致")
    void httpStatusMappingCoversAllCodesExactly() {
        // 全量覆盖断言：映射表大小必须等于枚举常量数——新增码未登记映射（或删码留孤映射）即红灯
        assertThat(EXPECTED_HTTP_STATUS)
                .as("HTTP 状态映射应覆盖全部枚举常量（当前枚举 %d 项）", WardErrorCode.values().length)
                .hasSize(WardErrorCode.values().length);
        // 逐项状态断言：404 查无语义（呼叫/冷链档案）、409 状态机与配置冲突（状态违例/路由未配置）、
        // 400 入参非法（处置记录缺 alarm_ref 或缺第二人、枚举 code 词表外、设备源禁手工入口）
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.CALL_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.CALL_STATE_NOT_ALLOWED))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.CALL_ROUTING_NOT_CONFIGURED))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.COLD_CHAIN_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.COLD_CHAIN_RECORD_INVALID))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.COLD_CHAIN_STATE_NOT_ALLOWED))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.ENUM_CODE_INVALID)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(EXPECTED_HTTP_STATUS.get(WardErrorCode.CALL_SOURCE_IOT_FORBIDDEN))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
