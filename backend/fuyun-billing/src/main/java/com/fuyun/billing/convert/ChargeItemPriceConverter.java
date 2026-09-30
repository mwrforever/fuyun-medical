package com.fuyun.billing.convert;

import com.fuyun.billing.entity.ChargeItemPrice;
import com.fuyun.billing.vo.ChargeItemPriceVO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 价格版本域 MapStruct 转换器（backend 宪法 A.7-4；BUG-23 迁入 ChargeItemPriceVO.from 手写直映）：
 * 价格版本实体 → 出参 VO 直映集中点——实体审计列不在出参契约内由转换器自动忽略；单价 price 为
 * Long（分）同型直传零换算（原手写即原样拷贝，无数值运算），出网转字符串归 Jackson 层
 * （JacksonLongToStringConfig）非转换器职责，等价单测锁 Long 全值透传禁数值化；版本状态/价格
 * 来源枚举与出参同型直映零翻译。
 *
 * <p>componentModel 取默认（非 spring）：controller 侧经 {@link #INSTANCE} 静态获取
 * （outpatient 转换器同款范式，宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface ChargeItemPriceConverter {

    /** 默认组件模型的生成实现获取入口（单测与 controller 调用同源） */
    ChargeItemPriceConverter INSTANCE = Mappers.getMapper(ChargeItemPriceConverter.class);

    /**
     * 价格版本实体 → 出参直映（10 字段；effectiveTo/approvalNo 可空直传——当前有效版本无失效止、
     * 协议价外无批文号时为 null）。
     *
     * @param price 价格版本实体，非空；来源：service 事务内版本链查询结果
     * @return 价格版本出参，非空；price（分）Long 原样透传
     */
    ChargeItemPriceVO toVO(ChargeItemPrice price);
}
