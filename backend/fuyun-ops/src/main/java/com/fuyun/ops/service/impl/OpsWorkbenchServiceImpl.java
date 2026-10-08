package com.fuyun.ops.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingStatsPort;
import com.fuyun.billing.api.BillingWorkloadStats;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.nursing.api.NursingStatsPort;
import com.fuyun.nursing.api.NursingWorkloadStats;
import com.fuyun.ops.constants.OpsConstants;
import com.fuyun.ops.service.IOpsWorkbenchService;
import com.fuyun.ops.vo.WorkbenchEventsVO;
import com.fuyun.ops.vo.WorkbenchEventsVO.Topic;
import com.fuyun.ops.vo.WorkbenchEventsVO.WorkEvent;
import com.fuyun.ops.vo.WorkbenchOverviewVO;
import com.fuyun.ops.vo.WorkbenchOverviewVO.Metrics;
import com.fuyun.ops.vo.WorkbenchOverviewVO.TrendPoint;
import com.fuyun.ops.vo.WorkbenchOverviewVO.WaitingRow;
import com.fuyun.outpatient.api.OutpatientStatsPort;
import com.fuyun.outpatient.api.OutpatientWorkloadStats;
import com.fuyun.outpatient.api.TrendWindow;
import com.fuyun.pharmacy.api.PharmacyStatsPort;
import com.fuyun.pharmacy.api.PharmacyWorkloadStats;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 运营工作台聚合服务实现（批次 2 册 2 首切片）：四业务模块统计 Port 只读聚合 + Redis 快照
 * TTL 5s read-through 缓存（{@code fy:ops:snapshot:workbench:overview}，NurseBoardServiceImpl
 * 同款形态——读/写/解析三分支失败均 warn 降级直算不阻断）+ 事件流五源装配。
 *
 * <p><b>模块边界（宪法 B.2-2）</b>：全部取数经四模块 api 包 Port 接口注入（禁直查他模块表、
 * 禁依赖他人 impl）；本服务只做编排与映射，零自身数据访问（fuyun-ops 首切片零表零 mapper）。
 *
 * <p><b>事件流五源</b>：①②③三 STOMP 端点主题订阅指引（/ws/iot 设备状态、/ws/nursing 病区
 * 动态、/ws/outpatient 候诊叫号——既有端点复用，不自建 /ws/ops）；④billing 待支付轮询源；
 * ⑤pharmacy 待配药轮询源；危急值段固定空数组+降级标志（M07 检验域未建，缺位降级明示——
 * NurseBoardVO.criticalValues 同款先例）。
 *
 * <p>业务时钟取北京钟面（TimeConstants.HEALTHCARE_TZ——时区红线，禁裸 now()）。无状态单例；
 * 装配归 OpsWebConfig @Import；本类为 fuyun-ops 唯一业务实现，单测全覆盖（册 2 验收线：
 * 聚合 service 单测 100%）。
 */
@Slf4j
public class OpsWorkbenchServiceImpl implements IOpsWorkbenchService {

    /** 主题指引行：IoT 设备状态（/ws/iot 端点，占位段按病区替换） */
    private static final Topic TOPIC_IOT_DEVICE_STATUS =
            new Topic("/ws/iot", "/topic/iot/device-status/{wardId}", "病区设备状态变更（IotFanoutListener 推送）");

    /** 主题指引行：护理病区动态（/ws/nursing 端点，占位段按病区替换） */
    private static final Topic TOPIC_NURSING_BOARD =
            new Topic("/ws/nursing", "/topic/nursing/board/{wardId}", "护士站大屏增量推送（出入院/任务/输液动态）");

    /** 主题指引行：门诊候诊叫号（/ws/outpatient 端点，占位段按诊区科室替换） */
    private static final Topic TOPIC_OUTPATIENT_QUEUE =
            new Topic("/ws/outpatient", "/topic/outpatient/queue/{deptCode}", "诊区候诊叫号推送（建队/叫号/过号）");

    /** 事件流来源域词表：检验（危急值占位——M07 缺位注记） */
    private static final String SOURCE_LAB = "lab";

    /** 四模块统计 Port 与缓存通道（构造器注入，宪法 A.1-7） */
    private final OutpatientStatsPort outpatientStatsPort;

    private final BillingStatsPort billingStatsPort;

    private final PharmacyStatsPort pharmacyStatsPort;

    private final NursingStatsPort nursingStatsPort;

    /** String 模板（禁 JDK 序列化），非空；overview 快照缓存唯一通道 */
    private final StringRedisTemplate redisTemplate;

    /** JSON 转换器，非空；快照序列化/反序列化（Boot 容器实例，records/jsr310 已注册） */
    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 OpsWebConfig @Import，backend 宪法 B.1）。
     *
     * @param outpatientStatsPort 门诊工作量统计 Port（M03 api 面），非空；六格两格/候诊表/趋势源
     * @param billingStatsPort    收费工作量统计 Port（M13 api 面），非空；收入/待结算两格与待支付源
     * @param pharmacyStatsPort   药事工作量统计 Port（M06 api 面），非空；待配药格与轮询源
     * @param nursingStatsPort    护理工作量统计 Port（M05 api 面），非空；在院人数格
     * @param redisTemplate       String 模板，非空；快照缓存唯一通道
     * @param objectMapper        JSON 转换器，非空；快照序列化/反序列化
     */
    public OpsWorkbenchServiceImpl(
            OutpatientStatsPort outpatientStatsPort,
            BillingStatsPort billingStatsPort,
            PharmacyStatsPort pharmacyStatsPort,
            NursingStatsPort nursingStatsPort,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.outpatientStatsPort = outpatientStatsPort;
        this.billingStatsPort = billingStatsPort;
        this.pharmacyStatsPort = pharmacyStatsPort;
        this.nursingStatsPort = nursingStatsPort;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 总览聚合入口：缓存命中直返（读失败/损坏降级直算），穿透直算四 Port 并回写缓存（TTL 5s）。
     */
    @Override
    public WorkbenchOverviewVO overview() {
        // 读路径：缓存命中直返（缓存面可重建，缺席/损坏不阻断聚合主链）
        WorkbenchOverviewVO cached = readCachedOverview();
        if (cached != null) {
            return cached;
        }
        WorkbenchOverviewVO computed = aggregateOverview();
        writeCachedOverview(computed);
        return computed;
    }

    /**
     * 总览四 Port 直算聚合（无缓存）：近 14 日窗口一次圈定，四 Port 各取一段后映射出参。
     *
     * @return 总览快照，非空；无业务数据返回零值/空清单视图（不造数）
     */
    private WorkbenchOverviewVO aggregateOverview() {
        LocalDate today = LocalDate.now(TimeConstants.HEALTHCARE_TZ);
        // 趋势窗口含今日在内近 14 个自然日（立项计划册 2 冻结口径）
        TrendWindow window = new TrendWindow(today.minusDays(OpsConstants.TREND_DAYS - 1L), today);
        // 跨模块取数：四 Port api 面调用（禁直查他模块表，宪法 B.2-2）——零写面纯只读
        OutpatientWorkloadStats outpatient = outpatientStatsPort.workloadStats(window);
        BillingWorkloadStats billing = billingStatsPort.workloadStats(today);
        PharmacyWorkloadStats pharmacy = pharmacyStatsPort.pendingStats();
        NursingWorkloadStats nursing = nursingStatsPort.inHospitalStats();
        List<TrendPoint> trend = outpatient.trend().stream()
                .map(row -> new TrendPoint(row.statDate(), row.visitCount(), row.emergencyCount()))
                .toList();
        List<WaitingRow> waitingTable = outpatient.waitingByDept().stream()
                .map(row -> new WaitingRow(row.deptCode(), row.waitingCount(), row.longestWaitingMinutes()))
                .toList();
        Metrics metrics = new Metrics(
                outpatient.todayVisits(),
                outpatient.waitingCount(),
                billing.todayIncomeFen(),
                nursing.inHospitalCount(),
                pharmacy.pendingDispenseCount(),
                billing.pendingSettleCount());
        log.info(
                "工作台总览聚合完成：todayVisits={}，waitingCount={}，todayIncomeFen={}，"
                        + "inHospitalCount={}，pendingDispenseCount={}，pendingSettleCount={}，trendDays={}",
                outpatient.todayVisits(),
                outpatient.waitingCount(),
                billing.todayIncomeFen(),
                nursing.inHospitalCount(),
                pharmacy.pendingDispenseCount(),
                billing.pendingSettleCount(),
                trend.size());
        return new WorkbenchOverviewVO(metrics, trend, waitingTable, OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ));
    }

    /**
     * 事件流五源装配（无缓存——两轮询源轻量查询直算）：主题指引 + 双源待办合并降序有界 +
     * 危急值缺位降级段。
     *
     * @return 事件流快照，非空
     */
    @Override
    public WorkbenchEventsVO events() {
        List<WorkEvent> events = mergeEvents();
        log.info("工作台事件流装配完成：events={}（危急值段恒空——M07 缺位降级注记）", events.size());
        return new WorkbenchEventsVO(
                List.of(TOPIC_IOT_DEVICE_STATUS, TOPIC_NURSING_BOARD, TOPIC_OUTPATIENT_QUEUE),
                events,
                List.of(),
                true,
                OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ));
    }

    /**
     * 双轮询源合并：billing 待支付 + pharmacy 待配药事件行映射合并，occurredAt 降序有界截断
     * （EVENTS_LIMIT 总上限，大屏有界纪律）。
     *
     * @return 合并事件清单（occurredAt 降序，≤ EVENTS_LIMIT）；无待办返回空清单
     */
    private List<WorkEvent> mergeEvents() {
        List<WorkEvent> merged = new ArrayList<>();
        // 轮询源④：billing 待支付费用（当日 PENDING 行派生，计费类携金额分值）
        billingStatsPort
                .workloadStats(LocalDate.now(TimeConstants.HEALTHCARE_TZ))
                .pendingFeeEvents()
                .forEach(fee -> merged.add(new WorkEvent(
                        fee.feeNo(),
                        OpsConstants.EVENT_TYPE_FEE_PENDING,
                        "billing",
                        fee.itemName(),
                        fee.amountFen(),
                        fee.chargedAt())));
        // 轮询源⑤：pharmacy 待配药在途单（CREATED/PICKING 行派生，非计费类零金额）
        pharmacyStatsPort
                .pendingStats()
                .pendingDispenseEvents()
                .forEach(dispense -> merged.add(new WorkEvent(
                        dispense.dispenseNo(),
                        OpsConstants.EVENT_TYPE_DISPENSE_PENDING,
                        "pharmacy",
                        dispense.rxNo(),
                        null,
                        dispense.createdAt())));
        // occurredAt 降序（最近在前，空时点防御殿后），总上限截断
        merged.sort(Comparator.comparing(WorkEvent::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return merged.size() > OpsConstants.EVENTS_LIMIT
                ? List.copyOf(merged.subList(0, OpsConstants.EVENTS_LIMIT))
                : merged;
    }

    /**
     * 读总览快照缓存（String JSON → record）：键缺席返回 null；JSON 损坏/Redis 异常 warn 降级
     * 返回 null（直算承接，缓存可重建不阻断）。
     *
     * @return 缓存快照；缺席或降级为 null
     */
    private WorkbenchOverviewVO readCachedOverview() {
        try {
            String json = redisTemplate.opsForValue().get(OpsConstants.OVERVIEW_SNAPSHOT_KEY);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, WorkbenchOverviewVO.class);
        } catch (JsonProcessingException e) {
            log.warn("总览快照解析失败（降级直算覆盖）：key={}，原因={}", OpsConstants.OVERVIEW_SNAPSHOT_KEY, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("总览快照读取失败（降级直算）：key={}，原因={}", OpsConstants.OVERVIEW_SNAPSHOT_KEY, e.getMessage());
            return null;
        }
    }

    /**
     * 写总览快照缓存（String JSON + 显式 TTL 5s，禁无 TTL 键红线）：Redis 异常/序列化异常仅
     * warn 降级不阻断调用方主链（缓存可重建）。
     *
     * @param overview 待缓存快照，非空
     */
    private void writeCachedOverview(WorkbenchOverviewVO overview) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            OpsConstants.OVERVIEW_SNAPSHOT_KEY,
                            objectMapper.writeValueAsString(overview),
                            OpsConstants.OVERVIEW_SNAPSHOT_TTL);
        } catch (Exception e) {
            log.warn("总览快照写入失败（缓存降级不影响主链）：key={}，原因={}", OpsConstants.OVERVIEW_SNAPSHOT_KEY, e.getMessage());
        }
    }
}
