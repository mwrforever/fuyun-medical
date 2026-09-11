package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备患者绑定实体（iot.iot_binding，V400 迁移）：绑定快照五元组与生命周期状态机载体。
 *
 * <p>历史只增（M14 Spec §5）：UNBOUND 行不物理删（数据归属回溯依据），解绑走状态迁移 UPDATE；
 * 新绑定必须由 BOUND 之外状态新建记录，禁止复用历史记录（V400 部分唯一索引
 * uk_iot_binding_device_bound 兜底"同一设备同一时刻至多一条绑定中"）。
 * 状态列使用 {@link BindType}/{@link BindingStatus} 枚举（MP @EnumValue 自动映射 VARCHAR 列）；
 * updated_at 由数据库触发器统一维护（V1 公共函数），应用层不写时间戳列。
 */
@Getter
@Setter
@TableName("iot.iot_binding")
public class IotBindingEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** IoTDA 设备标识（关联 iot_device 自然键） */
    private String deviceId;

    /** 患者 ID（M02，绑定快照五元组） */
    private Long patientId;

    /** 就诊 ID（M04，绑定快照五元组） */
    private Long visitId;

    /** 床位 ID（固定式绑定落，移动式可空），可空 */
    private Long bedId;

    /** 病区 ID（绑定快照五元组） */
    private Long wardId;

    /** 绑定模式：FIXED 固定式/MOBILE 移动式 */
    private BindType bindType;

    /** 绑定状态机：BOUND 绑定中/UNBINDING 解绑中/UNBOUND 已解绑（14-iot §5） */
    private BindingStatus status;

    /** 绑定原因，可空 */
    private String bindReason;

    /** 解绑原因：转床/消毒/维修/出院/调拨，未解绑为空 */
    private String unbindReason;

    /** 绑定操作人（种子/系统动作为 system，数据库默认值） */
    private String boundBy;

    /** 绑定生效时刻（数据库 DEFAULT now() 承担默认） */
    private OffsetDateTime boundAt;

    /** 解绑完成时刻（未解绑为空），可空 */
    private OffsetDateTime unboundAt;

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
