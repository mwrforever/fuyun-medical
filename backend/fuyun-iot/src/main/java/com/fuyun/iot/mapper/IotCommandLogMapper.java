package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotCommandLogEntity;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 命令日志 mapper：命令状态机写面的 CAS 唯一入口（GC23 形态：状态迁移一律 {@code @Update} +
 * 影响行数判定，显式补 deleted=0——并发结果帧/回执双写以旧状态限定兜底，终态不可变更红线）。
 *
 * <p>两条 CAS：终态迁移（casTerminal——同步回执/超时判定/结果帧终态共用）与送达中间态
 * （casDelivered）；一条结果归属读：设备维度最早未终态异步行（selectOutstandingAsync，部分
 * 索引 idx_iot_command_async_outstanding 准入）。必须标注 {@code @Mapper}：app 侧
 * MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotCommandLogMapper extends BaseMapper<IotCommandLogEntity> {

    /**
     * 终态迁移 CAS：ISSUED/DELIVERED → SUCCESS/FAILED/TIMEOUT（终态不可变更——CAS 零行即行已
     * 终态或不存在，调用方容错跳过；并发回执与结果帧竞态仅首个迁移方生效并发布事件）。
     *
     * @param commandNo 命令业务号，非空；来源：命令行定位
     * @param status    终态值（SUCCESS/FAILED/TIMEOUT 的 code），非空
     * @param resultAt  结果时刻（回执/超时判定/结果帧时点），非空
     * @param errorMsg  失败原因（SUCCESS 为 null；其余为失败摘要/判定文案），可空
     * @param operator  操作人（更新留痕：下发操作者或消费侧 system），非空
     * @return 影响行数（1=本次迁移方，调用方发布 iot.command.completed；0=已终态/行不存在，跳过）
     */
    @Update("UPDATE iot.iot_command_log SET status = #{status}, result_at = #{resultAt}, error_msg = #{errorMsg}, "
            + "updated_by = #{operator} "
            + "WHERE command_no = #{commandNo} AND status IN ('ISSUED','DELIVERED') AND deleted = 0")
    int casTerminal(
            @Param("commandNo") String commandNo,
            @Param("status") String status,
            @Param("resultAt") OffsetDateTime resultAt,
            @Param("errorMsg") String errorMsg,
            @Param("operator") String operator);

    /**
     * 送达中间态 CAS：ISSUED → DELIVERED（结果帧 DELIVERED 驱动；已 DELIVERED 幂等零行）。
     *
     * @param commandNo 命令业务号，非空；来源：结果归属行
     * @return 影响行数（1=置为已送达；0=行已终态/已送达/不存在，跳过）
     */
    @Update("UPDATE iot.iot_command_log SET status = 'DELIVERED', updated_by = 'system' "
            + "WHERE command_no = #{commandNo} AND status = 'ISSUED' AND deleted = 0")
    int casDelivered(@Param("commandNo") String commandNo);

    /**
     * 异步结果归属读：设备维度最早未终态异步行（IoTDA 离线命令按送达序 FIFO 执行——最早下发的
     * 未终态行即当前结果帧归属行；结果帧无本地命令号映射（confirm_ref 为凭证引用），按序归属
     * 为仓库无真实样例阶段的口径，真实联调如可携带平台命令标识则改为按号定位）。
     *
     * @param deviceId 设备号，非空；来源：结果帧 deviceId
     * @return 未终态异步行（ISSUED/DELIVERED 中最早 issued_at），可空（无未完成异步行=null，
     *         调用方容错跳过）
     */
    @Select("SELECT * FROM iot.iot_command_log WHERE device_id = #{deviceId} AND deliver_mode = 'ASYNC' "
            + "AND status IN ('ISSUED','DELIVERED') AND deleted = 0 ORDER BY issued_at ASC, id ASC LIMIT 1")
    IotCommandLogEntity selectOutstandingAsync(@Param("deviceId") String deviceId);
}
