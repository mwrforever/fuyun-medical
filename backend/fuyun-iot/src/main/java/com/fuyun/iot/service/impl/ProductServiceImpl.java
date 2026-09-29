package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.CreateProductRequest;
import com.fuyun.iot.dto.ProductQueryRequest;
import com.fuyun.iot.dto.UpdateCommandsRequest;
import com.fuyun.iot.dto.UpdateMappingsRequest;
import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.entity.IotProductCommandEntity;
import com.fuyun.iot.entity.IotProductEntity;
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
import com.fuyun.iot.service.IProductService;
import com.fuyun.iot.vo.CommandVO;
import com.fuyun.iot.vo.MetricMappingVO;
import com.fuyun.iot.vo.ProductVO;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产品管理服务实现（iot.iot_product 唯一写入口）：上架流水线（Registry.createProduct + 本地
 * 镜像落行 SYNCING）、物模型同步与失配检测（模型属性 ⊆ 映射表判定 SYNCED/MISMATCH，畸形快照
 * 兜底 MISMATCH——原文透传红线，14-iot FU-M14-02）、命令安全等级与属性映射全量替换编辑。
 *
 * <p>注册中心不可用语义：{@link RegistryException} 在本层统一转 IOT-1022（503）业务异常，
 * 全局渲染器出 ProblemDetail；上架路径注册中心失败即整链失败（本地不落孤儿行），本地落行
 * 撞唯一键（注册中心标识重复，理论不发生）由全局渲染兜底，不做业务化翻译。
 *
 * <p>事务边界：同步/标注/映射编辑为多行写路径挂方法级事务；上架仅单行 INSERT 不挂事务
 * （注册中心调用在事务外，禁把外部慢调用裹进本地事务）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class ProductServiceImpl extends ServiceImpl<IotProductMapper, IotProductEntity> implements IProductService {

    /** 命令标注 mapper：白名单全量替换写路径 */
    private final IotProductCommandMapper commandMapper;

    /** 属性映射 mapper：映射全量替换与失配检测取数 */
    private final IotMetricMappingMapper metricMappingMapper;

    /** MDC 字典 mapper：映射编辑的字典码存在性批量校验 */
    private final IotMetricDictMapper metricDictMapper;

    /** 设备注册中心契约：产品/物模型管理面唯一出口（双实现经 IotRegistryConfig 装配） */
    private final IotDeviceRegistry registry;

    /** JSON 转换器：失配检测解析模型快照属性名集合 */
    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param registry           设备注册中心契约，非空
     * @param commandMapper      命令标注 mapper，非空
     * @param metricMappingMapper 属性映射 mapper，非空
     * @param metricDictMapper   MDC 字典 mapper，非空
     * @param objectMapper       JSON 转换器，非空；来源：Boot 容器 ObjectMapper
     */
    public ProductServiceImpl(
            IotDeviceRegistry registry,
            IotProductCommandMapper commandMapper,
            IotMetricMappingMapper metricMappingMapper,
            IotMetricDictMapper metricDictMapper,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.commandMapper = commandMapper;
        this.metricMappingMapper = metricMappingMapper;
        this.metricDictMapper = metricDictMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProductVO createProduct(CreateProductRequest request) {
        // 上架第一环：注册中心受理（事务外外部调用——失败即整链失败，本地不落孤儿行）
        ProductRef ref;
        try {
            ref = registry.createProduct(new ProductSpec(
                    request.productName(),
                    request.deviceType(),
                    request.protocolType(),
                    request.dataFormat(),
                    request.manufacturerName(),
                    request.industry(),
                    request.description(),
                    request.modelDefinitionJson()));
        } catch (RegistryException e) {
            log.error("产品上架失败（注册中心不可用）：productName={}，原因={}", request.productName(), e.getMessage(), e);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "注册中心不可用，产品上架失败：" + request.productName());
        }
        // 上架第二环：本地镜像落行（product_id 取注册中心返回值自然键，初态 SYNCING）
        IotProductEntity entity = new IotProductEntity();
        entity.setProductId(ref.productId());
        entity.setProductName(request.productName());
        entity.setDeviceType(request.deviceType());
        entity.setProtocolType(request.protocolType());
        entity.setDataFormat(request.dataFormat());
        entity.setManufacturerName(request.manufacturerName());
        entity.setIndustry(request.industry());
        entity.setDescription(request.description());
        entity.setModelDefinition(request.modelDefinitionJson());
        entity.setSyncStatus(ProductSyncStatus.SYNCING);
        baseMapper.insert(entity);
        log.info(
                "产品上架落行完成：productId={}，productName={}，syncStatus=SYNCING",
                entity.getProductId(),
                entity.getProductName());
        return ProductVO.from(entity);
    }

    @Override
    @Transactional
    public ProductVO syncModel(String productId) {
        // 前置：镜像行存在（404）且本地快照非空（409——无模型快照无从对账，状态机守卫）
        IotProductEntity entity = baseMapper.selectById(productId);
        if (entity == null) {
            log.warn("物模型同步拒绝：产品不存在：productId={}", productId);
            throw new BizException(IotErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND, "产品不存在：" + productId);
        }
        String modelJson = entity.getModelDefinition();
        if (modelJson == null || modelJson.isBlank()) {
            log.warn("物模型同步拒绝：模型快照缺失：productId={}", productId);
            throw new BizException(
                    IotErrorCode.PRODUCT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "物模型快照缺失，禁止同步：" + productId);
        }
        // 同步第一环：本地快照重推注册中心（本地行是对账权威；失败不置状态整体回滚）
        try {
            registry.syncModel(productId, modelJson);
        } catch (RegistryException e) {
            log.error("物模型同步失败（注册中心不可用）：productId={}，原因={}", productId, e.getMessage(), e);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "注册中心不可用，物模型同步失败：" + productId);
        }
        // 同步第二环：失配检测（模型属性 ⊆ 已配置映射即 SYNCED，否则 MISMATCH 原文透传兜底）
        boolean mismatch = detectMismatch(productId, modelJson);
        entity.setSyncStatus(mismatch ? ProductSyncStatus.MISMATCH : ProductSyncStatus.SYNCED);
        baseMapper.updateById(entity);
        log.info(
                "物模型同步完成：productId={}，syncStatus={}",
                productId,
                entity.getSyncStatus().getCode());
        return ProductVO.from(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ProductVO> page(ProductQueryRequest request) {
        // 缺省补齐：page 0 基缺省 0、size 缺省 20（GET 幂等查询无强制必填面）
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：状态过滤缺席即不过滤；product_id 升序稳定（自然键字典序，管理台分页口径）
        Page<IotProductEntity> result = lambdaQuery()
                .eq(request.syncStatus() != null, IotProductEntity::getSyncStatus, request.syncStatus())
                .orderByAsc(IotProductEntity::getProductId)
                .page(new Page<>(page + 1, size));
        return PageResult.of(result.getRecords().stream().map(ProductVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductVO getById(String productId) {
        return ProductVO.from(requireProduct(productId));
    }

    @Override
    @Transactional
    public List<CommandVO> updateCommands(String productId, UpdateCommandsRequest request) {
        requireProduct(productId);
        // 全量替换第一环：逻辑删该产品全部旧行（部分唯一索引只约束未删行，@TableLogic 软删）
        commandMapper.delete(lambdaQueryOfCommands(productId));
        // 全量替换第二环：落新行（allowed 缺省按级别：SAFETY=true/TREATMENT=false，显式指定覆盖）
        List<CommandVO> result = new ArrayList<>();
        for (UpdateCommandsRequest.CommandItem item : request.commands()) {
            IotProductCommandEntity entity = new IotProductCommandEntity();
            entity.setProductId(productId);
            entity.setCommandName(item.commandName());
            entity.setServiceId(item.serviceId());
            entity.setSafetyLevel(item.safetyLevel());
            entity.setAllowed(
                    item.allowed() != null ? item.allowed() : item.safetyLevel().defaultAllowed());
            commandMapper.insert(entity);
            result.add(CommandVO.from(entity));
        }
        log.info("命令安全等级标注完成：productId={}，commandCount={}", productId, result.size());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommandVO> listCommands(String productId) {
        // 404 守卫与详情同口径（登记弹窗以产品存在为前提）
        requireProduct(productId);
        // 数据库读操作：id 升序稳定回显（与 PUT 保存序一致，FU-M14-09 白名单数据源回读面）
        return commandMapper
                .selectList(lambdaQueryOfCommands(productId).orderByAsc(IotProductCommandEntity::getId))
                .stream()
                .map(CommandVO::from)
                .toList();
    }

    @Override
    @Transactional
    public List<MetricMappingVO> updateMetricMappings(String productId, UpdateMappingsRequest request) {
        requireProduct(productId);
        // 唯一键前置拒绝：同批 propertyName 重复即 IOT-1005（写库前拦截，不触删改路径）
        Set<String> seen = new HashSet<>();
        for (UpdateMappingsRequest.MappingItem item : request.mappings()) {
            if (!seen.add(item.propertyName())) {
                log.warn("映射编辑拒绝：同批属性名重复：productId={}，propertyName={}", productId, item.propertyName());
                throw new BizException(
                        IotErrorCode.METRIC_MAPPING_CONFLICT,
                        HttpStatus.CONFLICT,
                        "同批映射存在重复属性名：" + item.propertyName());
            }
        }
        // 字典码存在性批量校验（宪法 A.4.3-14 拒循环内单查——单次 IN 批取后内存比对），缺失即 IOT-1004
        List<String> metricCodes = request.mappings().stream()
                .map(UpdateMappingsRequest.MappingItem::metricCode)
                .distinct()
                .toList();
        Set<String> existingCodes = metricDictMapper.selectBatchIds(metricCodes).stream()
                .map(IotMetricDictEntity::getMetricCode)
                .collect(Collectors.toSet());
        for (UpdateMappingsRequest.MappingItem item : request.mappings()) {
            if (!existingCodes.contains(item.metricCode())) {
                log.warn("映射编辑拒绝：字典码无命中：productId={}，metricCode={}", productId, item.metricCode());
                throw new BizException(
                        IotErrorCode.METRIC_DICT_NOT_FOUND, HttpStatus.NOT_FOUND, "MDC 字典编码不存在：" + item.metricCode());
            }
        }
        // 全量替换：逻辑删旧行 + 落新行（mismatchStrategy 缺省 RAW_PASSTHROUGH——失配原文透传红线）
        metricMappingMapper.delete(lambdaQueryOfMappings(productId));
        List<MetricMappingVO> result = new ArrayList<>();
        for (UpdateMappingsRequest.MappingItem item : request.mappings()) {
            IotMetricMappingEntity entity = new IotMetricMappingEntity();
            entity.setProductId(productId);
            entity.setPropertyName(item.propertyName());
            entity.setMetricCode(item.metricCode());
            entity.setMismatchStrategy(
                    item.mismatchStrategy() != null ? item.mismatchStrategy() : MismatchStrategy.RAW_PASSTHROUGH);
            metricMappingMapper.insert(entity);
            result.add(MetricMappingVO.from(entity));
        }
        log.info("属性 MDC 映射编辑完成：productId={}，mappingCount={}", productId, result.size());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricMappingVO> listMetricMappings(String productId) {
        // 404 守卫与详情同口径（回显弹窗以产品存在为前提）
        requireProduct(productId);
        // 数据库读操作：id 升序稳定回显（与 PUT 保存序一致，管理台弹窗回填确定性口径）
        return metricMappingMapper
                .selectList(lambdaQueryOfMappings(productId).orderByAsc(IotMetricMappingEntity::getId))
                .stream()
                .map(MetricMappingVO::from)
                .toList();
    }

    /**
     * 失配检测：提取模型快照全部属性名（services[].properties[].name），比对映射表已配置面——
     * 存在未映射属性即失配（遥测按原文透传并告警，不静默丢弃）。
     *
     * @param productId 产品标识（映射表查询键），非空
     * @param modelJson 模型 JSON 快照，非空（畸形快照按失配兜底，warn 留痕对账）
     * @return true=MISMATCH（存在未映射或快照畸形），false=SYNCED
     */
    private boolean detectMismatch(String productId, String modelJson) {
        Set<String> propertyNames = new HashSet<>();
        try {
            JsonNode root = objectMapper.readTree(modelJson);
            // 快照双形态兼容：服务能力裸数组或 {"services":[...]} 对象包裹（离线导出文件形态）
            JsonNode services = root.isArray() ? root : root.path("services");
            for (JsonNode service : services) {
                for (JsonNode property : service.path("properties")) {
                    String name = property.path("name").asText();
                    if (!name.isBlank()) {
                        propertyNames.add(name);
                    }
                }
            }
        } catch (Exception e) {
            // 快照畸形：属性集合不可知即按失配置位（原文透传兜底），warn 留痕供对账排查
            log.warn("物模型快照 JSON 解析失败，按失配置位：productId={}，原因={}", productId, e.getMessage());
            return true;
        }
        Set<String> mappedNames = metricMappingMapper
                .selectList(new LambdaQueryWrapper<IotMetricMappingEntity>()
                        .eq(IotMetricMappingEntity::getProductId, productId))
                .stream()
                .map(IotMetricMappingEntity::getPropertyName)
                .collect(Collectors.toSet());
        boolean mismatch = !mappedNames.containsAll(propertyNames);
        if (mismatch) {
            log.warn(
                    "失配检测命中未映射属性：productId={}，modelPropertyCount={}，mappedCount={}",
                    productId,
                    propertyNames.size(),
                    mappedNames.size());
        }
        return mismatch;
    }

    /**
     * 产品存在性校验（404 守卫）。
     *
     * @param productId 注册中心产品标识，非空
     * @return 命中的镜像实体，非空
     * @throws BizException IOT-1002（404 产品不存在）
     */
    private IotProductEntity requireProduct(String productId) {
        IotProductEntity entity = baseMapper.selectById(productId);
        if (entity == null) {
            log.warn("产品不存在：productId={}", productId);
            throw new BizException(IotErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND, "产品不存在：" + productId);
        }
        return entity;
    }

    /**
     * 命令标注逻辑删条件（该产品全部未删行；@TableLogic 下 delete 翻译为 UPDATE deleted=1）。
     *
     * @param productId 产品标识，非空
     * @return 删除条件包装器，非空
     */
    private LambdaQueryWrapper<IotProductCommandEntity> lambdaQueryOfCommands(String productId) {
        return new LambdaQueryWrapper<IotProductCommandEntity>().eq(IotProductCommandEntity::getProductId, productId);
    }

    /**
     * 属性映射逻辑删条件（该产品全部未删行）。
     *
     * @param productId 产品标识，非空
     * @return 删除条件包装器，非空
     */
    private LambdaQueryWrapper<IotMetricMappingEntity> lambdaQueryOfMappings(String productId) {
        return new LambdaQueryWrapper<IotMetricMappingEntity>().eq(IotMetricMappingEntity::getProductId, productId);
    }
}
