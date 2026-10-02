package com.fuyun.nursing.api;

import java.time.OffsetDateTime;

/**
 * 联动任务创建请求（M14 联动动作 → M05 护理任务创建端口入参，P2 PR-3 Task 12）：iot
 * LinkageExecutor NURSING_TASK 动作的进程内直调载荷，字段取告警行绑定快照与规则动作配置。
 *
 * <p><b>病区标识空间申报</b>：wardId 为 iot 域病区 id 数字串（sys_org 雪花 id 文本形态）——
 * 与护理域病区编码（org_code 文本，如 W01）分属两个标识空间且 nursing 侧无 id→code 映射
 * api 面（Task 11 IotCallTriggeredListener 同款实测结论）。本端口按数字串原样落
 * nursing_task.ward_id（VARCHAR(64) 同宽承载，任务清单按护理病区编码检索时该类行不命中——
 * 联动任务消费面为任务工作台全量与按 visit 检索，病区列收口随 M16/M01 映射面裁决）。
 *
 * <p><b>title 无落点列申报</b>：nursing_task 无标题列（V805 冻结面），本字段仅承载契约
 * （联动动作展示语义，iot 侧取规则 actionConfig.title），不落库——如需落列须契约变更走
 * 迁移，禁新迁移红线内不自行扩列（V400 unbind_reason 留痕先例）。
 *
 * @param linkageNo 联动执行业务号（LG 段），非空；幂等锚（source_ref 落库值，同号重放回查原任务）
 * @param wardId    病区 id 数字串（iot 域标识空间，见类注），非空
 * @param patientId 患者主索引，非空；来源：告警行绑定快照
 * @param visitId   住院就诊号（I 型 14 位），非空；来源：告警行绑定快照
 * @param taskType  任务类型 code（TaskType 词表；iot 侧缺省 IOT_LINKAGE），非空
 * @param title     任务展示标题（规则动作配置透传），可空；无落库列，仅契约承载
 * @param planTime  计划时间（逾期判定基准；iot 侧即联即办语义=执行时刻），非空
 */
public record NursingTaskLinkageRequest(
        String linkageNo,
        String wardId,
        Long patientId,
        String visitId,
        String taskType,
        String title,
        OffsetDateTime planTime) {}
