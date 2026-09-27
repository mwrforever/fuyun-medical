package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotLinkageLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 联动执行日志 mapper：人工重推结果的 CAS 唯一入口（GC23 形态：状态迁移一律 {@code @Update} +
 * 影响行数判定，显式补 deleted=0——并发双推以旧状态限定兜底）。必须标注 {@code @Mapper}：app 侧
 * MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotLinkageLogMapper extends BaseMapper<IotLinkageLogEntity> {

    /**
     * 人工重推结果 CAS：仅 FAILED 行可迁移（并发双推仅首个重推方生效，败方零行拒绝）。
     *
     * @param linkageNo 联动执行业务号，非空；来源：重推端点路径变量
     * @param result    重执行后的动作结果词表值（SUCCESS/FAILED/PENDING），非空；来源：执行器回执
     * @param errorMsg  失败原因/暂存注记（成功为 null），可空；来源：执行器回执
     * @param operator  操作者（操作者上下文，无登录上下文回退 system），非空
     * @return 影响行数（1=本次重推方登记成功；0=并发已被其他重推承接/行不存在，调用方按 409 拒绝）
     */
    @Update("UPDATE iot.iot_linkage_log SET action_result = #{result}, retry_count = retry_count + 1, "
            + "error_msg = #{errorMsg}, executed_at = now(), updated_by = #{operator} "
            + "WHERE linkage_no = #{linkageNo} AND action_result = 'FAILED' AND deleted = 0")
    int casRetryResult(
            @Param("linkageNo") String linkageNo,
            @Param("result") String result,
            @Param("errorMsg") String errorMsg,
            @Param("operator") String operator);
}
