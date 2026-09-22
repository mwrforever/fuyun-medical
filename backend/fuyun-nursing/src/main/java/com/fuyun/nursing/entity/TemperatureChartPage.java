package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 体温单月页实体（nursing.temperature_chart_page，V802）：住院月页为体温单条目的页面维度
 * （住院天数/术后天数等要素的载体，手术后天数序列依据 M10 手术事件标注随 P2 注记）。
 * (visit_id, chart_month) 唯一；月页由 ensurePage 自动创建/取回，无需人工开页。
 */
@Getter
@Setter
@TableName("nursing.temperature_chart_page")
public class TemperatureChartPage {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院就诊号（I 型 14 位） */
    private String visitId;

    /** 住院月页（yyyy-MM） */
    private String chartMonth;

    /** 页状态（ChartPageStatus code：ACTIVE 进行中 / ARCHIVED 已归档；归档随 P2/P4 注记） */
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
