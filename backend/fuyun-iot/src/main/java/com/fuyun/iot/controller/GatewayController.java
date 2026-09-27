package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.GatewayQueryRequest;
import com.fuyun.iot.dto.SaveGatewayRequest;
import com.fuyun.iot.service.IGatewayService;
import com.fuyun.iot.vo.GatewayVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 边缘网关端点（/api/v1/iot/gateways 四端点，FU-M14-12 本地档案面）：分页（GET，病区/模式/状态
 * 过滤）、登记（POST）、更新（PUT /{gatewayId}）、删除（DELETE /{gatewayId} 软删）。写操作挂
 * WRITE 审计。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用网关服务 + 编排响应；standby 校验链与
 * 删除守卫归服务层；网关注册与拓扑经 IoTDA 维护（Registry 直通，本端点不承载）。装配归
 * fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/gateways")
public class GatewayController {

    /** 网关服务：四端点唯一业务出口 */
    private final IGatewayService gatewayService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param gatewayService 网关服务，非空
     */
    public GatewayController(IGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * 网关分页（GET /api/v1/iot/gateways；病区/模式/状态/标识过滤，page 0 基）。
     *
     * @param request 分页查询请求（查询参数绑定，@Valid），非空
     * @return 分页出参；200
     */
    @GetMapping
    public PageResult<GatewayVO> page(@Valid GatewayQueryRequest request) {
        return gatewayService.page(request);
    }

    /**
     * 网关登记（POST /api/v1/iot/gateways；WRITE 审计）。
     *
     * @param request 保存请求体（@Valid），非空
     * @return 落库后的网关视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1024（409 标识已存在）或
     *                 IOT-1025（409 热备对端校验不通过）
     */
    @PostMapping
    @AuditLog(actionType = AuditActionType.WRITE)
    public GatewayVO create(@Valid @RequestBody SaveGatewayRequest request) {
        return gatewayService.create(request);
    }

    /**
     * 网关更新（PUT /api/v1/iot/gateways/{gatewayId}；WRITE 审计；路径 ID 为定位权威）。
     *
     * @param gatewayId 网关标识（路径变量）
     * @param request   保存请求体（@Valid），非空
     * @return 更新后的网关视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1023（404 网关不存在）或
     *                 IOT-1025（409 热备对端校验不通过）
     */
    @PutMapping("/{gatewayId}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public GatewayVO update(@PathVariable String gatewayId, @Valid @RequestBody SaveGatewayRequest request) {
        return gatewayService.update(gatewayId, request);
    }

    /**
     * 网关删除（DELETE /api/v1/iot/gateways/{gatewayId}；WRITE 审计；软删，热备引用守卫）。
     *
     * @param gatewayId 网关标识（路径变量）
     * @throws com.fuyun.common.exception.BizException IOT-1023（404 网关不存在）或
     *                 IOT-1025（409 他网关引用本网关为热备对端）
     */
    @DeleteMapping("/{gatewayId}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void delete(@PathVariable String gatewayId) {
        gatewayService.delete(gatewayId);
    }
}
