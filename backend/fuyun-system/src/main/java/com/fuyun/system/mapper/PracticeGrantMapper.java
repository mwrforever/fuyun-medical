package com.fuyun.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.system.entity.PracticeGrant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 执业授权 mapper：单表操作经 BaseMapper 链式能力，另声明生效授权查询与停权 CAS 两支注解 SQL
 * （条件更新/判定型语句经 @Update/@Select + 影响行数判定，FeeRecordMapper.casMarkFeesSettled 实证
 * 形态）。必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface PracticeGrantMapper extends BaseMapper<PracticeGrant> {

    /**
     * 停权 CAS（仅 EFFECTIVE 可停）：影响行数 0=并发已停或不存在（调用方重读定性）。
     *
     * <p>status 字面量与 {@link PracticeGrantStatus} code 同源；deleted=0 显式补齐（注解 SQL
     * 不继承 @TableLogic）；updated_by 应用层注入操作人，updated_at 交库端触发器刷新。
     *
     * @param id       授权行主键；来源：withdraw 端点路径参数
     * @param operator 操作者标识（OperatorContextHolder）；来源：认证拦截器注入
     * @return 影响行数：1=停权成功；0=行不存在或已非 EFFECTIVE（幂等达成/并发落败）
     */
    @Update("UPDATE system.practice_grant SET status = 'SUSPENDED', updated_by = #{operator}, updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'EFFECTIVE'")
    int casWithdraw(@Param("id") long id, @Param("operator") String operator);

    /**
     * 生效授权查询（check 主查询）：有效期含当日，valid_to NULL=长期。
     *
     * <p>status 字面量与 {@link PracticeGrantStatus} code 同源；deleted=0 显式补齐（注解 SQL
     * 不继承 @TableLogic）；LIMIT 1 收口（部分唯一索引保证至多一条生效行，谓词冗余防御）。
     *
     * @param employeeId 员工 ID；来源：check 请求入参（Task 8/9 自操作者上下文解析）
     * @param grantType  授权类型词表值；来源：check 请求入参
     * @param checkDate  校验日期（checkTime 缺省取服务端当日）；来源：服务端归一
     * @return 命中的生效授权行；无命中返回 null（调用方回查过期行区分 reason 文案）
     */
    @Select("SELECT * FROM system.practice_grant WHERE employee_id = #{employeeId} AND grant_type = #{grantType} "
            + "AND deleted = 0 AND status = 'EFFECTIVE' AND valid_from <= #{checkDate} "
            + "AND (valid_to IS NULL OR valid_to >= #{checkDate}) LIMIT 1")
    PracticeGrant selectEffective(
            @Param("employeeId") long employeeId,
            @Param("grantType") String grantType,
            @Param("checkDate") LocalDate checkDate);
}
