package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.ThresholdOp;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 告警规则实体（iot.iot_alarm_rule，V1008 迁移）：三类规则源（阈值/设备告警透传/离线）的
 * 评估输入载体（FU-M14-08）。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)，宪法 A.4.3-16）；规则型参数列按类型条件必填
 * （THRESHOLD：compare_op/threshold_value/duration_secs/recovery_band；OFFLINE：offline_secs
 * ——应用层抖动防护②校验拒保存缺失行）；updated_at 由数据库触发器统一维护（V1 公共函数，
 * 宪法 A.4.2-9），应用层不写时间戳列。
 */
@Getter
@Setter
@TableName("iot.iot_alarm_rule")
public class IotAlarmRuleEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 规则名称（管理台展示名） */
    private String ruleName;

    /** 规则类型：DEVICE_ALARM/THRESHOLD/OFFLINE */
    private AlarmRuleType ruleType;

    /** 适用设备号（NULL=全部设备），可空 */
    private String deviceId;

    /** 指标编码（THRESHOLD/DEVICE_ALARM 必填；OFFLINE 规则为空），可空 */
    private String metricCode;

    /** 阈值比较方向：&gt;/&lt;（仅 THRESHOLD 规则有语义），可空 */
    private ThresholdOp compareOp;

    /** 阈值（THRESHOLD 必填），可空 */
    private BigDecimal thresholdValue;

    /** 持续时长秒（THRESHOLD 必填：越限持续该时长方触发），可空 */
    private Integer durationSecs;

    /** 恢复带（THRESHOLD 必填：恢复带内不重复触发），可空 */
    private BigDecimal recoveryBand;

    /** 静默窗口秒（默认 300；P0 静默语义由同源聚合 trigger_count 合并承载） */
    private Integer silenceWindowSecs;

    /** 离线判定秒（OFFLINE 必填），可空 */
    private Integer offlineSecs;

    /** 告警级别：INFO/WARNING/CRITICAL */
    private AlarmLevel alarmLevel;

    /** 升级时限秒（危急告警未确认超该值发布升级动作，抑制⑤；默认 300） */
    private Integer escalateAfterSecs;

    /** 是否启用（禁用规则不参与评估） */
    private Boolean enabled;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
