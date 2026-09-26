package com.fuyun.iot.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.CreateProductRequest;
import com.fuyun.iot.dto.ProductQueryRequest;
import com.fuyun.iot.dto.UpdateCommandsRequest;
import com.fuyun.iot.dto.UpdateMappingsRequest;
import com.fuyun.iot.vo.CommandVO;
import com.fuyun.iot.vo.MetricMappingVO;
import com.fuyun.iot.vo.ProductVO;
import java.util.List;

/**
 * 产品管理服务接口（P2 PR-2 Task 4）：产品上架流水线（Registry 调用 + 本地镜像落行）、物模型
 * 同步与失配检测、镜像查询、命令安全等级标注与属性 MDC 映射编辑的唯一业务出口。
 *
 * <p>注册中心不可用语义：RegistryException 由实现层统一转 IOT-1022（503）业务异常渲染。
 */
public interface IProductService {

    /**
     * 产品上架（POST /api/v1/iot/products 主管道）：Registry.createProduct 受理 + 本地镜像落行
     * （sync_status=SYNCING）。
     *
     * @param request 上架请求体（已过 @Valid），非空
     * @return 落库后的产品视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1022（503 注册中心不可用，此时不落本地行）
     */
    ProductVO createProduct(CreateProductRequest request);

    /**
     * 物模型同步（POST /api/v1/iot/products/{id}/model-sync）：本地快照重推注册中心 + 失配检测
     * （模型属性存在未映射项即 MISMATCH，全部映射即 SYNCED）。
     *
     * @param productId 注册中心产品标识，非空
     * @return 更新同步状态后的产品视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1002（404 产品不存在）/
     *                                                 IOT-1003（409 快照缺失禁止同步）/
     *                                                 IOT-1022（503 注册中心不可用，状态不置位）
     */
    ProductVO syncModel(String productId);

    /**
     * 产品镜像分页查询（GET /api/v1/iot/products）。
     *
     * @param request 查询参数载体（状态过滤可空，page 0 基缺省 0、size 缺省 20），非空
     * @return 产品视图分页出参，非空
     */
    PageResult<ProductVO> page(ProductQueryRequest request);

    /**
     * 产品镜像详情查询（GET /api/v1/iot/products/{id}）。
     *
     * @param productId 注册中心产品标识，非空
     * @return 产品视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1002（404 产品不存在）
     */
    ProductVO getById(String productId);

    /**
     * 命令安全等级全量标注（PUT /api/v1/iot/products/{id}/commands）：逻辑删旧行 + 落新行；
     * allowed 缺省按级别（SAFETY=true/TREATMENT=false）。
     *
     * @param productId 注册中心产品标识，非空
     * @param request   标注请求体（已过 @Valid），非空
     * @return 标注后的命令视图清单（与入参同序），非空
     * @throws com.fuyun.common.exception.BizException IOT-1002（404 产品不存在）
     */
    List<CommandVO> updateCommands(String productId, UpdateCommandsRequest request);

    /**
     * 属性 MDC 映射全量编辑（PUT /api/v1/iot/products/{id}/metric-mappings）：逻辑删旧行 +
     * 落新行；mismatchStrategy 缺省 RAW_PASSTHROUGH。
     *
     * @param productId 注册中心产品标识，非空
     * @param request   映射请求体（已过 @Valid），非空
     * @return 编辑后的映射视图清单（与入参同序），非空
     * @throws com.fuyun.common.exception.BizException IOT-1002（404 产品不存在）/
     *                                                 IOT-1004（404 字典码无命中）/
     *                                                 IOT-1005（409 同批属性名重复）
     */
    List<MetricMappingVO> updateMetricMappings(String productId, UpdateMappingsRequest request);
}
