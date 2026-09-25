package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.Bed;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 床位 mapper：单表链式能力 + 五态状态机条件更新注解 SQL 全集（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0；状态字面量与 V903 列值域、BedStatus code 逐字同源）。
 * 防重复占床硬防线：casOccupy 限定 FREE/RESERVED 两态可占（0 行=并发占床，调用方定性
 * IP-1006）；消毒/维修中分配由服务层前置校验拒绝（IP-1005）。
 */
@Mapper
public interface BedMapper extends BaseMapper<Bed> {

    /**
     * 占床 CAS（FREE/RESERVED→OCCUPIED）：占用主体同语句落值——防重复占床硬防线
     * （仅 FREE/RESERVED 两态可占，DISINFECTING/MAINTENANCE 一律 0 行）。
     *
     * @param bedId  床位 id，非空
     * @param visitId 占用主体住院就诊号（I 型 14 位），非空
     * @return 影响行数（0=消毒/维修/已占用态不可占床，调用方定性 IP-1005/IP-1006）
     */
    @Update("UPDATE inpatient.bed SET status = 'OCCUPIED', visit_id = #{visitId} "
            + "WHERE id = #{bedId} AND status IN ('FREE', 'RESERVED') AND deleted = 0")
    int casOccupy(@Param("bedId") Long bedId, @Param("visitId") String visitId);

    /**
     * 预占 CAS（FREE→RESERVED）：预约入院/转科预占/全院一张床签床入口；预占不绑定 visit_id
     * （登记确认才签发，故置 NULL 防御脏数据残留）。
     *
     * @param bedId 床位 id，非空
     * @return 影响行数（0=非 FREE 态（已预占/占用/消毒/维修），调用方定性 IP-1005/IP-1006）
     */
    @Update("UPDATE inpatient.bed SET status = 'RESERVED', visit_id = NULL "
            + "WHERE id = #{bedId} AND status = 'FREE' AND deleted = 0")
    int casReserve(@Param("bedId") Long bedId);

    /**
     * 释放预占 CAS（RESERVED→FREE）：住院证作废（SCHEDULED 态）释放预占床位联动入口。
     *
     * @param bedId 床位 id，非空
     * @return 影响行数（0=非 RESERVED 态，调用方定性 IP-1005）
     */
    @Update("UPDATE inpatient.bed SET status = 'FREE', visit_id = NULL "
            + "WHERE id = #{bedId} AND status = 'RESERVED' AND deleted = 0")
    int casRelease(@Param("bedId") Long bedId);

    /**
     * 转出流转 CAS（OCCUPIED→DISINFECTING）：转科/转床/出院的转出床终末消毒流转；占用主体
     * 同语句校验（bed_id + visit_id 双条件——防止误流转他人占用的床位）并清冗余列。
     *
     * @param bedId  床位 id，非空
     * @param visitId 转出主体住院就诊号（I 型 14 位），非空
     * @return 影响行数（0=非 OCCUPIED 态或占用主体不符，调用方定性 IP-1023）
     */
    @Update("UPDATE inpatient.bed SET status = 'DISINFECTING', visit_id = NULL "
            + "WHERE id = #{bedId} AND status = 'OCCUPIED' AND visit_id = #{visitId} AND deleted = 0")
    int casDisinfect(@Param("bedId") Long bedId, @Param("visitId") String visitId);

    /**
     * 消毒完成 CAS（DISINFECTING→FREE）：终末消毒完成确认，床位回可分配池。
     *
     * @param bedId 床位 id，非空
     * @return 影响行数（0=非 DISINFECTING 态，调用方定性 IP-1005）
     */
    @Update("UPDATE inpatient.bed SET status = 'FREE' "
            + "WHERE id = #{bedId} AND status = 'DISINFECTING' AND deleted = 0")
    int casDisinfectDone(@Param("bedId") Long bedId);

    /**
     * 转维修 CAS（FREE→MAINTENANCE）：床位维修停用。
     *
     * @param bedId 床位 id，非空
     * @return 影响行数（0=非 FREE 态（占用/预占/消毒中），调用方定性 IP-1005）
     */
    @Update("UPDATE inpatient.bed SET status = 'MAINTENANCE' "
            + "WHERE id = #{bedId} AND status = 'FREE' AND deleted = 0")
    int casMaintain(@Param("bedId") Long bedId);

    /**
     * 维修恢复 CAS（MAINTENANCE→FREE）：维修完成回可分配池。
     *
     * @param bedId 床位 id，非空
     * @return 影响行数（0=非 MAINTENANCE 态，调用方定性 IP-1005）
     */
    @Update("UPDATE inpatient.bed SET status = 'FREE' "
            + "WHERE id = #{bedId} AND status = 'MAINTENANCE' AND deleted = 0")
    int casMaintainDone(@Param("bedId") Long bedId);
}
