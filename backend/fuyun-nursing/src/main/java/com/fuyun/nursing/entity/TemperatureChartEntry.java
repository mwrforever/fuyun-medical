package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 体温单条目实体（nursing.temperature_chart_entry，V802）：体温单数据点权威——体征符号由前端
 * 按体温部位渲染（腋温×/口温●/肛温〇；物理降温红圈红虚线、脉搏短绌短红线）。唯一约束第四维
 * type_key 由服务写入（VITAL=体温部位 / SPECIAL_EVENT=事件类型 / DAILY_VALUE=日行值类型），
 * 使 (page_id, entry_time, entry_type, type_key) 唯一且同刻不同体温部位可并存（Spec :108/:110）。
 */
@Getter
@Setter
@TableName("nursing.temperature_chart_entry")
public class TemperatureChartEntry {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 月页 ID（temperature_chart_page.id） */
    private Long pageId;

    /** 条目时点（服务器时间红线覆盖写入面：vital=体征测量时点、其余=写入时点服务器时间） */
    private OffsetDateTime entryTime;

    /** 条目类型（ChartEntryType code：VITAL / SPECIAL_EVENT / DAILY_VALUE） */
    private String entryType;

    /** 体征记录引用（entry_type=VITAL 时指向 vital_sign_record.id） */
    private Long vitalRef;

    /** 特殊事件类型（entry_type=SPECIAL_EVENT 时为 SpecialEventType code） */
    private String specialEventType;

    /** 日行值类型（entry_type=DAILY_VALUE：STOOL_COUNT/BODY_WEIGHT/HEIGHT/IO_SUMMARY_SHIFT/IO_SUMMARY_24H/SKIN_TEST） */
    private String dailyValueType;

    /** 类型键（唯一约束第四维；服务写入：VITAL=体温部位（空部位落空串）/SPECIAL_EVENT=事件类型/DAILY_VALUE=日行值类型） */
    private String typeKey;

    /** 日行值文本（如「入 2500 / 出 2100」） */
    private String valueText;

    /** 记录人（业务标识；自动链路写入可为空） */
    private String recorderId;

    /** 记录人姓名（展示用） */
    private String recorderName;

    /** 备注（物理降温前体温等） */
    private String remark;

    /** 创建时刻 */
    private OffsetDateTime createdAt;

    /** 更新时刻 */
    private OffsetDateTime updatedAt;

    /** 创建者 */
    private String createdBy;

    /** 更新者 */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
