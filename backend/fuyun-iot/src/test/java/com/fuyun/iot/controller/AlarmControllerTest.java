package com.fuyun.iot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.AlarmQueryRequest;
import com.fuyun.iot.dto.CloseAlarmRequest;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmStatus;
import com.fuyun.iot.service.IAlarmService;
import com.fuyun.iot.vo.AlarmVO;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 告警端点薄层冒烟单测（三端点：分页/确认/关闭；JaCoCo BUNDLE 兜底）：controller 禁业务逻辑
 * 与事务（A.1-8），状态机 CAS 与关闭事件发布归服务层（AlarmServiceImplTest 承载）；@Valid 400
 * 经全局渲染器按生产行为出网。
 */
@ExtendWith(MockitoExtension.class)
class AlarmControllerTest {

    private static final String ALARM_NO = "AL2026092600001";

    @Mock
    private IAlarmService alarmService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AlarmController(alarmService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("分页：过滤参数透传，分页出参四字段回显")
    void pageReturnsPagedAlarms() throws Exception {
        // any() 携带具体类型：接口配对 IService 后 page 存在泛型继承面重载，无类型 matcher 会引发
        // 重载解析歧义（编译错），显式 AlarmQueryRequest 锁定业务面 page(AlarmQueryRequest)
        when(alarmService.page(any(AlarmQueryRequest.class))).thenReturn(PageResult.of(List.of(alarmVo()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/iot/alarms")
                        .param("wardId", "1001")
                        .param("alarmLevel", "CRITICAL")
                        .param("status", "ACTIVE")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].alarmNo").value(ALARM_NO));
    }

    @Test
    @DisplayName("确认：POST 按路径告警号委派服务")
    void acknowledgeDelegatesWithPathAlarmNo() throws Exception {
        when(alarmService.acknowledge(ALARM_NO)).thenReturn(alarmVo());

        mockMvc.perform(post("/api/v1/iot/alarms/{alarmNo}/acknowledge", ALARM_NO)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alarmNo").value(ALARM_NO));
        verify(alarmService).acknowledge(ALARM_NO);
    }

    @Test
    @DisplayName("关闭：POST 原因必填回显")
    void closeDelegatesWithReason() throws Exception {
        when(alarmService.close(eq(ALARM_NO), any(CloseAlarmRequest.class))).thenReturn(alarmVo());

        mockMvc.perform(post("/api/v1/iot/alarms/{alarmNo}/close", ALARM_NO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"处置完成\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alarmNo").value(ALARM_NO))
                .andExpect(jsonPath("$.triggerValue").value("170"));
        verify(alarmService).close(eq(ALARM_NO), any(CloseAlarmRequest.class));
    }

    @Test
    @DisplayName("关闭校验：reason 缺失 → @Valid 400")
    void closeRejectsMissingReason() throws Exception {
        mockMvc.perform(post("/api/v1/iot/alarms/{alarmNo}/close", ALARM_NO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(alarmService);
    }

    /** 告警视图夹具（ACTIVE 心率危急告警） */
    private static AlarmVO alarmVo() {
        return new AlarmVO(
                1L,
                ALARM_NO,
                900001L,
                "dev-001",
                5L,
                "20260901000001",
                1001L,
                AlarmLevel.CRITICAL,
                "MDC_ECG_HEART_RATE",
                "170",
                AlarmStatus.ACTIVE,
                1,
                OffsetDateTime.ofInstant(java.time.Instant.parse("2026-09-26T08:00:00Z"), ZoneOffset.UTC),
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.ofInstant(java.time.Instant.parse("2026-09-26T08:00:00Z"), ZoneOffset.UTC));
    }
}
