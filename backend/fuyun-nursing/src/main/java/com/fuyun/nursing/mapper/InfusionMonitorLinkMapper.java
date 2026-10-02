package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.InfusionMonitorLink;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 输液监测挂接 mapper（V1106 infusion_monitor_link）：单表链式能力 + 摆药签收衔接幂等插入
 * 注解 SQL（GC26 形态，照 OrderExecutionMapper.insertIgnorePlanConflict 先例）。必须标注
 * @Mapper 供 app 侧扫描。
 */
@Mapper
public interface InfusionMonitorLinkMapper extends BaseMapper<InfusionMonitorLink> {

    /**
     * 幂等插入（摆药签收衔接 PIVAS 升格建链）：撞 uk_monitor_link_execution 部分唯一索引
     * 即整行放弃（DO NOTHING）——重复投递/乱序补发零副作用（0 行即已被唯一索引吞掉），
     * 与「先查后插」相比无查插间隙竞态。
     *
     * @param row 待插入挂接行（executionNo/bagLabelCode/startedAt/linkStatus/审计列已置值），非空
     * @return 影响行数：1=建链成功；0=该执行单在册挂接已存在（重复事件幂等达成）
     */
    @Insert("INSERT INTO nursing.infusion_monitor_link ("
            + "id, execution_no, bag_label_code, link_status, started_at, created_by, updated_by) VALUES ("
            + "#{id}, #{executionNo}, #{bagLabelCode}, #{linkStatus}, #{startedAt}, #{createdBy}, #{updatedBy}) "
            + "ON CONFLICT (execution_no) WHERE deleted = 0 DO NOTHING")
    int insertIgnoreExecutionConflict(InfusionMonitorLink row);
}
