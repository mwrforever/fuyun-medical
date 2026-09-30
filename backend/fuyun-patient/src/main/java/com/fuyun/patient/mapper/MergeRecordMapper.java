package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.MergeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 合并记录 mapper：单表操作经 BaseMapper/IService 链式能力（无 XML，宪法 A.4.3-15），另声明
 * 审批抢锚 CAS 条件更新（EX-21 读后判收口，fuyun-billing RefundRequestMapper.casFinalApprove
 * 同族先例）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface MergeRecordMapper extends BaseMapper<MergeRecord> {

    /**
     * 审批抢锚 CAS 条件更新（EX-21）：仅 PROCESSING/FAILED 可被审批——并发双批准同记录恰一赢
     * （本语句行锁上等待，先到者提交后谓词对新行版本重评估不命中），输家 0 行由服务层重读定性
     * 拒 PAT-1008，后提交者不得重复执行合并序列（快照覆盖/标识重挂/从档置 MERGED 的重复写面
     * 根除）。审批人落库与 FAILED 重试置回 PROCESSING 随状态锚同语句原子完成；原生 SQL 状态
     * 字面量与 {@code MergeStatus} code 同源（PROCESSING/FAILED 恒等常量名）；deleted=0 显式
     * 补齐（@TableLogic 仅自动作用于 wrapper，注解 SQL 不继承）。
     *
     * @param id       合并记录 id，非空；来源：approve 入口 requireRecord 读回行主键
     * @param operator 审批操作人，非空；来源：approve 入参（双人角色已由读快照守卫校验）
     * @return 影响行数（1=抢得审批执行权；0=已被并发处理或状态违例，调用方重读定性报错）
     */
    @Update("UPDATE patient.merge_record SET status = 'PROCESSING', approved_by = #{operator} "
            + "WHERE id = #{id} AND status IN ('PROCESSING', 'FAILED') AND deleted = 0")
    int casApproveProcessing(@Param("id") long id, @Param("operator") String operator);
}
