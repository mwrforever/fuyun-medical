package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.DeviceQueryRequest;
import com.fuyun.iot.dto.DeviceRegisterRequest;
import com.fuyun.iot.service.IDeviceManageService;
import com.fuyun.iot.vo.DeviceCredentialResetVO;
import com.fuyun.iot.vo.DeviceShadowVO;
import com.fuyun.iot.vo.DeviceVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备管理端点（/api/v1/iot/devices 六端点，FU-M14-03 注册面 + FU-M14-04 深化）：注册
 * （POST，201）、分页（GET）、详情（GET /{deviceId}）、凭证重置（POST /{deviceId}/credential-reset，
 * 热更新——新 secret 一次性弹窗交付）、停用（POST /{deviceId}/disable，204）、影子查询
 * （GET /{deviceId}/shadow）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用设备管理服务 + 编排响应，禁业务逻辑、
 * 禁 @Transactional（事务归 service impl 方法级）；注册/凭证重置/停用挂 WRITE 审计（@AuditLog
 * 注解 + M01 审计切面落 system.audit_log，操作人取 OperatorContextHolder），审计载荷仅业务
 * 标识不含 secret；查询端点纯读不挂写审计。注册中心不可用（IOT-1022）经全局渲染器出 503
 * ProblemDetail。装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/devices")
public class DeviceController {

    /** 设备管理服务：六端点唯一业务出口（注册流水线/凭证热更新/CAS 停用归服务层） */
    private final IDeviceManageService deviceManageService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param deviceManageService 设备管理服务，非空
     */
    public DeviceController(IDeviceManageService deviceManageService) {
        this.deviceManageService = deviceManageService;
    }

    /**
     * 设备注册（POST /api/v1/iot/devices；WRITE 审计）：一机一密签发，secret 仅随本次响应
     * 一次性透出（前端一次性弹窗承载，服务端不二次下发）。
     *
     * @param request 注册请求体（@Valid），非空；来源：管理台接入登记表单
     * @return 落库后的设备视图（credentialSecret 仅本次响应有效）；201
     * @throws com.fuyun.common.exception.BizException IOT-1008（409 重复注册）/
     *                                                 IOT-1022（503 注册中心不可用）
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public DeviceVO register(@Valid @RequestBody DeviceRegisterRequest request) {
        return deviceManageService.register(request);
    }

    /**
     * 设备档案分页查询（GET /api/v1/iot/devices；纯读）。
     *
     * @param request 查询参数载体（wardId/status/productId 过滤可空，page 0 基缺省 0、size 缺省 20）
     * @return 设备视图分页出参（无 secret 面）；200
     */
    @GetMapping
    public PageResult<DeviceVO> page(@Valid DeviceQueryRequest request) {
        return deviceManageService.page(request);
    }

    /**
     * 设备档案详情（GET /api/v1/iot/devices/{deviceId}；纯读，无 secret 面）。
     *
     * @param deviceId 设备标识（路径变量）
     * @return 设备视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）
     */
    @GetMapping("/{deviceId}")
    public DeviceVO detail(@PathVariable String deviceId) {
        return deviceManageService.getById(deviceId);
    }

    /**
     * 凭证重置（POST /api/v1/iot/devices/{deviceId}/credential-reset；WRITE 审计）：热更新
     * 语义——新 secret 经本次响应一次性返回，设备侧重置即生效自行重连。
     *
     * @param deviceId 设备标识（路径变量）
     * @return 换发结果（新凭证引用 + 新 secret 一次性面）；200
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）/
     *                                                 IOT-1022（503 注册中心不可用）
     */
    @PostMapping("/{deviceId}/credential-reset")
    @AuditLog(actionType = AuditActionType.WRITE)
    public DeviceCredentialResetVO resetCredential(@PathVariable String deviceId) {
        return deviceManageService.resetCredential(deviceId);
    }

    /**
     * 停用设备（POST /api/v1/iot/devices/{deviceId}/disable；WRITE 审计）：DISABLED 置位 CAS。
     *
     * @param deviceId 设备标识（路径变量）
     * @return 204 无体（DISABLED 置位完成，可逆回 INACTIVE）
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）/
     *                                                 IOT-1007（409 已停用或并发 CAS 落败）
     */
    @PostMapping("/{deviceId}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void disable(@PathVariable String deviceId) {
        deviceManageService.disable(deviceId);
    }

    /**
     * 设备影子查询（GET /api/v1/iot/devices/{deviceId}/shadow；纯读）：注册中心 desired/reported
     * 双面直通。
     *
     * @param deviceId 设备标识（路径变量）
     * @return 影子视图（双面均非 null）；200
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）/
     *                                                 IOT-1022（503 注册中心不可用）
     */
    @GetMapping("/{deviceId}/shadow")
    public DeviceShadowVO shadow(@PathVariable String deviceId) {
        return deviceManageService.shadow(deviceId);
    }
}
