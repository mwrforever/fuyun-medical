package com.fuyun.ops.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运营工作台事件流出参（GET /api/v1/ops/workbench/events，批次 2 册 2 前端消费契约）：
 * 五源构成——三 STOMP 端点主题订阅指引（/ws/iot、/ws/nursing、/ws/outpatient 复用既有端点，
 * 本端点不自建 /ws/ops）+ 两 HTTP 轮询派生待办事件（billing 待支付/pharmacy 待配药）+ 危急值
 * 缺位降级段。全部真实聚合零伪数据。
 *
 * @param topics                STOMP 主题订阅指引清单（三端点各一条主力主题），非空
 * @param events                轮询派生待办事件（occurredAt 降序有界合并清单），非空（无待办为
 *                              空清单）
 * @param criticalValues        危急值段——固定空数组（M07 检验危急值模块未建，缺位降级明示；
 *                              NurseBoardVO.criticalValues 同款先例，M07 落地后回填，勿删组件），
 *                              非空
 * @param criticalValueDegraded 危急值降级判别标志：true=缺位降级中（M07 未落地，前端按降级文案
 *                              渲染）；false=真实数据。M07 回填前恒 true（与固定空数组联动），
 *                              非空
 * @param generatedAt           快照生成时点（北京钟面），非空
 */
public record WorkbenchEventsVO(
        List<Topic> topics,
        List<WorkEvent> events,
        List<Object> criticalValues,
        boolean criticalValueDegraded,
        OffsetDateTime generatedAt) {

    /**
     * STOMP 主题订阅指引行（既有端点主题复用清单——前端 useIotStomp 多端点订阅的登记面）。
     *
     * @param endpoint    WebSocket 端点（/ws/iot、/ws/nursing、/ws/outpatient 三既有端点），非空
     * @param topic       主题模板（占位段 {wardId}/{deptCode} 按订阅病区/诊区替换），非空
     * @param description 业务含义（前端事件流分类文案素材），非空
     */
    public record Topic(String endpoint, String topic, String description) {}

    /**
     * 待办事件行（HTTP 轮询派生源单行，纯计数/快照面零患者标识）。
     *
     * @param id        事件幂等判别键（费用编号/调剂单号，跨源唯一），非空
     * @param type      事件类型（FEE_PENDING 待支付/DISPENSE_PENDING 待配药/CRITICAL_VALUE
     *                  危急值占位——词表见 OpsConstants），非空
     * @param source    来源域（billing/pharmacy/lab），非空
     * @param title     业务标题（项目名称/处方号素材），非空
     * @param amountFen 金额（分，仅计费类事件携带；非计费类为 null），可空
     * @param occurredAt 事件时点（计费时刻/建单时刻），非空
     */
    public record WorkEvent(
            String id, String type, String source, String title, Long amountFen, OffsetDateTime occurredAt) {}
}
