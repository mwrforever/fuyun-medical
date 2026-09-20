package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.Dispense;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 调剂单 mapper：单表链式能力 + 三段状态机 CAS 条件更新（注解 SQL 显式补 deleted=0；
 * 状态字面量与 DispenseStatus code 逐字同源；0 行=并发被抢/状态违例，调用方定性拒绝）。
 */
@Mapper
public interface DispenseMapper extends BaseMapper<Dispense> {

    /**
     * 通用状态 CAS。
     *
     * @param id   调剂单 id
     * @param from 期望现态 code，非空
     * @param to   目标态 code，非空
     * @return 影响行数（0=未命中）
     */
    @Update("UPDATE pharmacy.dispense SET status = #{to} " + "WHERE id = #{id} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    /**
     * 发药签名终笔（核对/发药人/发药时刻随 ISSUED 一并落行，双签法定留痕不可篡改）。
     *
     * @param id       调剂单 id
     * @param issuer   发药签名操作者，非空
     * @param issuedAt 发药时刻，非空
     * @return 影响行数（0=非 PICKED 并发被抢）
     */
    @Update("UPDATE pharmacy.dispense SET status = 'ISSUED', issuer = #{issuer}, issued_at = #{issuedAt} "
            + "WHERE id = #{id} AND status = 'PICKED' AND deleted = 0")
    int casIssue(@Param("id") long id, @Param("issuer") String issuer, @Param("issuedAt") OffsetDateTime issuedAt);
}
