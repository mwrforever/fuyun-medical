package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.enums.BindingStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 设备绑定 mapper：生效绑定查询（绑定域服务收口）与解绑状态机 CAS 条件更新（绑定历史只增，禁物理删）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface IotBindingMapper extends BaseMapper<IotBindingEntity> {

    /**
     * 解绑第一步 CAS：仅 BOUND 行迁移 UNBINDING（解绑动作受理）。
     * status 字面量与 {@link BindingStatus} code 同源；deleted = 0 显式补齐（注解 SQL 不继承
     * {@code @TableLogic}）。并发解绑/新绑定竞争时行锁串行化，落败方影响行数为 0。
     *
     * @param deviceId IoTDA 设备标识；来源：解绑端点路径变量
     * @return 影响行数：1=迁移成功；0=无 BOUND 行（含并发被抢），调用方须抛 IOT-1010 整体回滚
     */
    @Update("UPDATE iot.iot_binding SET status = 'UNBINDING' "
            + "WHERE device_id = #{deviceId} AND status = 'BOUND' AND deleted = 0")
    int casMarkUnbinding(@Param("deviceId") String deviceId);

    /**
     * 解绑第二步 CAS：仅 UNBINDING 行迁移 UNBOUND 终态并回填解绑留痕（原因 + 时刻）。
     * 双迁移设计承载状态机 BOUND→UNBINDING→UNBOUND 中间态语义（UNBINDING 期间遥测不挂新归属）；
     * 第二步落败（并发抢锚）抛 IOT-1010 回滚第一步，杜绝半迁移状态。unbound_at 取库端 now()
     * 与 updated_at 触发器同源时钟；status/deleted 口径同 {@link #casMarkUnbinding}。
     *
     * @param deviceId     IoTDA 设备标识；来源：解绑端点路径变量
     * @param unbindReason 解绑原因（服务层已强制非空白）；来源：操作员录入
     * @return 影响行数：1=迁移成功；0=无 UNBINDING 行（并发被抢），调用方须抛 IOT-1010 整体回滚
     */
    @Update("UPDATE iot.iot_binding SET status = 'UNBOUND', unbind_reason = #{unbindReason}, unbound_at = now() "
            + "WHERE device_id = #{deviceId} AND status = 'UNBINDING' AND deleted = 0")
    int casMarkUnbound(@Param("deviceId") String deviceId, @Param("unbindReason") String unbindReason);
}
