package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.IoRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 出入量明细账 mapper：单表链式能力 + 班期聚合注解 SQL（GC26：@Select 显式 deleted=0）。
 * 行写入主链为 insert（明细账无唯一约束，无冲突翻译面）；班次小结/24h 总结的求和经
 * sumByTypeAndPeriod 按类型分组聚合，时间窗含头不含尾（TIMESTAMPTZ 按时刻比较）。
 */
@Mapper
public interface IoRecordMapper extends BaseMapper<IoRecord> {

    /**
     * 统计周期内按出入量类型求和（班次小结/24h 总结聚合面，简报冻结 SQL 形态）：窗口
     * [from, to) 含头不含尾，GROUP BY io_type 每型至多一行——结果行仅 ioType/quantity
     * （quantity 承载该型合计值）两字段有效，其余列不映射。PG 侧未加引别名列按小写返回，
     * 依赖 map-underscore-to-camel-case 将 io_type 映射 ioType（禁别名 ioType——PG 折叠
     * 小写后无法匹配驼峰属性）。
     *
     * @param visitId 住院就诊号，非空
     * @param from    统计周期起（含），非空
     * @param to      统计周期止（不含），非空
     * @return 分型合计行清单（空周期返回空清单，非 null）；quantity=该型 SUM(quantity)
     */
    @Select("SELECT io_type, SUM(quantity) AS quantity FROM nursing.io_record "
            + "WHERE visit_id = #{visitId} AND occur_at >= #{from} AND occur_at < #{to} AND deleted = 0 "
            + "GROUP BY io_type")
    List<IoRecord> sumByTypeAndPeriod(
            @Param("visitId") String visitId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
