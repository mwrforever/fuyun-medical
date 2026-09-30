package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotGatewayEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 边缘网关档案 mapper（iot.iot_gateway，FU-M14-12，P2 PR-2 Task 11）：网关 CRUD 主链走链式
 * wrapper（宪法 A.4.3-15 复杂 SQL 才落 mapper XML）；删除守卫 CAS 以注解 SQL 承载
 * （IotAlarmMapper 同款形态——守卫反查折入置删语句，见 {@link #casSoftDeleteIfNoInboundStandby}）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotGatewayMapper extends BaseMapper<IotGatewayEntity> {

    /**
     * CAS 守卫软删（EX-22/BE-A2-03，删除守卫读后写竞态收口）：「他网关 standby_of 反查」折入
     * 置删同一 UPDATE 原子边界——反查谓词与置删同语句生效，原「count 预检读 → removeById 写」
     * 方法级窗口内并发挂入的热备引用不再放行（防悬挂 standby_of 引用，数据卫生面）。反查条件与
     * 原链式预检逐字同形（standby_of 命中本网关、排除自身行、@TableLogic deleted=0 口径显式内嵌，
     * 部分索引 idx_iot_gateway_standby 准入）；SET 面与 removeById 生成面同形（仅置 deleted=1，
     * updated_at 由数据库触发器维护，应用层不触碰审计列）。
     *
     * @param gatewayId 待删网关标识，非空；来源：删除端点路径变量
     * @return 影响行数（1=守卫通过且已软删；0=守卫命中或行已不在册——调用方按行可见性二分归因：
     *         行仍在册即 IOT-1025 拒删，行已消失即并发删除幂等成功）
     */
    @Update("UPDATE iot.iot_gateway SET deleted = 1 "
            + "WHERE gateway_id = #{gatewayId} AND deleted = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM iot.iot_gateway peer "
            + "WHERE peer.standby_of = #{gatewayId} AND peer.gateway_id <> #{gatewayId} AND peer.deleted = 0)")
    int casSoftDeleteIfNoInboundStandby(@Param("gatewayId") String gatewayId);
}
