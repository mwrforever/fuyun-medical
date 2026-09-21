package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.ClinicOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 申请单主单 mapper：单表操作（开单落库/单号定位/在途单据计数）经 BaseMapper 链式能力，另声明状态
 * CAS 注解 SQL——申请单状态机迁移的数据面（Task 8 消费：缴费回执 CREATED→PENDING_FEE、作废
 * CREATED/PENDING_FEE→CANCELLED；CHARGED 扇出随 Task 10 消费）。必须标注 {@code @Mapper}：
 * app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface ClinicOrderMapper extends BaseMapper<ClinicOrder> {

    /**
     * 申请单状态 CAS：影响行数 0=行不存在/并发已迁移/状态违例（调用方重读定性后幂等跳过或拒绝）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.OrderStatus} code 同源（入参传
     * {@code OrderStatus.XXX.getCode()}）；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；
     * updated_by 固定 'system'（缴费回执为系统驱动迁移，操作者语义不适用于本表）。
     *
     * @param id         申请单主键（clinic_order 表 PK）；来源：按 order_no 定位后取得
     * @param fromStatus 期望迁出态 code（如 CREATED）
     * @param toStatus   目标态 code（如 PENDING_FEE/CANCELLED）
     * @return 影响行数：1=迁移成功；0=行不存在或状态违例/并发落败
     */
    @Update("UPDATE outpatient.clinic_order SET status = #{toStatus}, updated_by = 'system', updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casStatus(@Param("id") long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);
}
