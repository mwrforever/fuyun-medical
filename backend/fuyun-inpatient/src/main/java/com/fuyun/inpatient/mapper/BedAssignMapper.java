package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.BedAssign;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 床位占用流水 mapper：只增表开账（insert）+ 未闭合行闭合（GC26：条件更新一律 @Update +
 * 影响行数判定，显式补 deleted=0；ended_at IS NULL 即未闭合在用行，每床至多一条由
 * uk_bed_assign_open 部分唯一索引兜底）。历史闭合行禁改写（历史可溯红线）。
 */
@Mapper
public interface BedAssignMapper extends BaseMapper<BedAssign> {

    /**
     * 闭合未继行（ended_at 落值）：转科/转床/出院时转出床占用流水收口；bed_id + visit_id
     * 双条件防误闭合他人占用的流水。
     *
     * @param bedId   转出床位 id，非空
     * @param visitId 转出主体住院就诊号（I 型 14 位），非空
     * @param endedAt 闭合时点（应用服务器时钟，与事件 transferredAt 同源），非空
     * @return 影响行数（0=无未闭合行——数据不一致场景，调用方定性 IP-1023）
     */
    @Update("UPDATE inpatient.bed_assign SET ended_at = #{endedAt} "
            + "WHERE bed_id = #{bedId} AND visit_id = #{visitId} AND ended_at IS NULL AND deleted = 0")
    int closeOpen(
            @Param("bedId") Long bedId, @Param("visitId") String visitId, @Param("endedAt") OffsetDateTime endedAt);
}
