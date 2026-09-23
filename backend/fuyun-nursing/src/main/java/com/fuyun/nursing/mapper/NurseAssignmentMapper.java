package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.NurseAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 责任护士分配 mapper：单表链式能力 + 撤销 CAS 条件更新（注解 SQL 显式补 deleted=0；
 * 状态字面量与 V801 列值域逐字同源；0 行=分配不存在或已撤销，调用方定性拒绝）。
 * 唯一键冲突前置查重经服务层 selectCount 链式条件承载（床位/患者两条部分唯一索引对齐）。
 */
@Mapper
public interface NurseAssignmentMapper extends BaseMapper<NurseAssignment> {

    /**
     * 撤销分配 CAS（ACTIVE→CANCELLED）：逻辑撤销留痕（不删行，交接班追溯依据）。
     *
     * @param id        分配 id
     * @param updatedBy 撤销操作者（审计留痕），非空
     * @return 影响行数（0=分配不存在或已撤销）
     */
    @Update("UPDATE nursing.nurse_assignment SET status = 'CANCELLED', updated_by = #{updatedBy} "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND deleted = 0")
    int casCancel(@Param("id") long id, @Param("updatedBy") String updatedBy);
}
