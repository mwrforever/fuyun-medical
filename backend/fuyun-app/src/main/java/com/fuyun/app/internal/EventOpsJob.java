package com.fuyun.app.internal;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Modulith 事件注册表运维任务：重试卡住的未完成发布 + 清理过期已完成记录（D-2 裁决设计，
 * 宪法 B.3-3 可靠事件投递的定时侧）。
 *
 * <p>设计语义：注册表在业务事务内写入发布记录、提交后异步投递；监听器失败（或应用实例中途宕机）
 * 的发布停留在未完成态，由本任务以「卡住超 5 分钟」阈值定时重投——给正常处理留窗口，不依赖重启
 * 兜底（重启重发已关闭，多实例下不安全）；已完成记录按 7 天保留期清理，防事件日志表膨胀。
 *
 * <p>多实例互斥：@SchedulerLock（ShedLock JDBC，public.shedlock，宪法 A.5-14）——多实例部署下
 * 同名锁同时仅一实例执行，lockAtMostFor 覆盖任务最长执行上界。归 app internal/：事件基础设施
 * 运维属装配域横向能力，非 M20 业务逻辑（装配模块不放业务逻辑红线不受影响）。
 */
@Component
public class EventOpsJob {

    /** 重试阈值：仅重投卡住超 5 分钟的未完成发布（给正常处理留窗口，D-2 裁决设计值） */
    static final Duration RETRY_STUCK_THRESHOLD = Duration.ofMinutes(5);

    /** 已完成发布保留期：到期清理防事件日志表膨胀（D-2 裁决设计值） */
    static final Duration COMPLETED_RETENTION = Duration.ofDays(7);

    private final IncompleteEventPublications incompletePublications;
    private final CompletedEventPublications completedPublications;

    /**
     * 全参构造器（backend 宪法 A.1-7 构造器注入）。
     *
     * @param incompletePublications 未完成发布查询与重投入口，非空；来源：starter-jdbc 自动装配
     * @param completedPublications  已完成发布清理入口，非空；来源：starter-jdbc 自动装配
     */
    EventOpsJob(IncompleteEventPublications incompletePublications, CompletedEventPublications completedPublications) {
        this.incompletePublications = incompletePublications;
        this.completedPublications = completedPublications;
    }

    /**
     * 重投卡住的未完成发布：每分钟一次（fixedDelay 串行节奏，上轮结束才计时）。
     * 首轮启动即执行：重启后对宕机期间积压的未完成发布立即补救（重启重发已关闭）。
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "modulith-event-retry", lockAtMostFor = "PT55S", lockAtLeastFor = "PT1S")
    public void retryStuck() {
        incompletePublications.resubmitIncompletePublicationsOlderThan(RETRY_STUCK_THRESHOLD);
    }

    /** 清理过期已完成发布：每小时整点（cron 秒 分 时 日 月 周 六位）。 */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "modulith-event-cleanup", lockAtMostFor = "PT55S", lockAtLeastFor = "PT1S")
    public void cleanup() {
        completedPublications.deletePublicationsOlderThan(COMPLETED_RETENTION);
    }
}
