package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.Visit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 就诊记录 mapper：单表操作（挂号落库/就诊号定位/在途查询）经 BaseMapper 链式能力，另声明状态 CAS
 * 注解 SQL——visit 主状态机迁移的数据面（Task 7/8 消费：报到 WAITING、接诊 IN_CONSULT、诊毕
 * FINISHED 等）；红线 5：迁移前置校验经状态机单点（Task 8 落位），CAS 命中后每迁必记
 * visit_status_log（from/to/reason/operator）。必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig
 * 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface VisitMapper extends BaseMapper<Visit> {

    /**
     * 就诊状态 CAS：影响行数 0=行不存在/并发已迁移/状态违例（调用方重读定性后幂等跳过或拒绝）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.VisitStatus} code 同源（入参传
     * {@code VisitStatus.XXX.getCode()}，且 from→to 须为状态机合法迁移对）；deleted=0 显式补齐
     * （注解 SQL 不继承 @TableLogic）；updated_by 固定 'system'（操作者留痕由 visit_status_log
     * operator 列承载）。
     *
     * @param id         就诊记录主键（visit 表 PK）；来源：Task 7/8 按 visit_id 定位后取得
     * @param fromStatus 期望迁出态 code（如 WAITING）
     * @param toStatus   目标态 code（如 IN_CONSULT）
     * @return 影响行数：1=迁移成功（调用方随即写 visit_status_log）；0=行不存在或状态违例/并发落败
     */
    @Update("UPDATE outpatient.visit SET status = #{toStatus}, updated_by = 'system', updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casStatus(@Param("id") long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);
}
