package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.enums.MetricDataType;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * MDC 指标术语字典实体（iot.iot_metric_dict，V1007 迁移）：模块自管专业字典（不经 M01），
 * 跨品牌遥测术语归一基准（14-iot.md §105）。
 *
 * <p>主键偏离声明（V1007 文件头）：主键 = metric_code（MDC 编码自然键），{@code @TableId(type =
 * INPUT)}。硬删除口径：本表无审计列与 deleted 列（V1007 唯一例外表），删除为物理 DELETE，
 * 删除前由应用层校验映射表无引用。
 */
@Getter
@Setter
@TableName("iot.iot_metric_dict")
public class IotMetricDictEntity {

    /** 主键：MDC 编码自然键（如 MDC_ECG_HEART_RATE，@TableId(INPUT)） */
    @TableId(value = "metric_code", type = IdType.INPUT)
    private String metricCode;

    /** 指标名称（中文，管理台展示） */
    private String metricName;

    /** 指标类别：VITAL_SIGN/WAVEFORM/ALARM/DEVICE_STATUS */
    private MetricCategory category;

    /** 数据类型：NUMERIC/TEXT/JSON */
    private MetricDataType dataType;

    /** 计量单位（次每分/%/mmHg 等），可空 */
    private String unit;

    /** 生理极限下界（超限即数据质量异常线索），可空 */
    private BigDecimal physioMin;

    /** 生理极限上界（同上），可空 */
    private BigDecimal physioMax;

    /** 默认告警级别：INFO/WARNING/CRITICAL（与 iot_alarm_rule.level 同词表），可空 */
    private String defaultLevel;

    /**
     * 标称采集频率（次/分钟，V1012 增列，FU-M14-11）：断流判定（在线但超标称周期 N 倍时长无
     * 数据）与缺数统计（expected_count = 标称频率 × 在线分钟数）的推算基准；NULL = 未登记，
     * 不参与缺数推算（缺数率按 0 记防误报）。
     */
    private BigDecimal nominalFreqPerMin;
}
