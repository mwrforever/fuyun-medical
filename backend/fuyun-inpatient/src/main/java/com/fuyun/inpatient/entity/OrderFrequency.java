package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用药频次专业字典实体（inpatient.order_frequency，V904）——长期医嘱频次校验
 * （freq_code 无命中拒 IP-1021）与执行计划拆分取数源（times_per_day/time_points/
 * prn_flag）：七行种子挂接 M01 medication.frequency 字典条目（dict_code 与 V607
 * dict_item.item_code 逐字同源）；freq_code 为本域自有编码可扩充（dict_code 保持 M01
 * 对照锚）。prn_flag=true 频次不经计划拆分（按需执行，嘱托触发面消费）。
 */
@Getter
@Setter
@TableName("inpatient.order_frequency")
public class OrderFrequency {

    /** 雪花主键（MP ASSIGN_ID；种子行取小整数 1–7） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 频次编码（行业惯例缩写：qd/bid/tid/qid/qn/prn/st；uk 唯一；医嘱 freq_code 校验键） */
    private String freqCode;

    /** 频次名称（与 V607 medication.frequency 条目 item_name 同源） */
    private String freqName;

    /** 每日次数（qd=1/bid=2/tid=3/qid=4/qn=1；prn/st 无固定次数=0） */
    private Integer timesPerDay;

    /** 执行时点序列（HH:mm 逗号分隔如 08:00,16:00；prn/st 无固定时点为 null） */
    private String timePoints;

    /** 周模式（如 MWF；本批种子均为 null，隔日/周模式频次扩充时启用） */
    private String weekPattern;

    /** 必要时（pro re nata）标记：true=按需执行不经计划拆分、由嘱托触发面消费 */
    private Boolean prnFlag;

    /** 挂接 M01 medication.frequency 字典条目 item_code（V607 条目对照锚） */
    private String dictCode;

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
