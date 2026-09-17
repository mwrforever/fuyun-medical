package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.DepositAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 押金账户 mapper：单表链式 + 余额原子记账（CardAccountMapper.recordBalance 实证先例） */
@Mapper
public interface DepositAccountMapper extends BaseMapper<DepositAccount> {

    /**
     * 原子增减余额并回读（并发缴存/抵扣串行化，欠费判定以回读值为准）。
     *
     * @param accountId 账户 id
     * @param delta     变动量（分，缴入正/抵扣负）
     * @return 变动后余额；null=账户不存在或非 NORMAL/ARREARS 可记账态
     */
    @Select("UPDATE billing.deposit_account SET balance = balance + #{delta} "
            + "WHERE id = #{accountId} AND status IN ('NORMAL','ARREARS') AND deleted = 0 RETURNING balance")
    Long mutateBalance(@Param("accountId") long accountId, @Param("delta") long delta);
}
