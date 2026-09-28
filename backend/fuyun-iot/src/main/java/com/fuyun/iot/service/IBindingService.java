package com.fuyun.iot.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.BindDeviceRequest;
import com.fuyun.iot.dto.BindingQueryRequest;
import com.fuyun.iot.dto.UnbindDeviceRequest;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.vo.BindingVO;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 设备患者绑定管理服务（iot.iot_binding 唯一写入口，M14 绑定五元组管理域）：绑定/解绑生命周期、
 * 管理端分页与病区/设备维度的生效绑定查询。遥测入库富化经 {@link #listActiveByDevices} 批量
 * 通道复用同一绑定视图（{@link #findActiveByDevice} 委托同渠道取单元素；单点口径，禁旁路快照查询）。
 *
 * <p>写路径语义：bind 校验链（设备存在且非 DISABLED[IOT-1006/1007]→无生效绑定[IOT-1010]→患者
 * 归一非冻结/合并中且存在在途就诊——OngoingVisitQuery 集合任一实现命中即在途[IOT-1011]）→
 * 落 BOUND 行 + 事务内发布 iot.binding.changed（BIND，经 IotDomainPublisher AFTER_COMMIT 出 MQ）；
 * unbind 原因强制[IOT-1010] + 状态 CAS BOUND→UNBINDING→UNBOUND 双迁移 + 发布 changeType=UNBIND。
 */
public interface IBindingService extends IService<IotBindingEntity> {

    /**
     * 绑定设备到患者（绑定快照五元组落行 + BIND 事件）。
     *
     * @param req 绑定请求（设备/患者/就诊/床位/病区/绑定模式/原因），非空；来源：护士站/病区管理端
     * @return 落库后的绑定视图（含雪花 id），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404 设备不存在）/
     *                 IOT-1007（409 设备停用 DISABLED 拒绑定）/
     *                 IOT-1010（409 设备已有生效绑定——uk_iot_binding_device_bound 应用层前置 +
     *                 唯一索引竞态兜底）/
     *                 IOT-1011（409 患者冻结/合并中，或无在途就诊）
     */
    BindingVO bind(BindDeviceRequest req);

    /**
     * 解绑设备（原因强制 + BOUND→UNBINDING→UNBOUND 双 CAS 迁移 + UNBIND 事件）。
     *
     * @param deviceId IoTDA 设备标识，非空；来源：路径变量
     * @param req      解绑请求（原因强制，空白即拒），非空
     * @throws com.fuyun.common.exception.BizException IOT-1010（400 解绑原因缺失或空白）/
     *                 IOT-1010（409 设备无 BOUND 绑定、或并发下 CAS 迁移被抢行数不足）
     */
    void unbind(String deviceId, UnbindDeviceRequest req);

    /**
     * 绑定记录分页查询（管理端工作台数据源；id 倒序 = 新绑定在前）。
     *
     * @param req 分页查询请求（deviceId/wardId/status 过滤可空，page 缺省 0、size 缺省 20），非空
     * @return 绑定视图分页（page 0 基回显），非空
     */
    PageResult<BindingVO> page(BindingQueryRequest req);

    /**
     * 按病区列出当前生效（BOUND）绑定（M05 播报路由与 M16 病区设备墙查询面）。
     *
     * @param wardId 病区 id，非空；来源：路径变量
     * @return 该病区 BOUND 绑定视图清单（id 升序），非空；无绑定为空清单
     */
    List<BindingVO> listByWard(Long wardId);

    /**
     * 查设备当前生效（BOUND）绑定：设备当前归属查询复用入口（内部委托批量方法取单元素）。
     *
     * @param deviceId IoTDA 设备标识，非空；来源：路径变量
     * @return 生效绑定视图；设备无 BOUND 绑定时为 Optional.empty()（遥测仍入库仅无患者归属）
     */
    Optional<BindingVO> findActiveByDevice(String deviceId);

    /**
     * 按设备集合批量查当前生效（BOUND）绑定：遥测入库富化的唯一快照通道（单次 IN 查询，
     * 宪法 A.4.3-14 拒循环内单查；uk_iot_binding_device_bound 保证每设备至多一条活跃绑定）。
     *
     * @param deviceIds 设备标识集合（调用方先 distinct 去重），可空；null 或空集合均直接返回空清单
     *                  不触库（防空 IN 列表非法 SQL，与实现短路分支一致）；来源：遥测批内 distinct 设备号
     * @return 生效绑定视图清单（id 升序），非空；无命中为空清单
     */
    List<BindingVO> listActiveByDevices(Collection<String> deviceIds);
}
