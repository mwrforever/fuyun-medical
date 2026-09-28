package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.LinkageActionResult;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 联动执行日志实体（iot.iot_linkage_log，V1010 迁移）：逐次动作执行留痕与失败重推载体
 * （FU-M14-10）。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)，宪法 A.4.3-16）；联动行以 linkage_no 业务号对外
 * （IotSeqGate.nextLinkageNo，LG{yyyyMMdd}{%05d}）；结果机：SUCCESS/FAILED 终态 + PENDING 暂存
 * （目标域未上线，回接方收口），FAILED 人工重推经 casRetryResult 以旧状态限定兜底；updated_at
 * 由数据库触发器统一维护（V1 公共函数，宪法 A.4.2-9），应用层不写时间戳列。
 */
@Getter
@Setter
@TableName("iot.iot_linkage_log")
public class IotLinkageLogEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 联动执行业务号（LG{yyyyMMdd}{%05d}，IotSeqGate 签发，全局唯一） */
    private String linkageNo;

    /** 命中联动规则 ID（关联 linkage_rule，应用层保证存在） */
    private Long ruleId;

    /** 触发来源（命中时快照）：ALARM_TRIGGERED/TELEMETRY_ANOMALY/DEVICE_STATUS */
    private LinkageTriggerSource triggerSource;

    /** 触发来源引用（告警号 AL... 等业务号，回溯锚） */
    private String triggerRef;

    /** 动作类型（命中时快照）：NOTIFY/M01_NOTIFY/CALL_TRANSFER/NURSING_TASK/WARD_BROADCAST */
    private LinkageActionType actionType;

    /** 动作执行结果：SUCCESS 成功/FAILED 失败（重试耗尽终态）/PENDING 暂存（目标域未上线） */
    private LinkageActionResult actionResult;

    /** 累计重试次数（自动重试+人工重推累计；0=首试即成） */
    private Integer retryCount;

    /** 失败原因/暂存注记（SUCCESS 为空；PENDING 行承载 WardUnavailable 等域缺位注记） */
    private String errorMsg;

    /** 执行时刻（动作分派/终态判定时点；PENDING 行为暂存时点） */
    private OffsetDateTime executedAt;

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
