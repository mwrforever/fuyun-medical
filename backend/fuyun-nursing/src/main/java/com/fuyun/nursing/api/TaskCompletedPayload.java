package com.fuyun.nursing.api;

/**
 * 护理任务完成/取消事件载荷（nursing.task.completed，V800 id 59 冻结契约，Task 7 发布）：
 * 护理任务到达终态时发布，消费方回写关联单据。
 *
 * @param taskNo 护理任务业务号，非空；来源：护理任务发号器
 * @param status 任务终态（COMPLETED 完成 或 CANCELLED 取消），非空
 */
public record TaskCompletedPayload(String taskNo, String status) {}
