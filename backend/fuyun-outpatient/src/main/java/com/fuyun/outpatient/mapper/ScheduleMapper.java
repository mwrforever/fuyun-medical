package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 排班日历 mapper：单表操作（放号插入/分页清单）经 BaseMapper 链式能力，另声明停诊/恢复共用的
 * 状态 CAS 注解 SQL（条件更新一律 @Update + 影响行数判定，PracticeGrantMapper.casWithdraw 实证
 * 形态）。必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    /**
     * 排班状态 CAS（停诊 NORMAL→STOPPED / 恢复 STOPPED→NORMAL 共用）：影响行数 0=并发已迁移
     * 或状态违例（调用方判 OP-1004）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.ScheduleStatus} code 同源（入参传
     * {@code ScheduleStatus.XXX.getCode()}）；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；
     * updated_by 注入操作人，updated_at 交库端触发器同刻刷新。
     *
     * @param id         排班行主键；来源：stop/resume 端点路径参数
     * @param fromStatus 期望迁出态 code（停诊传 NORMAL、恢复传 STOPPED）
     * @param toStatus   目标态 code（停诊传 STOPPED、恢复传 NORMAL）
     * @param operator   操作者标识（OperatorContextHolder）；来源：认证拦截器注入
     * @return 影响行数：1=迁移成功；0=行不存在或状态违例/并发落败（调用方判 OP-1004）
     */
    @Update("UPDATE outpatient.schedule SET status = #{toStatus}, updated_by = #{operator}, updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casStatus(
            @Param("id") long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operator") String operator);
}
