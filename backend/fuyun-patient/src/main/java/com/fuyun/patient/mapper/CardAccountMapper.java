package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.CardAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 一卡通账户 mapper：单表操作经 BaseMapper/IService 链式能力（无 XML，宪法 A.4.3-15），
 * 另声明余额唯一写点的原子记账语句（审查 I5）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface CardAccountMapper extends BaseMapper<CardAccount> {

    /**
     * 原子记账并回读记账后余额（PG RETURNING：UPDATE 与余额回读单语句完成，消除并发窗口——审查 I5）。
     * 软删行不可命中（EX-23：注解 SQL 不继承 @TableLogic，deleted=0 显式补齐——与 billing
     * DepositAccountMapper.mutateBalance 对齐口径；软删命中即死账户复活记账）。
     *
     * @param accountId 账户 id，非空；来源：CardTxnRecord
     * @param delta     余额增量（分；入账为正、出账为负，由服务层按 txn_type 折算），非 0
     * @return 记账后余额（分）；账户不存在、非 ACTIVE 或已逻辑删时无行返回为 null
     */
    @Select("UPDATE patient.card_account SET balance = balance + #{delta} "
            + "WHERE id = #{accountId} AND status = 'ACTIVE' AND deleted = 0 RETURNING balance")
    Long recordBalance(@Param("accountId") long accountId, @Param("delta") long delta);
}
