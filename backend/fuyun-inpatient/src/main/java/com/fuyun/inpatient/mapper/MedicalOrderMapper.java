package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.MedicalOrder;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院医嘱主表 mapper：单表链式能力 + 状态条件更新注解 SQL（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0；状态字面量与 V904 列值域、OrderStatus code 逐字同源）。
 * 状态迁移唯一执行面为 OrderStateMachineService（casTransferStatus）——模块外直写医嘱状态
 * 为 04 Spec 红线 2 违例；停嘱值面（end_at/stop_reason）由业务服务在状态迁移后同事务补写
 * （updateStopValues，非状态面更新）。
 */
@Mapper
public interface MedicalOrderMapper extends BaseMapper<MedicalOrder> {

    /**
     * 状态迁移 CAS（OrderStateMachineService 唯一执行面）：from 态限定更新至 to 态，
     * 0 行定性状态机违例（并发迁移/终态/行缺失），调用方按 IP-1010 处置。
     *
     * @param orderNo   医嘱号，非空
     * @param fromState 迁移前状态 code（OrderStatus），非空
     * @param toState   迁移后状态 code（OrderStatus），非空
     * @param operator  操作者（审计留痕），非空
     * @return 影响行数（0=from 态不匹配或行不存在）
     */
    @Update("UPDATE inpatient.medical_order SET status = #{toState}, updated_by = #{operator} "
            + "WHERE order_no = #{orderNo} AND status = #{fromState} AND deleted = 0")
    int casTransferStatus(
            @Param("orderNo") String orderNo,
            @Param("fromState") String fromState,
            @Param("toState") String toState,
            @Param("operator") String operator);

    /**
     * 停嘱值面补写（非状态面——状态已由 casTransferStatus 迁移）：停嘱时点（服务器时钟）
     * 与停嘱原因落值；独立于状态 CAS 语句以保持状态机执行面单一。
     *
     * @param orderNo    医嘱号，非空
     * @param endAt      停嘱时点（服务器时间），非空
     * @param stopReason 停嘱原因（转科固定文案/医生停嘱理由），非空
     * @param operator   操作者（审计留痕），非空
     * @return 影响行数（0=行不存在或并发逻辑删，调用方定性数据冲突）
     */
    @Update("UPDATE inpatient.medical_order SET end_at = #{endAt}, stop_reason = #{stopReason}, "
            + "updated_by = #{operator} WHERE order_no = #{orderNo} AND deleted = 0")
    int updateStopValues(
            @Param("orderNo") String orderNo,
            @Param("endAt") OffsetDateTime endAt,
            @Param("stopReason") String stopReason,
            @Param("operator") String operator);
}
