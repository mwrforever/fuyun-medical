package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.CreateProductRequest;
import com.fuyun.iot.dto.ProductQueryRequest;
import com.fuyun.iot.dto.UpdateCommandsRequest;
import com.fuyun.iot.dto.UpdateMappingsRequest;
import com.fuyun.iot.entity.IotProductCommandEntity;
import com.fuyun.iot.entity.IotProductEntity;
import com.fuyun.iot.enums.CommandSafetyLevel;
import com.fuyun.iot.enums.MismatchStrategy;
import com.fuyun.iot.enums.ProductSyncStatus;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.mapper.IotMetricMappingMapper;
import com.fuyun.iot.mapper.IotProductCommandMapper;
import com.fuyun.iot.mapper.IotProductMapper;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.ProductRef;
import com.fuyun.iot.registry.ProductSpec;
import com.fuyun.iot.registry.RegistryException;
import com.fuyun.iot.vo.CommandVO;
import com.fuyun.iot.vo.MetricMappingVO;
import com.fuyun.iot.vo.ProductVO;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 产品管理服务单测（P2 PR-2 Task 4 Step 3）：上架流水线（Registry 调用 + 本地落行 SYNCING）、
 * 物模型同步失配检测（MISMATCH/SYNCED/畸形 JSON 原文透传兜底）与 IOT-1022 注册中心不可用
 * 转译、映射编辑冲突链（同批重复 IOT-1005/字典码缺失 IOT-1004）、命令安全等级默认面
 * （SAFETY=true/TREATMENT=false + 显式覆盖）、分页与详情查询。JaCoCo 核心包
 * com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>Registry/四个 mapper 以 Mockito 模拟（真实 SQL 归 fuyun-app 集成面验证）；lambda 条件
 * 列名解析依赖 TableInfo（容器外单测需手动初始化一次）。
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    /** 产品标识夹具（注册中心返回值） */
    private static final String PRODUCT_ID = "prod-iotda-001";

    /** 物模型 JSON 夹具（单属性 heartRate） */
    private static final String MODEL_JSON =
            "{\"services\":[{\"serviceId\":\"vital\",\"properties\":[{\"name\":\"heartRate\"}]}]}";

    @Mock
    private IotDeviceRegistry registry;

    @Mock
    private IotProductMapper productMapper;

    @Mock
    private IotProductCommandMapper commandMapper;

    @Mock
    private IotMetricMappingMapper mappingMapper;

    @Mock
    private IotMetricDictMapper metricDictMapper;

    @Captor
    private ArgumentCaptor<IotProductEntity> productCaptor;

    @Captor
    private ArgumentCaptor<IotProductCommandEntity> commandCaptor;

    private ProductServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotProductEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotProductCommandEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.fuyun.iot.entity.IotMetricMappingEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 无 Spring 上下文直构（Bean 注册归 app 侧 IotConfig @Import）；ServiceImpl 基类字段手工注入
        service = new ProductServiceImpl(registry, commandMapper, mappingMapper, metricDictMapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", productMapper);
        ReflectionTestUtils.setField(service, "entityClass", IotProductEntity.class);
    }

    @Test
    @DisplayName("上架流水线：Registry.createProduct 调用一次 + 本地落行 sync_status=SYNCING")
    void createProductCallsRegistryThenInsertsLocalRow() {
        when(registry.createProduct(any(ProductSpec.class))).thenReturn(new ProductRef(PRODUCT_ID));

        ProductVO vo = service.createProduct(
                new CreateProductRequest("多参数监护仪", "MONITOR", "MQTT", "JSON", "厂商", "医疗设备", "描述", MODEL_JSON));

        verify(registry).createProduct(specWithProductName("多参数监护仪"));
        assertThat(vo.productId()).as("注册中心分配的产品标识回填 VO").isEqualTo(PRODUCT_ID);
        verify(productMapper).insert(productCaptor.capture());
        IotProductEntity inserted = productCaptor.getValue();
        assertThat(inserted.getProductId()).as("本地镜像自然键取注册中心返回值").isEqualTo(PRODUCT_ID);
        assertThat(inserted.getProductName()).as("产品名透传落行").isEqualTo("多参数监护仪");
        assertThat(inserted.getModelDefinition()).as("模型快照随上架落行").isEqualTo(MODEL_JSON);
        assertThat(inserted.getSyncStatus()).as("上架初态为 SYNCING（对账未完成）").isEqualTo(ProductSyncStatus.SYNCING);
    }

    @Test
    @DisplayName("上架拒绝：注册中心不可用 IOT-1022（503），且不触本地落行")
    void createProductTranslatesRegistryFailure() {
        when(registry.createProduct(any(ProductSpec.class))).thenThrow(new RegistryException("IoTDA 产品创建失败：timeout"));

        assertThatThrownBy(() -> service.createProduct(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.REGISTRY_UNAVAILABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
        verify(productMapper, never()).insert(any(IotProductEntity.class));
    }

    @Test
    @DisplayName("物模型同步失配：模型属性存在未映射项 → MISMATCH 置位（原文透传红线语义）")
    void syncModelMarksMismatchWhenPropertyUnmapped() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING));
        when(mappingMapper.selectList(any())).thenReturn(List.of());

        ProductVO vo = service.syncModel(PRODUCT_ID);

        verify(registry)
                .syncModel(org.mockito.ArgumentMatchers.eq(PRODUCT_ID), org.mockito.ArgumentMatchers.eq(MODEL_JSON));
        verify(productMapper).updateById(productCaptor.capture());
        assertThat(productCaptor.getValue().getSyncStatus())
                .as("未映射属性存在即 MISMATCH")
                .isEqualTo(ProductSyncStatus.MISMATCH);
        assertThat(vo.syncStatus()).isEqualTo(ProductSyncStatus.MISMATCH);
    }

    @Test
    @DisplayName("物模型同步成功：模型属性全部存在映射 → SYNCED 置位")
    void syncModelMarksSyncedWhenAllPropertiesMapped() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.MISMATCH));
        when(mappingMapper.selectList(any())).thenReturn(List.of(mappingRow("heartRate", "MDC_ECG_HEART_RATE")));

        ProductVO vo = service.syncModel(PRODUCT_ID);

        verify(registry)
                .syncModel(org.mockito.ArgumentMatchers.eq(PRODUCT_ID), org.mockito.ArgumentMatchers.eq(MODEL_JSON));
        verify(productMapper).updateById(productCaptor.capture());
        assertThat(productCaptor.getValue().getSyncStatus())
                .as("模型属性全覆盖即 SYNCED")
                .isEqualTo(ProductSyncStatus.SYNCED);
        assertThat(vo.syncStatus()).isEqualTo(ProductSyncStatus.SYNCED);
    }

    @Test
    @DisplayName("物模型同步兜底：快照 JSON 畸形 → MISMATCH 置位（不阻断同步主流程）")
    void syncModelFallsBackToMismatchOnMalformedJson() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING, "{not-json"));

        service.syncModel(PRODUCT_ID);

        verify(productMapper).updateById(productCaptor.capture());
        assertThat(productCaptor.getValue().getSyncStatus())
                .as("畸形快照无法提取属性集合，按失配置位（告警对账）")
                .isEqualTo(ProductSyncStatus.MISMATCH);
    }

    @Test
    @DisplayName("物模型同步拒绝：产品不存在 IOT-1002（404）")
    void syncModelRejectsUnknownProduct() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.syncModel(PRODUCT_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.PRODUCT_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("物模型同步拒绝：快照缺失 IOT-1003（409）——无模型快照禁止同步")
    void syncModelRejectsBlankSnapshot() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING, " "));

        assertThatThrownBy(() -> service.syncModel(PRODUCT_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.PRODUCT_STATE_NOT_ALLOWED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("物模型同步拒绝：注册中心不可用 IOT-1022（503），且不置同步状态")
    void syncModelTranslatesRegistryFailure() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING));
        org.mockito.Mockito.doThrow(new RegistryException("IoTDA 物模型同步失败：timeout"))
                .when(registry)
                .syncModel(any(), any());

        assertThatThrownBy(() -> service.syncModel(PRODUCT_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.REGISTRY_UNAVAILABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
        verify(productMapper, never()).updateById(any(IotProductEntity.class));
    }

    @Test
    @DisplayName("映射回显查询：命中回显全集 / 空配置回空清单 / 产品不存在 IOT-1002（404）")
    void listMetricMappingsEchoesExistingRowsOrEmpty() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCED));
        when(mappingMapper.selectList(any()))
                .thenReturn(List.of(
                        mappingRow("heartRate", "MDC_ECG_HEART_RATE"), mappingRow("spo2", "MDC_PULSE_OXIM_SPO2")));

        List<MetricMappingVO> result = service.listMetricMappings(PRODUCT_ID);

        assertThat(result).as("既有映射全集回显（弹窗打开回填）").hasSize(2);
        assertThat(result.get(0).propertyName()).isEqualTo("heartRate");
        assertThat(result.get(1).metricCode()).isEqualTo("MDC_PULSE_OXIM_SPO2");
        // 空配置态：回空清单（弹窗回落单空行快速录入）
        when(mappingMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listMetricMappings(PRODUCT_ID)).as("无映射配置回空清单").isEmpty();
        // 产品不存在：404 守卫与详情同口径
        when(productMapper.selectById("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.listMetricMappings("missing"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("映射编辑冲突：同批 propertyName 重复 IOT-1005（409），且不触库写路径")
    void updateMappingsRejectsDuplicatePropertyInBatch() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING));

        assertThatThrownBy(() -> service.updateMetricMappings(
                        PRODUCT_ID,
                        new UpdateMappingsRequest(List.of(
                                new UpdateMappingsRequest.MappingItem("heartRate", "MDC_ECG_HEART_RATE", null),
                                new UpdateMappingsRequest.MappingItem("heartRate", "MDC_PULSE_OXIM_SPO2", null)))))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.METRIC_MAPPING_CONFLICT);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(mappingMapper, never()).delete(any());
        verify(mappingMapper, never()).insert(any(com.fuyun.iot.entity.IotMetricMappingEntity.class));
    }

    @Test
    @DisplayName("映射编辑冲突：metric_code 在字典无命中 IOT-1004（404，批量校验兜底）")
    void updateMappingsRejectsUnknownMetricCode() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING));
        // selectBatchIds 无 stub 默认返回空清单 → 请求码必然缺失（IOT-1004 路径）
        assertThatThrownBy(() -> service.updateMetricMappings(
                        PRODUCT_ID,
                        new UpdateMappingsRequest(
                                List.of(new UpdateMappingsRequest.MappingItem("heartRate", "MDC_MISSING", null)))))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.METRIC_DICT_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("映射编辑成功：逻辑删旧行 + 落新行（RAW_PASSTHROUGH 缺省与显式指定并存）")
    void updateMappingsReplacesRows() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING));
        when(metricDictMapper.selectBatchIds(any()))
                .thenReturn(List.of(dictRow("MDC_ECG_HEART_RATE"), dictRow("MDC_PULSE_OXIM_SPO2")));

        List<MetricMappingVO> result = service.updateMetricMappings(
                PRODUCT_ID,
                new UpdateMappingsRequest(List.of(
                        new UpdateMappingsRequest.MappingItem("heartRate", "MDC_ECG_HEART_RATE", null),
                        new UpdateMappingsRequest.MappingItem(
                                "spo2", "MDC_PULSE_OXIM_SPO2", MismatchStrategy.RAW_PASSTHROUGH))));

        verify(mappingMapper).delete(any());
        verify(mappingMapper).insert(mappingNamed("heartRate"));
        verify(mappingMapper).insert(mappingNamed("spo2"));
        assertThat(result).as("替换结果回显与入参同序同量").hasSize(2);
        assertThat(result.get(0).mismatchStrategy())
                .as("缺省策略回填 RAW_PASSTHROUGH（失配原文透传红线）")
                .isEqualTo(MismatchStrategy.RAW_PASSTHROUGH);
    }

    @Test
    @DisplayName("命令回显查询：命中回显全集 / 空配置回空清单 / 产品不存在 IOT-1002（404）")
    void listCommandsEchoesExistingRowsOrEmpty() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCED));
        when(commandMapper.selectList(any()))
                .thenReturn(List.of(commandRow("setWorkMode", CommandSafetyLevel.SAFETY, true)));

        List<CommandVO> result = service.listCommands(PRODUCT_ID);

        assertThat(result).as("既有命令标注全集回显（弹窗打开回填）").hasSize(1);
        assertThat(result.get(0).commandName()).isEqualTo("setWorkMode");
        assertThat(result.get(0).safetyLevel()).isEqualTo(CommandSafetyLevel.SAFETY);
        assertThat(result.get(0).allowed()).as("放行状态随行回显（FU-M14-09 白名单数据源）").isTrue();
        // 空配置态：回空清单（弹窗回落单空行快速录入）
        when(commandMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listCommands(PRODUCT_ID)).as("无命令标注回空清单").isEmpty();
        // 产品不存在：404 守卫与详情同口径
        when(productMapper.selectById("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.listCommands("missing"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("命令安全等级默认面：SAFETY→allowed=true、TREATMENT→allowed=false、显式 allowed 覆盖生效")
    void updateCommandsAppliesSafetyDefaults() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCING));

        List<CommandVO> result = service.updateCommands(
                PRODUCT_ID,
                new UpdateCommandsRequest(List.of(
                        new UpdateCommandsRequest.CommandItem("setWorkMode", "vital", CommandSafetyLevel.SAFETY, null),
                        new UpdateCommandsRequest.CommandItem(
                                "defibrillate", "treatment", CommandSafetyLevel.TREATMENT, null),
                        new UpdateCommandsRequest.CommandItem(
                                "calibrate", "vital", CommandSafetyLevel.SAFETY, Boolean.FALSE))));

        verify(commandMapper).delete(any());
        verify(commandMapper, org.mockito.Mockito.times(3)).insert(commandCaptor.capture());
        List<IotProductCommandEntity> inserted = commandCaptor.getAllValues();
        assertThat(inserted.get(0).getAllowed()).as("安全级缺省放行").isTrue();
        assertThat(inserted.get(1).getAllowed()).as("治疗级缺省禁放行").isFalse();
        assertThat(inserted.get(2).getAllowed()).as("显式指定覆盖级别默认").isFalse();
        assertThat(result).as("标注结果回显与入参同序同量").hasSize(3);
    }

    @Test
    @DisplayName("命令标注拒绝：产品不存在 IOT-1002（404）")
    void updateCommandsRejectsUnknownProduct() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateCommands(
                        PRODUCT_ID,
                        new UpdateCommandsRequest(List.of(new UpdateCommandsRequest.CommandItem(
                                "setWorkMode", "vital", CommandSafetyLevel.SAFETY, null)))))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("分页：page/size 缺省补齐（0/20）+ 状态过滤透传 + VO 工厂出网")
    void pageAppliesDefaultsAndFilters() {
        when(productMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<IotProductEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(productEntity(ProductSyncStatus.SYNCING)));
            result.setTotal(1);
            return result;
        });

        PageResult<ProductVO> result = service.page(new ProductQueryRequest(null, null, ProductSyncStatus.SYNCING));

        assertThat(result.page()).as("0 基页码按请求口径回显").isZero();
        assertThat(result.size()).as("size 缺省 20").isEqualTo(20);
        assertThat(result.content()).as("实体经 VO 工厂出网").hasSize(1);
    }

    @Test
    @DisplayName("详情查询：命中回显 / 未命中 IOT-1002（404）")
    void getByIdReturnsVoOrThrowsNotFound() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(productEntity(ProductSyncStatus.SYNCED));

        assertThat(service.getById(PRODUCT_ID).productId()).isEqualTo(PRODUCT_ID);
        when(productMapper.selectById("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.getById("missing"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.PRODUCT_NOT_FOUND));
    }

    /** 产品规格匹配器（Registry 调用参数断言面：产品名透传） */
    private ProductSpec specWithProductName(String productName) {
        return org.mockito.ArgumentMatchers.argThat(spec -> spec != null && productName.equals(spec.productName()));
    }

    /** 映射插入行匹配器（按属性名区分两次 insert 的验证） */
    private com.fuyun.iot.entity.IotMetricMappingEntity mappingNamed(String propertyName) {
        return org.mockito.ArgumentMatchers.argThat(
                entity -> entity != null && propertyName.equals(entity.getPropertyName()));
    }

    /** 产品实体夹具（V1007 列面） */
    private static IotProductEntity productEntity(ProductSyncStatus status) {
        return productEntity(status, MODEL_JSON);
    }

    private static IotProductEntity productEntity(ProductSyncStatus status, String modelDefinition) {
        IotProductEntity entity = new IotProductEntity();
        entity.setProductId(PRODUCT_ID);
        entity.setProductName("多参数监护仪");
        entity.setDeviceType("MONITOR");
        entity.setProtocolType("MQTT");
        entity.setDataFormat("JSON");
        entity.setModelDefinition(modelDefinition);
        entity.setSyncStatus(status);
        return entity;
    }

    /** 映射实体夹具 */
    private static com.fuyun.iot.entity.IotMetricMappingEntity mappingRow(String propertyName, String metricCode) {
        com.fuyun.iot.entity.IotMetricMappingEntity entity = new com.fuyun.iot.entity.IotMetricMappingEntity();
        entity.setProductId(PRODUCT_ID);
        entity.setPropertyName(propertyName);
        entity.setMetricCode(metricCode);
        entity.setMismatchStrategy(MismatchStrategy.RAW_PASSTHROUGH);
        return entity;
    }

    /** 命令标注实体夹具（回显查询 stub 载体） */
    private static IotProductCommandEntity commandRow(
            String commandName, CommandSafetyLevel safetyLevel, Boolean allowed) {
        IotProductCommandEntity entity = new IotProductCommandEntity();
        entity.setProductId(PRODUCT_ID);
        entity.setCommandName(commandName);
        entity.setSafetyLevel(safetyLevel);
        entity.setAllowed(allowed);
        return entity;
    }

    /** 字典实体夹具（映射编辑存在性批量校验 stub 载体） */
    private static com.fuyun.iot.entity.IotMetricDictEntity dictRow(String metricCode) {
        com.fuyun.iot.entity.IotMetricDictEntity entity = new com.fuyun.iot.entity.IotMetricDictEntity();
        entity.setMetricCode(metricCode);
        return entity;
    }

    /** 上架请求夹具 */
    private static CreateProductRequest request() {
        return new CreateProductRequest("多参数监护仪", "MONITOR", "MQTT", "JSON", null, null, null, MODEL_JSON);
    }
}
