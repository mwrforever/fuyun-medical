package com.fuyun.billing.convert;

import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.vo.ChargeItemVO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 收费项目库域 MapStruct 转换器（backend 宪法 A.7-4；BUG-23 迁入 ChargeItemVO.from 手写直映）：
 * 物价项目实体 → 出参 VO 直映集中点——实体审计列（createdAt/updatedAt/createdBy/updatedBy/deleted）
 * 不在出参契约内由转换器自动忽略，字段增删即编译失败（管理面字段演进时出参漏映射由编译期拦截，
 * 替代原手写 10 位逐字段拷贝的静默漏拷风险）；类别/收费标记/状态枚举与出参同型直映零翻译，
 * 本 VO 无金额列（单价走价格版本 VO）。
 *
 * <p>componentModel 取默认（非 spring）：controller 侧经 {@link #INSTANCE} 静态获取
 * （outpatient 转换器同款范式，宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface ChargeItemConverter {

    /** 默认组件模型的生成实现获取入口（单测与 controller 调用同源） */
    ChargeItemConverter INSTANCE = Mappers.getMapper(ChargeItemConverter.class);

    /**
     * 收费项目实体 → 出参直映（10 字段；execDeptId 可空直传——未配置默认执行科室时为 null）。
     *
     * @param item 项目实体，非空；来源：service 事务内查询/建档回读结果
     * @return 项目出参，非空
     */
    ChargeItemVO toVO(ChargeItem item);
}
