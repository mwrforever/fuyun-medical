package com.fuyun.billing.convert;

import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.vo.InsuranceMappingVO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 医保对照域 MapStruct 转换器（backend 宪法 A.7-4；BUG-23 迁入 InsuranceMappingVO.from 手写直映）：
 * 项目级 22 项编码对照实体 → 出参 VO 直映集中点——实体审计列不在出参契约内由转换器自动忽略；
 * 医保限价 limitPrice 为 Long（分）同型直传零换算、先自付比例 selfPayRatio 为 BigDecimal 同型
 * 直传保精度（列精度 DECIMAL(5,4) 出网不二次加工），等价单测锁两列全值透传禁数值化；
 * 对照类型/支付属性/对照状态枚举与出参同型直映零翻译。
 *
 * <p>componentModel 取默认（非 spring）：controller 侧经 {@link #INSTANCE} 静态获取
 * （outpatient 转换器同款范式，宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface InsuranceMappingConverter {

    /** 默认组件模型的生成实现获取入口（单测与 controller 调用同源） */
    InsuranceMappingConverter INSTANCE = Mappers.getMapper(InsuranceMappingConverter.class);

    /**
     * 医保对照实体 → 出参直映（10 字段；limitPrice/checkReceipt 可空直传——无限价/未校验回执时
     * 为 null）。
     *
     * @param mapping 对照实体，非空；来源：service 事务内生效对照查询结果
     * @return 对照出参，非空；limitPrice（分）与 selfPayRatio（0-1）原样透传
     */
    InsuranceMappingVO toVO(InsuranceMapping mapping);
}
