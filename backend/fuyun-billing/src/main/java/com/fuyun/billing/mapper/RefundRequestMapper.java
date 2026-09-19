package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.RefundRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 退费申请 mapper：单表操作经 BaseMapper 链式能力（无 XML，宪法 A.4.3-15），另声明退费执行
 * CAS 抢锚语句（W-16，与 SettlementMapper.casMarkSettled 同族）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface RefundRequestMapper extends BaseMapper<RefundRequest> {

    /**
     * 退费执行 CAS 抢锚（W-16，与 SettlementMapper.casMarkSettled 同族）：仅 APPROVED 可迁移——
     * 并发双发同退费单恰一赢，输家重读定性（已 EXECUTED 幂等直返/否则 BILL-1019）；
     * 动卡入账严格后置于锚抢占成功（结算收口同款时序）。原生 SQL 状态字面量与
     * {@code RefundStatus} code 同源（APPROVED/EXECUTED 恒等常量名）；deleted=0 显式补齐
     * （@TableLogic 仅自动作用于 wrapper，注解 SQL 不继承）。
     *
     * @param id 退费申请 id
     * @return 影响行数（0=非 APPROVED 并发被抢/状态违例）
     */
    @Update("UPDATE billing.refund_request SET status = 'EXECUTED' "
            + "WHERE id = #{id} AND status = 'APPROVED' AND deleted = 0")
    int casMarkExecuted(@Param("id") long id);
}
