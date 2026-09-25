package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 随访计划实体（inpatient.follow_up_plan，V907）——出院医嘱三要素之一（注意事项/带药/随访，
 * 调研依据 11）：离院确认时按「出院后 N 日」请求参数生成 PENDING 行（visit DISCHARGED 与
 * 随访生成同事务——出院必随随访）。触达经 M01 通知中心（通知中心缺位期降级为工作站列表
 * 可见——五大降级清单②）。
 */
@Getter
@Setter
@TableName("inpatient.follow_up_plan")
public class FollowUpPlan {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院就诊主键（inpatient_visit.id；出院就诊轴） */
    private Long visitId;

    /** 患者主索引（触达对象定位） */
    private Long patientId;

    /** 随访日期（出院日后 N 日——离院确认时按请求参数生成） */
    private LocalDate planDate;

    /** 随访方式（三值词表：PHONE 电话 / WECHAT 公众号 / REVISIT 复诊） */
    private String way;

    /** 内容摘要（出院医嘱随访要素：复诊提示/用药指导/康复注意等） */
    private String summary;

    /** 状态 code（FollowUpStatus：PENDING/DONE/CANCELLED） */
    private String status;

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
