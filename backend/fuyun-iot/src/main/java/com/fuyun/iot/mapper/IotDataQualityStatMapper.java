package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotDataQualityStatEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 遥测数据质量日统计 mapper（iot.iot_data_quality_stat，FU-M14-11 统计落库通道）。
 *
 * <p>UPSERT 走 mapper + XML（宪法 A.4.3-15）：当日统计惰性重算按
 * uk_iot_data_quality_stat_device_date 冲突覆盖（ON CONFLICT DO UPDATE），链式 wrapper 无法表达。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotDataQualityStatMapper extends BaseMapper<IotDataQualityStatEntity> {

    /**
     * 统计行 UPSERT：同设备同统计日存在则覆盖计算值，否则落新行（updated_at 由库端触发器维护，
     * 应用层不写该列；updated_by 由调用方传操作人，惰性触发恒 'system'）。
     *
     * @param stat 统计实体，非空（device_id/statDate/四统计值必填，审计列由库端默认承担）
     * @return 影响行数（UPSERT 恒 1）
     */
    int upsertStat(IotDataQualityStatEntity stat);
}
