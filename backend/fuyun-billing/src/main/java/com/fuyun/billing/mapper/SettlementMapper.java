package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.Settlement;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 结算单 mapper：单表操作经 BaseMapper 链式能力（无 XML，宪法 A.4.3-15），另声明正式结算
 * CAS 抢锚语句（2026-09-18 用户裁决：结算/退费并发缺口收口）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {

    /**
     * 正式结算锚点条件更新（CAS 抢锚）：仅当结算单仍处 DRAFT/PRESETTLED 可结算态时一次性落
     * SETTLED 终态字段——并发双发同单时，第二事务在本语句的行锁上等待，首事务提交后 WHERE 条件
     * 对新行版本重评估即不命中（READ COMMITTED 语义），影响行数 0 由服务层分流幂等直返/态不符拒。
     * 原生 SQL 状态字面量与 SettlementStatus code 同源（DRAFT/PRESETTLED/SETTLED 恒等常量名）；
     * deleted=0 显式补齐（@TableLogic 仅自动作用于 wrapper，注解 SQL 不继承）。
     *
     * @param id             结算单 id；来源：settle 按 settleNo 定位后的行主键
     * @param settledAt      正式结算时刻；来源：服务层事务内取 now()
     * @param paymentDetails 支付明细 JSON 文本（[{method,amount,channelRef}]，退费 execute 原路退回读回源）
     * @return 影响行数：1=抢锚成功（本事务独占该单结算权）；0=锚已被并发事务抢占或状态已迁移
     */
    @Update("UPDATE billing.settlement SET status = 'SETTLED', settled_at = #{settledAt}, "
            + "payment_details = #{paymentDetails} "
            + "WHERE id = #{id} AND status IN ('DRAFT', 'PRESETTLED') AND deleted = 0")
    int casMarkSettled(
            @Param("id") long id,
            @Param("settledAt") OffsetDateTime settledAt,
            @Param("paymentDetails") String paymentDetails);
}
