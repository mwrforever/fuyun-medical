package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备档案 mapper：状态机 apply 的条件更新与档案存在性查询（自然键 device_id）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface IotDeviceMapper extends BaseMapper<IotDeviceEntity> {

    /**
     * 离线断流候选单条动态扫描（W-60 收敛，OfflineDetector 消费）：启用 OFFLINE 规则集一次
     * foreach 组装 UNION ALL 分支——每分支携带 rule_id 标记列并按该规则独立超时窗口
     * （now() - make_interval(offline_secs)，NULL 归零与探测器原逐规则口径一致）与候选上限
     * （ORDER BY last_online_at 升序 LIMIT scanLimit，断流最久优先），一次 SQL 触达替代
     * 逐规则循环单查（宪法 A.4.3-14）。分支间输出序无契约保证，调用侧按 rule_id 分组后
     * 显式排序承载升序语义。
     *
     * <p>判定基准时刻=SQL 语句时钟（PG now() 同语句内恒定，分支间窗口一致；应用/DB 时钟
     * NTP 同步，秒级偏差相对分钟级离线阈值无语义影响——原逐规则实现的应用侧时钟口径收敛
     * 申报见 OfflineDetector）。注解 SQL 不继承 @TableLogic，deleted=0 显式补齐。
     *
     * @param rules     启用 OFFLINE 规则清单，非空集合（空集由调用侧短路，禁空 foreach 生成非法 SQL）
     * @param scanLimit 单规则候选上限（LIMIT 硬顶，与调用侧截断判定同源），正数
     * @return 各规则分支候选行（rule_id 标记 + 设备档案全列；同设备命中多规则时按分支重复出现），非空
     */
    @Select("<script><foreach collection='rules' item='r' separator=' UNION ALL '>"
            + "(SELECT CAST(#{r.id} AS BIGINT) AS rule_id, d.* FROM iot.iot_device d"
            + " WHERE d.status = 'ONLINE' AND d.deleted = 0"
            + " AND d.last_online_at &lt; now() - make_interval(secs =&gt; COALESCE(#{r.offlineSecs}, 0))"
            + "<if test='r.deviceId != null'> AND d.device_id = #{r.deviceId}</if>"
            + " ORDER BY d.last_online_at ASC, d.device_id ASC LIMIT #{scanLimit})"
            + "</foreach></script>")
    List<OfflineCandidateRow> selectOfflineCandidates(
            @Param("rules") List<IotAlarmRuleEntity> rules, @Param("scanLimit") int scanLimit);

    /**
     * 离线候选投影行（单条 UNION ALL 扫描载体）：继承设备档案实体承载 d.* 全列（病区路由等
     * 消费字段全保真），外加 rule_id 分支标记列供调用侧按规则分组配对（MyBatis 驼峰映射
     * rule_id → ruleId）。
     */
    @Getter
    @Setter
    class OfflineCandidateRow extends IotDeviceEntity {

        /** 命中分支的规则 ID（UNION 分支标记，Java 侧按规则分组配对），非空 */
        private Long ruleId;
    }
}
