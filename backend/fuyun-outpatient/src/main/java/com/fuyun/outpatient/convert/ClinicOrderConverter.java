package com.fuyun.outpatient.convert;

import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.ClinicOrderItem;
import com.fuyun.outpatient.vo.ClinicOrderVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 申请单域 MapStruct 转换器（backend 宪法 A.7-4；BUG-20 迁入 ClinicOrderServiceImpl 手写直映）：
 * 申请单主单+明细行实体 → 出参 VO 直映集中点——主单 11 字段与明细行 3 字段（itemCode/quantity/
 * usageSummary）均为纯直映，record 构造器映射按组件名对位生成，字段增删/调序即编译失败。
 * quantity 为 DECIMAL string 透传（D-18 同源，禁数值化——非金额换算，直映语义）；dispenseStatus/
 * feeSettlementId 等发药回流镜像与结算锚字段可空直传 null（未发药/未结算），明细行由服务层
 * 按单取数后以清单参数供给（跨源取数装配留服务层不迁），ClinicOrderConverterTest 全字段等价
 * 单测守护。
 *
 * <p>componentModel 取默认（非 spring）：服务侧经 {@link #INSTANCE} 静态获取
 * （AppointmentConverter 同款，宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface ClinicOrderConverter {

    /** 默认组件模型的生成实现获取入口（单测与服务调用同源） */
    ClinicOrderConverter INSTANCE = Mappers.getMapper(ClinicOrderConverter.class);

    /**
     * 申请单明细行实体 → 计费行出参直映（3 字段；usageSummary 可空直传——无用法摘要时为 null）。
     *
     * @param item 明细行实体，非空
     * @return 计费行出参，非空
     */
    ClinicOrderVO.Item toItemVO(ClinicOrderItem item);

    /**
     * 申请单主单+明细行 → 出参直映（11 主单字段 + 明细行清单；extRef/validTo/dispenseStatus/
     * feeSettlementId 可空直传——P1 执行域未接入/未发药/未结算时为 null）。
     *
     * @param order 申请单实体，非空
     * @param items 明细行清单（服务层按单取数供给），可为空清单（RX_REF 引用行零明细）
     * @return 申请单出参，非空
     */
    @Mapping(target = "items", source = "items")
    ClinicOrderVO toClinicOrderVO(ClinicOrder order, List<ClinicOrderItem> items);
}
