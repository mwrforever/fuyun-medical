package com.fuyun.iot.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.GatewayQueryRequest;
import com.fuyun.iot.dto.SaveGatewayRequest;
import com.fuyun.iot.entity.IotGatewayEntity;
import com.fuyun.iot.vo.GatewayVO;

/**
 * 边缘网关管理服务（iot.iot_gateway 唯一写入口，FU-M14-12 本地档案面）：模式 B/C 网关 CRUD
 * 与热备对端校验。网关注册与子设备拓扑经 IoTDA 维护（Registry 直通，Spec「拓扑经 IoTDA 维护」），
 * 本服务只承载本地档案与联动展示——禁在此发起 Registry 调用。
 *
 * <p>配对纪律（宪法 A.4.3-20）：本服务为教科书式单表 CRUD（无跨表聚合编排），接口侧
 * {@code extends IService<IotGatewayEntity>}——主表分页/批量等通用能力直接复用 IService 契约面，
 * 实现侧对应 {@code extends ServiceImpl<IotGatewayMapper, IotGatewayEntity>}（A.4.3-13 主表
 * 查询走实现内置链式）。
 *
 * <p>standby 校验语义（brief 冻结「校验语义取贴切实现并注记」）：standby_of 非空时①须指向存在
 * 网关（404 词表借承不贴切——属保存请求的字段关系校验，统一 409 IOT-1025）、②禁自引用、③禁
 * 成环（沿 standby 链游走，回到自身即环，链上已见集合防既有脏数据死循环）。
 */
public interface IGatewayService extends IService<IotGatewayEntity> {

    /**
     * 网关分页查询（管理台工作台数据源；gateway_id 升序稳定输出）。
     *
     * @param request 分页查询请求（wardId/mode/status/gatewayId 过滤可空，page 缺省 0、size 缺省 20），
     *                非空
     * @return 网关视图分页（page 0 基回显），非空
     */
    PageResult<GatewayVO> page(GatewayQueryRequest request);

    /**
     * 登记网关（自然键落行 + 热备对端校验）。
     *
     * @param request 保存请求（gatewayId 非空为自然键），非空
     * @return 落库后的网关视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1024（409 gateway_id 已存在）/
     *                 IOT-1025（409 standby_of 校验不通过：对端不存在/自引用/成环）
     */
    GatewayVO create(SaveGatewayRequest request);

    /**
     * 更新网关档案（全字段覆写 + 热备对端校验；以路径 gatewayId 定位，请求体同名字段仅契约回显）。
     *
     * @param gatewayId 网关标识（路径变量），非空
     * @param request   保存请求，非空
     * @return 更新后的网关视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1023（404 网关不存在）/
     *                 IOT-1025（409 standby_of 校验不通过：对端不存在/自引用/成环）
     */
    GatewayVO update(String gatewayId, SaveGatewayRequest request);

    /**
     * 删除网关（软删）：有其他网关以本网关为热备对端时拒绝（防删除后悬挂 standby_of 引用）。
     *
     * @param gatewayId 网关标识（路径变量），非空
     * @throws com.fuyun.common.exception.BizException IOT-1023（404 网关不存在）/
     *                 IOT-1025（409 存在其他网关引用本网关为热备对端，热备关系先行解除）
     */
    void delete(String gatewayId);
}
