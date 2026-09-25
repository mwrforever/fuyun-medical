package com.fuyun.inpatient.internal;

import com.fuyun.inpatient.service.OrderPlanService;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 长期医嘱日切分解定时任务（FU-M04-06 下，Spec §3.2 选定方案：凌晨分解次日计划——执行单
 * 有整晚提前量供核对打印，分解异常夜内有整晚缓冲）。
 *
 * <p>调度口径（A.5-14）：@Scheduled + ShedLock 多实例互斥（cron 每日 02:00 夜间低峰——
 * PatientDuplicateScanJob 02:30 错峰错开；总开关 fuyun-app SchedulingConfig @EnableScheduling
 * 既有形态，禁自建配置面）。批量分解（候选查询+分批 500 一事务+查前置/唯一约束双幂等）全归
 * {@link OrderPlanService#decomposeNextDay}——本任务仅调度触发与监控日志。归 internal/：
 * 模块内设施禁外引（装配归 InpatientMessagingConfig @Import——patient 段
 * PatientDuplicateScanJob 经 PatientMessagingConfig 注册先例）。
 */
@Slf4j
public class OrderPlanDecomposeJob {

    private final OrderPlanService orderPlanService;

    /**
     * 全参构造器（装配归 InpatientMessagingConfig @Import）。
     *
     * @param orderPlanService 医嘱执行计划服务（日切批量分解业务面），非空
     */
    public OrderPlanDecomposeJob(OrderPlanService orderPlanService) {
        this.orderPlanService = orderPlanService;
    }

    /**
     * 每日夜切分解次日计划（锁名唯一；lockAtMostFor 覆盖全院分解上界——千级医嘱×时点×分批
     * 事务的运行时长边界）。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "inpatient-order-plan-decompose", lockAtMostFor = "PT30M")
    public void decompose() {
        // 次日计划日期（凌晨 02:00 触发——生成当日剩余夜间时点与日间全部时点）
        LocalDate planDate = LocalDate.now().plusDays(1);
        log.info("长期医嘱日切分解任务开始：planDate={}", planDate);
        int created = orderPlanService.decomposeNextDay(planDate);
        log.info("长期医嘱日切分解任务完成：planDate={}，生成次日计划 {} 行", planDate, created);
    }
}
