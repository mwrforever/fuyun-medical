package com.fuyun.iot.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.DeviceQueryRequest;
import com.fuyun.iot.dto.DeviceRegisterRequest;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.vo.DeviceCredentialResetVO;
import com.fuyun.iot.vo.DeviceShadowVO;
import com.fuyun.iot.vo.DeviceVO;

/**
 * 设备管理服务：设备注册/凭证换发/停用/影子查询的唯一业务出口（M14 Spec §5 FU-M14-03 注册面
 * 与 FU-M14-04 深化，P2 PR-2 Task 5）。
 *
 * <p>注册中心调用面收敛于 {@link com.fuyun.iot.registry.IotDeviceRegistry}（双实现经
 * IotRegistryConfig 装配），{@link com.fuyun.iot.registry.RegistryException} 由实现层统一转
 * IOT-1022（503）业务异常渲染；凭证红线（14-iot §9）：一机一密 secret 仅随注册/换发响应一次性
 * 透出，落库仅 credential_ref，禁日志禁落库。
 *
 * <p>主表配对（宪法 A.4.3-20）：单主表 iot_device 的 CRUD 型服务，接口继承 IService、实现已
 * 继承 ServiceImpl（半配对收拢，EX-08）；getById(String) 视图签名与 IService#getById(Serializable)
 * 构成重载并存，调用侧 String 实参恒解析到视图面。
 */
public interface IDeviceManageService extends IService<IotDeviceEntity> {

    /**
     * 注册设备（一机一密签发）：本地查重（重复 IOT-1008 前置拒绝，不触注册中心）→
     * Registry.registerDevice 签发凭证 → 本地 iot_device 落行（credential_ref 承载、secret
     * 不落库不落日志、初态 INACTIVE）。
     *
     * @param request 注册请求（deviceId/productId/deviceName/deviceType/accessMode 必填），
     *                非空；来源：管理台接入登记表单
     * @return 落库后的设备视图（credentialSecret 仅本次响应一次性透出），非空
     * @throws com.fuyun.common.exception.BizException IOT-1008（409 重复注册）/
     *                                                 IOT-1022（503 注册中心不可用）
     */
    DeviceVO register(DeviceRegisterRequest request);

    /**
     * 换发设备凭证（热更新语义）：Registry.resetDeviceCredential 换发 → 本地 credential_ref
     * 轮换 → 新 secret 经本次响应一次性返回，设备侧重置即生效自行重连。
     *
     * @param deviceId 设备标识（IoTDA 自然键），非空
     * @return 换发结果（新凭证引用 + 新 secret 一次性面），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）/
     *                                                 IOT-1022（503 注册中心不可用）
     */
    DeviceCredentialResetVO resetCredential(String deviceId);

    /**
     * 设备档案分页查询（ward/status/productId 过滤，device_id 升序稳定，page 0 基缺省 0/20）。
     *
     * @param request 查询参数载体（过滤全部可空），非空
     * @return 设备视图分页出参（无 secret 面），非空
     */
    PageResult<DeviceVO> page(DeviceQueryRequest request);

    /**
     * 设备详情查询。
     *
     * @param deviceId 设备标识（IoTDA 自然键），非空
     * @return 设备视图（无 secret 面），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）
     */
    DeviceVO getById(String deviceId);

    /**
     * 停用设备（任意状态 → DISABLED 可逆迁移）：DISABLED 置位 CAS（status≠DISABLED 条件更新），
     * 并发落败或已停用归 IOT-1007。
     *
     * @param deviceId 设备标识（IoTDA 自然键），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）/
     *                                                 IOT-1007（409 已停用或并发 CAS 落败）
     */
    void disable(String deviceId);

    /**
     * 查询设备影子（Registry.shadow 直通：desired/reported 双面属性快照）。
     *
     * @param deviceId 设备标识（IoTDA 自然键），非空
     * @return 影子视图（双面均非 null），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）/
     *                                                 IOT-1022（503 注册中心不可用）
     */
    DeviceShadowVO shadow(String deviceId);
}
