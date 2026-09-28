package com.fuyun.iot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.iot.dto.CreateMetricRequest;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.enums.MetricDataType;
import com.fuyun.iot.service.IMetricDictService;
import com.fuyun.iot.vo.MetricDictVO;
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
 * MDC 术语字典端点薄层冒烟单测（两端点：清单/登记 201；JaCoCo BUNDLE 兜底）：controller 禁
 * 业务逻辑与事务（A.1-8），重复登记冲突归服务层（MetricDictServiceImplTest 承载）；
 * @Valid 400 经全局渲染器按生产行为出网。
 */
@ExtendWith(MockitoExtension.class)
class MetricDictControllerTest {

    private static final String METRIC_CODE = "MDC_ECG_HEART_RATE";

    @Mock
    private IMetricDictService metricDictService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MetricDictController(metricDictService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("清单：GET 类别过滤参数透传，字典视图升序出网")
    void listDelegatesWithCategory() throws Exception {
        when(metricDictService.list(MetricCategory.VITAL_SIGN)).thenReturn(List.of(metricVo()));

        mockMvc.perform(get("/api/v1/iot/metrics")
                        .param("category", "VITAL_SIGN")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metricCode").value(METRIC_CODE))
                .andExpect(jsonPath("$[0].metricName").value("心率"));
    }

    @Test
    @DisplayName("登记：POST 返回 201 且编码回显")
    void createReturnsCreatedWithVo() throws Exception {
        when(metricDictService.create(any(CreateMetricRequest.class))).thenReturn(metricVo());

        mockMvc.perform(post("/api/v1/iot/metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metricCode":"MDC_ECG_HEART_RATE","metricName":"心率",
                                 "category":"VITAL_SIGN","dataType":"NUMERIC","unit":"次每分"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metricCode").value(METRIC_CODE));
        verify(metricDictService).create(any(CreateMetricRequest.class));
    }

    @Test
    @DisplayName("登记校验：category 缺失 → @Valid 400")
    void createRejectsMissingCategory() throws Exception {
        mockMvc.perform(post("/api/v1/iot/metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metricCode\":\"MDC_X\",\"metricName\":\"X\",\"dataType\":\"NUMERIC\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(metricDictService);
    }

    /** 字典视图夹具（V1007 种子行同款字段） */
    private static MetricDictVO metricVo() {
        return new MetricDictVO(
                METRIC_CODE, "心率", MetricCategory.VITAL_SIGN, MetricDataType.NUMERIC, "次每分", null, null, "WARNING");
    }
}
