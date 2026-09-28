package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.CreateProductRequest;
import com.fuyun.iot.dto.ProductQueryRequest;
import com.fuyun.iot.dto.UpdateCommandsRequest;
import com.fuyun.iot.dto.UpdateMappingsRequest;
import com.fuyun.iot.service.IProductService;
import com.fuyun.iot.vo.CommandVO;
import com.fuyun.iot.vo.MetricMappingVO;
import com.fuyun.iot.vo.ProductVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品管理端点（/api/v1/iot/products 六端点，FU-M14-02 上架流水线）：上架（POST，201）、物模型
 * 同步（POST /{id}/model-sync）、分页（GET）、详情（GET /{id}）、命令安全等级标注
 * （PUT /{id}/commands）、属性 MDC 映射编辑（PUT /{id}/metric-mappings）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用产品服务 + 编排响应，禁业务逻辑、禁
 * @Transactional（事务归 service impl 方法级）；上架/同步/标注/映射编辑挂 WRITE 审计
 * （@AuditLog 注解 + M01 审计切面落 system.audit_log，操作人取 OperatorContextHolder）；查询
 * 端点纯读不挂写审计。注册中心不可用（IOT-1022）经全局渲染器出 503 ProblemDetail。
 * 装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/products")
public class ProductController {

    /** 产品管理服务：六端点唯一业务出口（流水线/对账/替换编辑归服务层） */
    private final IProductService productService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param productService 产品管理服务，非空
     */
    public ProductController(IProductService productService) {
        this.productService = productService;
    }

    /**
     * 产品上架（POST /api/v1/iot/products；WRITE 审计）。
     *
     * @param request 上架请求体（@Valid），非空；来源：管理台上架表单
     * @return 落库后的产品视图（sync_status=SYNCING 初态）；201
     * @throws com.fuyun.common.exception.BizException IOT-1022（503 注册中心不可用）
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public ProductVO create(@Valid @RequestBody CreateProductRequest request) {
        return productService.createProduct(request);
    }

    /**
     * 物模型同步（POST /api/v1/iot/products/{productId}/model-sync；WRITE 审计）：本地快照重推
     * 注册中心 + 失配检测置位（SYNCED/MISMATCH）。
     *
     * @param productId 注册中心产品标识（路径变量）
     * @return 更新同步状态后的产品视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1002（404）/ IOT-1003（409 快照缺失）/
     *                                                 IOT-1022（503）
     */
    @PostMapping("/{productId}/model-sync")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ProductVO syncModel(@PathVariable String productId) {
        return productService.syncModel(productId);
    }

    /**
     * 产品镜像分页查询（GET /api/v1/iot/products；纯读）。
     *
     * @param request 查询参数载体（syncStatus 过滤可空，page 0 基缺省 0、size 缺省 20）
     * @return 产品视图分页出参；200
     */
    @GetMapping
    public PageResult<ProductVO> page(@Valid ProductQueryRequest request) {
        return productService.page(request);
    }

    /**
     * 产品镜像详情（GET /api/v1/iot/products/{productId}；纯读，含模型快照回显）。
     *
     * @param productId 注册中心产品标识（路径变量）
     * @return 产品视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1002（404 产品不存在）
     */
    @GetMapping("/{productId}")
    public ProductVO detail(@PathVariable String productId) {
        return productService.getById(productId);
    }

    /**
     * 命令安全等级全量标注（PUT /api/v1/iot/products/{productId}/commands；WRITE 审计）：allowed
     * 缺省按级别（SAFETY=true/TREATMENT=false）。
     *
     * @param productId 注册中心产品标识（路径变量）
     * @param request   标注请求体（@Valid），非空
     * @return 标注后的命令视图清单（与入参同序）；200
     * @throws com.fuyun.common.exception.BizException IOT-1002（404 产品不存在）
     */
    @PutMapping("/{productId}/commands")
    @AuditLog(actionType = AuditActionType.WRITE)
    public List<CommandVO> updateCommands(
            @PathVariable String productId, @Valid @RequestBody UpdateCommandsRequest request) {
        return productService.updateCommands(productId, request);
    }

    /**
     * 属性 MDC 映射全量编辑（PUT /api/v1/iot/products/{productId}/metric-mappings；WRITE 审计）：
     * mismatchStrategy 缺省 RAW_PASSTHROUGH。
     *
     * @param productId 注册中心产品标识（路径变量）
     * @param request   映射请求体（@Valid），非空
     * @return 编辑后的映射视图清单（与入参同序）；200
     * @throws com.fuyun.common.exception.BizException IOT-1002（404）/ IOT-1004（404 字典码缺失）/
     *                                                 IOT-1005（409 同批属性名重复）
     */
    @PutMapping("/{productId}/metric-mappings")
    @AuditLog(actionType = AuditActionType.WRITE)
    public List<MetricMappingVO> updateMetricMappings(
            @PathVariable String productId, @Valid @RequestBody UpdateMappingsRequest request) {
        return productService.updateMetricMappings(productId, request);
    }
}
