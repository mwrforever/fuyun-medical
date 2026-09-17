package com.fuyun.patient.internal;

import com.fuyun.patient.service.IPossibleDuplicateService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 疑似重复批量增量扫描任务（M02 §4 双渠道之二：周期性批量扫描，Spec §9 错峰执行）。
 *
 * <p>调度口径（A.5-14）：@Scheduled + ShedLock 多实例互斥（cron 每日 02:30 错峰，默认值经
 * fuyun.patient.empi.scan-cron 注入）；幂等可重跑——(a,b) 唯一索引兜底重复命中（scanBatch 内
 * DuplicateKeyException 幂等静默）。归 internal/：模块内设施禁外引（装配归 PatientMessagingConfig，
 * Task 13）。
 */
@Slf4j
public class PatientDuplicateScanJob {

    private final IPossibleDuplicateService duplicateService;

    /**
     * 全参构造器（装配归 PatientMessagingConfig @Import，Task 13）。
     *
     * @param duplicateService 疑似重复治理服务，非空
     */
    public PatientDuplicateScanJob(IPossibleDuplicateService duplicateService) {
        this.duplicateService = duplicateService;
    }

    /**
     * 每日错峰扫描（锁名唯一；lockAtMostFor 覆盖全量扫描上界）。
     *
     * @return 本次新增待审行数（监控取值点）
     */
    @Scheduled(cron = "${fuyun.patient.empi.scan-cron:0 30 2 * * *}")
    @SchedulerLock(name = "patient-duplicate-scan", lockAtMostFor = "PT30M")
    public int scan() {
        log.info("疑似重复批量扫描任务开始");
        int created = duplicateService.scanBatch();
        log.info("疑似重复批量扫描任务完成：新增待审={}", created);
        return created;
    }
}
