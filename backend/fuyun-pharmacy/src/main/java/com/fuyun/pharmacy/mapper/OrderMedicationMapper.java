package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.OrderMedication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 住院医嘱用药快照 mapper：单表链式能力（插入/按医嘱号查询），幂等唯一约束
 * uk_medication_order_no 由 DDL 兜底；重提刷新走注解 SQL（freq_code 透传可空——链式
 * updateById 缺省非空策略不写 null 列，「长期转临时」重提须显式清空列值保持快照权威）。
 */
@Mapper
public interface OrderMedicationMapper extends BaseMapper<OrderMedication> {

    /**
     * 重提头值面 + 明细快照同语句刷新（M04 resubmit 头值可变面：freqCode/items；定位 id 补
     * deleted=0 与链式写同守卫）。freq_code 透传可空（临时医嘱为 null），与对端 M04
     * updateResubmitValues 语义同源——快照以重发事件载荷为唯一权威。
     *
     * @param id        快照行 id，非空
     * @param freqCode  本次事件频次编码（长期非空/临时 null），可空
     * @param itemsJson 本次事件明细快照 JSON，非空
     * @return 影响行数（0=行不存在或并发逻辑删；重开前置已查得实体，理论不可达，调用方不判）
     */
    @Update("UPDATE pharmacy.order_medication SET freq_code = #{freqCode}, items = #{itemsJson} "
            + "WHERE id = #{id} AND deleted = 0")
    int refreshResubmitted(
            @Param("id") long id, @Param("freqCode") String freqCode, @Param("itemsJson") String itemsJson);
}
