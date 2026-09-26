package com.fuyun.iot.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.BindDeviceRequest;
import com.fuyun.iot.dto.BindingQueryRequest;
import com.fuyun.iot.dto.UnbindDeviceRequest;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.vo.BindingVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备绑定管理端点（/api/v1/iot/bindings 五端点，M14 绑定管理域）：绑定（POST，201）、解绑
 * （POST /{deviceId}/unbind，204）、绑定记录分页（GET）、病区生效绑定清单（GET /wards/{wardId}）、
 * 设备生效绑定查询（GET /devices/{deviceId}/active）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用绑定服务 + 编排响应，禁业务逻辑、禁
 * @Transactional（事务归 service impl 方法级）；绑定/解绑挂 WRITE 审计（@AuditLog 注解 +
 * M01 审计切面落 system.audit_log，操作人取 OperatorContextHolder）；查询端点纯读不挂写审计。
 * 生效绑定查询落空翻译 404 IOT-1009（服务层返回 Optional 供遥测富化复用，404 语义归端点面）。
 * 装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot")
public class BindingController {

    /** 绑定管理服务：五端点唯一业务出口（校验链/状态机/事件发布归服务层） */
    private final IBindingService bindingService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param bindingService 绑定管理服务，非空
     */
    public BindingController(IBindingService bindingService) {
        this.bindingService = bindingService;
    }

    /**
     * 绑定设备到患者（POST /api/v1/iot/bindings；WRITE 审计）。
     *
     * @param request 绑定请求体（@Valid），非空；来源：护士站/病区管理端
     * @return 落库后的绑定视图（含雪花 id）；201
     * @throws BizException IOT-1006（404 设备不存在）/ IOT-1007（409 设备停用）/
     *                      IOT-1010（409 已有生效绑定）/ IOT-1011（409 患者冻结合并或无在途就诊）
     */
    @PostMapping("/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public BindingVO bind(@Valid @RequestBody BindDeviceRequest request) {
        return bindingService.bind(request);
    }

    /**
     * 解绑设备（POST /api/v1/iot/bindings/{deviceId}/unbind；WRITE 审计）。
     *
     * @param deviceId IoTDA 设备标识（路径变量）
     * @param request  解绑请求体（原因强制，@Valid），非空
     * @return 204 无体（BOUND→UNBINDING→UNBOUND 双迁移完成）
     * @throws BizException IOT-1010（400 原因空白 / 409 无绑定或并发 CAS 落败）
     */
    @PostMapping("/bindings/{deviceId}/unbind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void unbind(@PathVariable String deviceId, @Valid @RequestBody UnbindDeviceRequest request) {
        bindingService.unbind(deviceId, request);
    }

    /**
     * 绑定记录分页查询（GET /api/v1/iot/bindings；纯读）。
     *
     * @param request 查询参数载体（deviceId/wardId/status 过滤可空，page 0 基缺省 0、size 缺省 20）
     * @return 绑定视图分页出参；200
     */
    @GetMapping("/bindings")
    public PageResult<BindingVO> page(@Valid BindingQueryRequest request) {
        return bindingService.page(request);
    }

    /**
     * 病区生效绑定清单（GET /api/v1/iot/bindings/wards/{wardId}；M05 播报路由/M16 病区设备墙查询面）。
     *
     * @param wardId 病区 id（路径变量）
     * @return 该病区 BOUND 绑定视图清单；200（无绑定为空数组）
     */
    @GetMapping("/bindings/wards/{wardId}")
    public List<BindingVO> listByWard(@PathVariable Long wardId) {
        return bindingService.listByWard(wardId);
    }

    /**
     * 设备当前生效绑定查询（GET /api/v1/iot/bindings/devices/{deviceId}/active）。
     *
     * @param deviceId IoTDA 设备标识（路径变量）
     * @return 生效绑定视图；200
     * @throws BizException IOT-1009（404 设备无 BOUND 生效绑定）
     */
    @GetMapping("/bindings/devices/{deviceId}/active")
    public BindingVO activeByDevice(@PathVariable String deviceId) {
        return bindingService
                .findActiveByDevice(deviceId)
                .orElseThrow(() ->
                        new BizException(IotErrorCode.BINDING_NOT_FOUND, HttpStatus.NOT_FOUND, "设备无生效绑定：" + deviceId));
    }
}
