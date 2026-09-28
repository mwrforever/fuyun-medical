package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotConsumeErrorLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 消费错误日志 mapper：毒丸留痕落库唯一写入口与处置状态机 CAS 通道（重放/放弃为 P0 只写遗留
 * 义务的管理端补齐，P2 PR-2 Task 10）。
 *
 * <p>CAS 形态照 IotBindingMapper 解绑双迁移先例（注解 @Update 条件更新，影响行数判定落败方
 * 抛 IOT-1021）；status 字面量与 {@link com.fuyun.iot.enums.ConsumeErrorStatus} code 同源。
 * 必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotConsumeErrorLogMapper extends BaseMapper<IotConsumeErrorLogEntity> {

    /**
     * 重放认领 CAS：PENDING/REPLAYED 行迁移 REPLAYED 且 replay_count 累加（重放可多次语义）。
     * handled_by/handled_at/updated_by 同步留痕（updated_at 由库端触发器维护，应用层不写）。
     * 并发重放/放弃竞争时行锁串行化，落败方影响行数为 0。
     *
     * @param errorId  错误行 ID，非空
     * @param operator 处置操作人（无登录上下文回退 system），非空
     * @return 影响行数：1=认领成功；0=已放弃（终态禁重放）或并发被抢，调用方须抛 IOT-1021
     */
    @Update("UPDATE iot.iot_consume_error_log SET status = 'REPLAYED', replay_count = replay_count + 1, "
            + "handled_by = #{operator}, handled_at = now(), updated_by = #{operator} "
            + "WHERE error_id = #{errorId} AND status IN ('PENDING', 'REPLAYED')")
    int casMarkReplayed(@Param("errorId") Long errorId, @Param("operator") String operator);

    /**
     * 放弃处置 CAS：仅 PENDING 行迁移 ABANDONED 终态（状态机单向：已重放/已放弃不可再放弃）。
     * 原因由服务层追加进 errorMsg 一并入参承载（V401 无独立原因列，500 列宽截断防线在服务层）。
     *
     * @param errorId  错误行 ID，非空
     * @param errorMsg 追加强弃原因后的完整原因文本（服务层已截断），非空
     * @param operator 处置操作人（无登录上下文回退 system），非空
     * @return 影响行数：1=迁移成功；0=非 PENDING 行（并发被抢），调用方须抛 IOT-1021
     */
    @Update("UPDATE iot.iot_consume_error_log SET status = 'ABANDONED', error_msg = #{errorMsg}, "
            + "handled_by = #{operator}, handled_at = now(), updated_by = #{operator} "
            + "WHERE error_id = #{errorId} AND status = 'PENDING'")
    int casMarkAbandoned(
            @Param("errorId") Long errorId, @Param("errorMsg") String errorMsg, @Param("operator") String operator);
}
