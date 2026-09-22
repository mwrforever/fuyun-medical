package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 病区护理配置实体（nursing.nursing_ward_config，V801）：病区级护理策略唯一配置点，一病区一行。
 * P1 消费面：体征测量频次参数组（vital_freq_config，Task 5 体征频次提醒取值）/ IoT 自动落卡开关
 * （P2 生效注记）/ 班次定义（shift_definitions，交接班 Task 9 与详情卡当班判定消费）。
 * JSONB 列以 String 承载（pgjdbc getString 直读，结构解析归服务层 Jackson）。
 */
@Getter
@Setter
@TableName("nursing.nursing_ward_config")
public class NursingWardConfig {

    /** 雪花主键（MP ASSIGN_ID；种子行固定 id=1） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 病区编码（M01 组织机构病区 code，uk_ward_config_ward 唯一） */
    private String wardId;

    /** 体征测量频次参数组（JSONB 文本：{"SPECIAL":60,"CRITICAL":240,"NORMAL":480}，分钟/次） */
    private String vitalFreqConfig;

    /** IoT 自动落卡开关（P2 生效；ICU 病区经此关闭防双写） */
    private Boolean iotAutocastEnabled;

    /** 班次定义（JSONB 文本：[{"code","name","start","end"}]） */
    private String shiftDefinitions;

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
