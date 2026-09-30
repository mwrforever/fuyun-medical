package com.fuyun.iot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.CreateProductRequest;
import com.fuyun.iot.dto.ProductQueryRequest;
import com.fuyun.iot.dto.UpdateCommandsRequest;
import com.fuyun.iot.dto.UpdateMappingsRequest;
import com.fuyun.iot.enums.CommandSafetyLevel;
import com.fuyun.iot.enums.MismatchStrategy;
import com.fuyun.iot.enums.ProductSyncStatus;
import com.fuyun.iot.service.IProductService;
import com.fuyun.iot.vo.CommandVO;
import com.fuyun.iot.vo.MetricMappingVO;
import com.fuyun.iot.vo.ProductVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 产品管理端点薄层冒烟单测（六端点：上架 201 / 模型同步 / 分页 / 详情 / 命令标注 / 映射编辑；
 * JaCoCo BUNDLE 兜底）：controller 禁业务逻辑与事务（A.1-8），流水线与失配检测归服务层
 * （ProductServiceImplTest 承载）；@Valid 400 与 BizException ProblemDetail 经全局渲染器按
 * 生产行为出网。挂 WRITE 审计注解的落账行为归 M01 审计切面，不在本薄层断言面。
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    private static final String PRODUCT_ID = "prod-iotda-001";

    @Mock
    private IProductService productService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：@Valid 校验失败与 BizException 按生产行为出 ProblemDetail
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("上架：POST 返回 201 且产品标识回显（服务调用一次）")
    void createReturnsCreatedWithProductVo() throws Exception {
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(productVo());

        mockMvc.perform(post("/api/v1/iot/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName":"多参数监护仪","deviceType":"MONITOR","protocolType":"MQTT",
                                 "dataFormat":"JSON"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID))
                .andExpect(jsonPath("$.syncStatus").value("SYNCING"));
    }

    @Test
    @DisplayName("上架校验：productName 空白 → @Valid 400（全局渲染器出 ProblemDetail）")
    void createRejectsBlankProductNameWith400() throws Exception {
        mockMvc.perform(post("/api/v1/iot/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"productName\":\"\",\"deviceType\":\"MONITOR\",\"protocolType\":\"MQTT\",\"dataFormat\":\"JSON\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(productService);
    }

    @Test
    @DisplayName("模型同步：POST model-sync 返回 200 且以路径变量调用服务")
    void syncModelDelegatesWithPathId() throws Exception {
        when(productService.syncModel(PRODUCT_ID)).thenReturn(productVo());

        mockMvc.perform(post("/api/v1/iot/products/{productId}/model-sync", PRODUCT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID));
        verify(productService).syncModel(PRODUCT_ID);
    }

    @Test
    @DisplayName("分页：GET 返回 PageResult 四字段形态")
    void pageReturnsPageResultShape() throws Exception {
        when(productService.page(any(ProductQueryRequest.class)))
                .thenReturn(PageResult.of(List.of(productVo()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/iot/products")
                        .param("syncStatus", "SYNCING")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value(PRODUCT_ID))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("详情：产品不存在 → 404 ProblemDetail 携带 IOT-1002")
    void detailReturnsProblemDetailOnMissing() throws Exception {
        when(productService.getById("missing"))
                .thenThrow(new com.fuyun.common.exception.BizException(
                        IotErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND, "产品不存在：missing"));

        mockMvc.perform(get("/api/v1/iot/products/{productId}", "missing").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(IotErrorCode.PRODUCT_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("命令标注：PUT commands 返回 200 清单且安全等级回显")
    void updateCommandsReturnsList() throws Exception {
        when(productService.updateCommands(eq(PRODUCT_ID), any(UpdateCommandsRequest.class)))
                .thenReturn(List.of(
                        new CommandVO(1L, PRODUCT_ID, "setWorkMode", "vital", CommandSafetyLevel.SAFETY, true)));

        mockMvc.perform(put("/api/v1/iot/products/{productId}/commands", PRODUCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commands":[{"commandName":"setWorkMode","serviceId":"vital",
                                 "safetyLevel":"SAFETY"}]}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].allowed").value(true));
    }

    @Test
    @DisplayName("命令回显：GET commands 返回 200 清单且以路径变量调用服务")
    void getCommandsReturnsList() throws Exception {
        when(productService.listCommands(PRODUCT_ID))
                .thenReturn(List.of(
                        new CommandVO(1L, PRODUCT_ID, "setWorkMode", "vital", CommandSafetyLevel.SAFETY, true)));

        mockMvc.perform(get("/api/v1/iot/products/{productId}/commands", PRODUCT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].commandName").value("setWorkMode"))
                .andExpect(jsonPath("$[0].allowed").value(true));
        verify(productService).listCommands(PRODUCT_ID);
    }

    @Test
    @DisplayName("映射回显：GET metric-mappings 返回 200 清单且以路径变量调用服务")
    void getMetricMappingsReturnsList() throws Exception {
        when(productService.listMetricMappings(PRODUCT_ID))
                .thenReturn(List.of(new MetricMappingVO(
                        1L, PRODUCT_ID, "heartRate", "MDC_ECG_HEART_RATE", MismatchStrategy.RAW_PASSTHROUGH)));

        mockMvc.perform(get("/api/v1/iot/products/{productId}/metric-mappings", PRODUCT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].propertyName").value("heartRate"))
                .andExpect(jsonPath("$[0].metricCode").value("MDC_ECG_HEART_RATE"));
        verify(productService).listMetricMappings(PRODUCT_ID);
    }

    @Test
    @DisplayName("映射编辑：PUT metric-mappings 返回 200 清单且策略回显")
    void updateMappingsReturnsList() throws Exception {
        when(productService.updateMetricMappings(eq(PRODUCT_ID), any(UpdateMappingsRequest.class)))
                .thenReturn(List.of(new MetricMappingVO(
                        1L, PRODUCT_ID, "heartRate", "MDC_ECG_HEART_RATE", MismatchStrategy.RAW_PASSTHROUGH)));

        mockMvc.perform(put("/api/v1/iot/products/{productId}/metric-mappings", PRODUCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mappings":[{"propertyName":"heartRate",
                                 "metricCode":"MDC_ECG_HEART_RATE"}]}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mismatchStrategy").value("RAW_PASSTHROUGH"));
    }

    /** 产品视图夹具 */
    private static ProductVO productVo() {
        return new ProductVO(
                PRODUCT_ID,
                "多参数监护仪",
                "MONITOR",
                "MQTT",
                "JSON",
                null,
                null,
                null,
                null,
                ProductSyncStatus.SYNCING,
                null);
    }
}
