package com.fuyun.billing.internal;

import com.fuyun.billing.service.IInpatientChargeService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 住院持续性费用日切定时任务（M13 住院计费联动，P2 PR-1 Task 13）：02:30 全院在院就诊生成
 * 当日床位费 PENDING 行（在 inpatient 长期医嘱日切分解[02:00]之后错峰）。
 *
 * <p>调度口径（A.5-14）：@Scheduled + ShedLock 多实例互斥（cron 每日 02:30 夜间低峰；
 * 总开关 fuyun-app SchedulingConfig @EnableScheduling 既有形态，禁自建配置面）。幂等与
 * 出院跳过全归 {@link IInpatientChargeService#dailyBedCharge}（计费唯一键 visit×日防重 +
 * DISCHARGE_STOP 标记人群剔除）——本任务仅调度触发与监控日志。归 internal/：模块内设施禁外引
 * （装配归 BillingMessagingConfig @Import——OrderPlanDecomposeJob 经 InpatientMessagingConfig
 * 注册先例）。
 */
@Slf4j
public class InpatientDailyChargeJob {

    private final IInpatientChargeService inpatientChargeService;

    /**
     * 全参构造器（装配归 BillingMessagingConfig @Import）。
     *
     * @param inpatientChargeService 住院计费联动服务（床位费日切业务面），非空
     */
    public InpatientDailyChargeJob(IInpatientChargeService inpatientChargeService) {
        this.inpatientChargeService = inpatientChargeService;
    }

    /**
     * 每日床位费日切（锁名唯一；lockAtMostFor 覆盖全院在院就诊逐行计价的运行时长上界）。
     */
    @Scheduled(cron = "0 30 2 * * ?")
    @SchedulerLock(name = "billing-inpatient-daily-bed-charge", lockAtMostFor = "PT30M")
    public void chargeDailyBedFee() {
        log.info("住院床位费日切任务触发（02:30，在院就诊当日床位费）");
        int charged = inpatientChargeService.dailyBedCharge();
        log.info("住院床位费日切任务完成：新生成床位费行数={}", charged);
    }
}
