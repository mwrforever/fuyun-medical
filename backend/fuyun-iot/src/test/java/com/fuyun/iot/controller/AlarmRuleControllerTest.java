package com.fuyun.iot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.iot.dto.SimulateAlarmRequest;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.service.IAlarmRuleService;
import com.fuyun.iot.vo.AlarmRuleVO;
import com.fuyun.iot.vo.SimulateResultVO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 告警规则端点薄层冒烟单测（五端点：清单/登记 201/更新/软删 204/模拟；JaCoCo BUNDLE 兜底）：
 * controller 禁业务逻辑与事务（A.1-8），抖动防护②拒保存归服务层（AlarmRuleServiceImplTest
 * 承载）；@Valid 400 经全局渲染器按生产行为出网。
 */
@ExtendWith(MockitoExtension.class)
class AlarmRuleControllerTest {

    private static final long RULE_ID = 900001L;

    @Mock
    private IAlarmRuleService alarmRuleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AlarmRuleController(alarmRuleService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("清单：GET 出网规则视图（类型/级别词表回显）")
    void listReturnsRuleVos() throws Exception {
        when(alarmRuleService.listAll()).thenReturn(List.of(ruleVo()));

        mockMvc.perform(get("/api/v1/iot/alarm-rules").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(RULE_ID))
                .andExpect(jsonPath("$[0].ruleType").value("THRESHOLD"))
                .andExpect(jsonPath("$[0].alarmLevel").value("CRITICAL"));
    }

    @Test
    @DisplayName("登记：POST 返回 201 且名称回显")
    void createReturnsCreatedWithVo() throws Exception {
        when(alarmRuleService.create(any())).thenReturn(ruleVo());

        mockMvc.perform(post("/api/v1/iot/alarm-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleName":"心率过速危急告警","ruleType":"THRESHOLD",
                                 "metricCode":"MDC_ECG_HEART_RATE","compareOp":">","thresholdValue":150,
                                 "durationSecs":30,"recoveryBand":10,"alarmLevel":"CRITICAL"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleName").value("心率过速危急告警"));
        verify(alarmRuleService).create(any());
    }

    @Test
    @DisplayName("登记校验：alarmLevel 缺失 → @Valid 400")
    void createRejectsMissingAlarmLevel() throws Exception {
        mockMvc.perform(post("/api/v1/iot/alarm-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleName":"缺级别","ruleType":"THRESHOLD","metricCode":"MDC_X",
                                 "compareOp":">","thresholdValue":1,"durationSecs":1,"recoveryBand":1}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(alarmRuleService);
    }

    @Test
    @DisplayName("更新：PUT 按路径 id 委派服务")
    void updateDelegatesWithPathVariable() throws Exception {
        when(alarmRuleService.update(eq(RULE_ID), any())).thenReturn(ruleVo());

        mockMvc.perform(put("/api/v1/iot/alarm-rules/{id}", RULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleName":"心率过速危急告警（改）","ruleType":"THRESHOLD",
                                 "metricCode":"MDC_ECG_HEART_RATE","compareOp":">","thresholdValue":150,
                                 "durationSecs":30,"recoveryBand":10,"alarmLevel":"CRITICAL"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RULE_ID))
                .andExpect(jsonPath("$.durationSecs").value(30));
        verify(alarmRuleService).update(eq(RULE_ID), any());
    }

    @Test
    @DisplayName("软删：DELETE 返回 204")
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/iot/alarm-rules/{id}", RULE_ID)).andExpect(status().isNoContent());
        verify(alarmRuleService).delete(RULE_ID);
    }

    @Test
    @DisplayName("模拟：POST /{id}/simulate 返回触发明细（不落库评估面）")
    void simulateReturnsTriggerDetails() throws Exception {
        when(alarmRuleService.simulate(eq(RULE_ID), any(SimulateAlarmRequest.class)))
                .thenReturn(new SimulateResultVO(
                        4,
                        List.of(new SimulateResultVO.SimulateTrigger(
                                "dev-001",
                                "MDC_ECG_HEART_RATE",
                                Instant.parse("2026-09-26T00:00:40Z"),
                                "168",
                                AlarmLevel.CRITICAL))));

        mockMvc.perform(post("/api/v1/iot/alarm-rules/{id}/simulate", RULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"from":"2026-09-26T00:00:00Z","to":"2026-09-26T01:00:00Z"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scannedRows").value(4))
                .andExpect(jsonPath("$.triggers[0].deviceId").value("dev-001"))
                .andExpect(jsonPath("$.triggers[0].alarmLevel").value("CRITICAL"));
    }

    /** 规则视图夹具（V1008 种子行同构） */
    private static AlarmRuleVO ruleVo() {
        return new AlarmRuleVO(
                RULE_ID,
                "心率过速危急告警",
                AlarmRuleType.THRESHOLD,
                null,
                "MDC_ECG_HEART_RATE",
                ThresholdOp.GT,
                new BigDecimal("150"),
                30,
                new BigDecimal("10"),
                300,
                null,
                AlarmLevel.CRITICAL,
                300,
                true,
                null,
                null);
    }
}
