package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 处方明细单表 mapper（计费行快照读写，单表链式能力），另声明退药累计回写一支原子语句
 * （V701 列注释「已退数量（退药回写）」的落地件，occupancy returnedQuantity 数据源）。
 */
@Mapper
public interface PrescriptionItemMapper extends BaseMapper<PrescriptionItem> {

    /**
     * 退药累计回写（退药受理 ISSUED_RETURN 时点专用，服务端原子累加禁读值覆写）：
     * {@code returned_quantity = returned_quantity + #{returnQty}}。口径依据 V701 列注释——
     * 「退药回写」指退药受理链（发后退药）累计；DISPENSING_CANCEL 发药中明细退场不计数
     * （该时点明细未实发，退场写面在 dispense_item.item_status=CANCELLED；prescription_item
     * 的 status 列零写入点——发后退药链仅经本语句回写 returned_quantity）。
     * deleted=0 显式补齐（注解 SQL 不继承 @TableLogic，与 FeeRecordMapper.casMarkFeesSettled 同范式）。
     *
     * @param prescriptionItemId 处方明细行 id（dispense_item.prescription_item_id 引用）；来源：退药明细行
     * @param returnQty          本次退药数量，非空且 &gt;0（数量守卫已由服务层 PH-1013 前置拦截）
     * @return 影响行数：1 累计成功；0=明细行缺失/已逻辑删（脏数据），调用方须显式拒并整体回滚
     */
    @Update("UPDATE pharmacy.prescription_item SET returned_quantity = returned_quantity + #{returnQty} "
            + "WHERE id = #{prescriptionItemId} AND deleted = 0")
    int accumulateReturnedQuantity(
            @Param("prescriptionItemId") long prescriptionItemId, @Param("returnQty") BigDecimal returnQty);
}
