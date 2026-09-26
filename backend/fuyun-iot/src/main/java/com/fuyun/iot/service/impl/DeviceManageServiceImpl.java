package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.DeviceQueryRequest;
import com.fuyun.iot.dto.DeviceRegisterRequest;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.registry.DeviceCredential;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.RegistryDeviceSpec;
import com.fuyun.iot.registry.RegistryException;
import com.fuyun.iot.service.IDeviceManageService;
import com.fuyun.iot.vo.DeviceCredentialResetVO;
import com.fuyun.iot.vo.DeviceShadowVO;
import com.fuyun.iot.vo.DeviceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备管理服务实现（iot.iot_device 唯一注册写入口）：注册流水线（本地查重 IOT-1008 前置拒绝
 * → Registry 签发一机一密 → 本地落行 INACTIVE）、凭证重置热更新（credential_ref 轮换 + 新
 * secret 经响应一次性交付）、DISABLED 置位 CAS 与影子直通。
 *
 * <p>凭证红线（14-iot §9）：一机一密 secret 只允许出现在注册/换发的本次 HTTP 响应体内——落库
 * 仅 credential_ref，日志只记 deviceId 与 credentialRef，禁打印 DeviceCredential/凭证 VO 整体
 * （record toString 会直出 secret）。
 *
 * <p>注册中心不可用语义：{@link RegistryException} 在本层统一转 IOT-1022（503）业务异常，
 * 全局渲染器出 ProblemDetail；注册路径注册中心失败即整链失败（本地不落孤儿行）。
 *
 * <p>事务边界：注册/换发/停用均单行写路径不挂事务（注册中心外部慢调用禁裹进本地事务，
 * ProductServiceImpl 上架同口径）；查询面挂只读事务。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class DeviceManageServiceImpl extends ServiceImpl<IotDeviceMapper, IotDeviceEntity>
        implements IDeviceManageService {

    /** 设备注册中心契约：注册/换发/影子管理面唯一出口（双实现经 IotRegistryConfig 装配） */
    private final IotDeviceRegistry registry;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param registry 设备注册中心契约，非空
     */
    public DeviceManageServiceImpl(IotDeviceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public DeviceVO register(DeviceRegisterRequest request) {
        // 前置：本地档案查重（IOT-1008 409 写库前置拒绝，不触注册中心避免云端脏登记；
        // 走 baseMapper 直查——getById(String) 已被本服务视图出参签名占用）
        if (baseMapper.selectById(request.deviceId()) != null) {
            log.warn("设备注册拒绝：deviceId 重复：deviceId={}", request.deviceId());
            throw new BizException(
                    IotErrorCode.DEVICE_ALREADY_EXISTS, HttpStatus.CONFLICT, "设备已存在，禁止重复注册：" + request.deviceId());
        }
        // 注册第一环：注册中心签发一机一密（事务外外部调用——失败即整链失败，本地不落孤儿行）；
        // secret 仅随 credential 一次性面，本方法内禁打印 credential 整体
        DeviceCredential credential;
        try {
            credential = registry.registerDevice(new RegistryDeviceSpec(
                    request.deviceId(), request.nodeId(), request.productId(), request.deviceName()));
        } catch (RegistryException e) {
            log.error("设备注册失败（注册中心不可用）：deviceId={}，原因={}", request.deviceId(), e.getMessage(), e);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "注册中心不可用，设备注册失败：" + request.deviceId());
        }
        // 注册第二环：本地档案落行（credential_ref 承载引用，secret 不落库；初态 INACTIVE 未上线）
        IotDeviceEntity entity = new IotDeviceEntity();
        entity.setDeviceId(request.deviceId());
        entity.setNodeId(request.nodeId());
        entity.setProductId(request.productId());
        entity.setDeviceName(request.deviceName());
        entity.setDeviceType(request.deviceType());
        entity.setAccessMode(request.accessMode());
        entity.setCredentialRef(credential.credentialRef());
        entity.setStatus(DeviceStatus.INACTIVE);
        baseMapper.insert(entity);
        log.info(
                "设备注册落行完成：deviceId={}，productId={}，credentialRef={}，status=INACTIVE",
                entity.getDeviceId(),
                entity.getProductId(),
                entity.getCredentialRef());
        return DeviceVO.of(entity, credential.secret());
    }

    @Override
    public DeviceCredentialResetVO resetCredential(String deviceId) {
        requireDevice(deviceId);
        // 换发第一环：注册中心换发新 secret（事务外外部调用，失败本地 credential_ref 不变）
        DeviceCredential credential;
        try {
            credential = registry.resetDeviceCredential(deviceId);
        } catch (RegistryException e) {
            log.error("设备凭证换发失败（注册中心不可用）：deviceId={}，原因={}", deviceId, e.getMessage(), e);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "注册中心不可用，凭证换发失败：" + deviceId);
        }
        // 换发第二环：本地 credential_ref 轮换（单行 UPDATE 仅投影引用列，secret 不落库）
        lambdaUpdate()
                .eq(IotDeviceEntity::getDeviceId, deviceId)
                .set(IotDeviceEntity::getCredentialRef, credential.credentialRef())
                .update();
        log.info("设备凭证换发完成（热更新，设备侧自行重连生效）：deviceId={}，credentialRef={}", deviceId, credential.credentialRef());
        return new DeviceCredentialResetVO(deviceId, credential.credentialRef(), credential.secret());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DeviceVO> page(DeviceQueryRequest request) {
        // 缺省补齐：page 0 基缺省 0、size 缺省 20（GET 幂等查询无强制必填面）
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：ward/status/productId 过滤缺席即不过滤；device_id 升序稳定（自然键字典序）
        Page<IotDeviceEntity> result = lambdaQuery()
                .eq(request.wardId() != null, IotDeviceEntity::getWardId, request.wardId())
                .eq(request.status() != null, IotDeviceEntity::getStatus, request.status())
                .eq(request.productId() != null, IotDeviceEntity::getProductId, request.productId())
                .orderByAsc(IotDeviceEntity::getDeviceId)
                .page(new Page<>(page + 1, size));
        return PageResult.of(result.getRecords().stream().map(DeviceVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceVO getById(String deviceId) {
        return DeviceVO.from(requireDevice(deviceId));
    }

    @Override
    public void disable(String deviceId) {
        IotDeviceEntity device = requireDevice(deviceId);
        // 状态机守卫：已停用再停用即状态违例（IOT-1007 409）
        if (device.getStatus() == DeviceStatus.DISABLED) {
            log.warn("设备停用拒绝：已处停用状态：deviceId={}", deviceId);
            throw new BizException(
                    IotErrorCode.DEVICE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "设备已停用，禁止重复停用：" + deviceId);
        }
        // DISABLED 置位 CAS：status≠DISABLED 条件更新（并发窗口下重复请求由影响行数兜底判违例）
        boolean updated = lambdaUpdate()
                .eq(IotDeviceEntity::getDeviceId, deviceId)
                .ne(IotDeviceEntity::getStatus, DeviceStatus.DISABLED)
                .set(IotDeviceEntity::getStatus, DeviceStatus.DISABLED)
                .update();
        if (!updated) {
            // CAS 落败：并发状态迁移竞态（如状态帧同步置位），按状态违例处置
            log.warn("设备停用 CAS 未命中（并发竞态）：deviceId={}", deviceId);
            throw new BizException(
                    IotErrorCode.DEVICE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "设备状态已变更，禁止停用：" + deviceId);
        }
        log.info("设备停用完成：deviceId={}，{}→DISABLED", deviceId, device.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceShadowVO shadow(String deviceId) {
        // 前置：本地档案存在性守卫（404 IOT-1006——本地档案为存在性权威，不透传注册中心 503）
        requireDevice(deviceId);
        // 影子直通：desired/reported 双面原样映射，不做二次加工
        try {
            return DeviceShadowVO.from(registry.shadow(deviceId));
        } catch (RegistryException e) {
            log.error("设备影子查询失败（注册中心不可用）：deviceId={}，原因={}", deviceId, e.getMessage(), e);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "注册中心不可用，影子查询失败：" + deviceId);
        }
    }

    /**
     * 设备存在性校验（404 守卫）。
     *
     * @param deviceId 设备标识（IoTDA 自然键），非空
     * @return 命中的档案实体，非空
     * @throws BizException IOT-1006（404 设备不存在）
     */
    private IotDeviceEntity requireDevice(String deviceId) {
        // 走 baseMapper 直查：getById(String) 已被本服务视图出参签名占用（避免重载递归）
        IotDeviceEntity entity = baseMapper.selectById(deviceId);
        if (entity == null) {
            log.warn("设备不存在：deviceId={}", deviceId);
            throw new BizException(IotErrorCode.DEVICE_NOT_FOUND, HttpStatus.NOT_FOUND, "设备不存在：" + deviceId);
        }
        return entity;
    }
}
