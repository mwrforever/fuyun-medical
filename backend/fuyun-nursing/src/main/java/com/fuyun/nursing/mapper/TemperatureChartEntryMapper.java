package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.TemperatureChartEntry;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 体温单条目 mapper：单表链式能力 + 条件更新注解 SQL（GC26：@Update + 影响行数判定 +
 * 显式 deleted=0）。条目写入主链为 insert（唯一约束冲突由服务层转 NS-1016 幂等拒绝）；
 * 条件更新仅承载引用回填类兜底面，禁任何正文覆盖型更新（首值权威）。
 */
@Mapper
public interface TemperatureChartEntryMapper extends BaseMapper<TemperatureChartEntry> {

    /**
     * VITAL 条目引用回填（casFillVitalEntry，Task 5 体征域重放兜底消费面）：仅当同键 VITAL
     * 条目的 vital_ref 为空时回填体征行引用——先建条目后补引用的两段式写入场景；已有引用
     * 一律不动（首值权威，不覆盖）。零命中（0 行）属幂等常态，调用方自行容忍。
     *
     * @param pageId    月页 id，非空
     * @param entryTime 条目时点，非空
     * @param typeKey   类型键（体温部位，空部位传空串），非空
     * @param vitalRef  待回填的体征记录引用，非空
     * @return 影响行数（0=同键条目不存在或引用已非空，幂等容忍）
     */
    @Update("UPDATE nursing.temperature_chart_entry SET vital_ref = #{vitalRef} "
            + "WHERE page_id = #{pageId} AND entry_time = #{entryTime} AND entry_type = 'VITAL' "
            + "AND type_key = #{typeKey} AND vital_ref IS NULL AND deleted = 0")
    int casFillVitalEntry(
            @Param("pageId") long pageId,
            @Param("entryTime") OffsetDateTime entryTime,
            @Param("typeKey") String typeKey,
            @Param("vitalRef") long vitalRef);
}
