package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotConsumerStatEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消费积压监控快照 mapper（iot.iot_consumer_stat，FU-M14-01 积压观测面，P2 PR-2 Task 10）。
 *
 * <p>最新快照查询走 mapper + XML（宪法 A.4.3-15）：每消费组取采样时刻最新一行
 * （DISTINCT ON 惯用法），链式 wrapper 无法表达。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotConsumerStatMapper extends BaseMapper<IotConsumerStatEntity> {

    /**
     * 查询每消费组最新一行快照（monitor/consumer-lag 端点数据源：>5 分钟告警判据读取面）。
     *
     * @param groups 消费组标识集合，可空（null/空 = 不过滤返回全部组）
     * @return 每组最新快照清单（sampled_at 降序），非空；无采样行为空清单
     */
    List<IotConsumerStatEntity> selectLatestPerGroup(@Param("groups") List<String> groups);
}
