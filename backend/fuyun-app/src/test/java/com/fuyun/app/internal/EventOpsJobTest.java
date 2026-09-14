package com.fuyun.app.internal;

import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;

/**
 * EventOpsJob 单测：断言运维任务对注册表 API 的委托参数（阈值常量为 D-2 裁决设计值）。
 */
class EventOpsJobTest {

    @Test
    @DisplayName("重试任务按 5 分钟卡住阈值重投未完成发布")
    void retryStuckResubmitsByFiveMinuteThreshold() {
        IncompleteEventPublications incomplete = Mockito.mock(IncompleteEventPublications.class);
        CompletedEventPublications completed = Mockito.mock(CompletedEventPublications.class);
        EventOpsJob job = new EventOpsJob(incomplete, completed);

        job.retryStuck();

        verify(incomplete).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("清理任务按 7 天保留期删除已完成发布")
    void cleanupDeletesBySevenDayRetention() {
        IncompleteEventPublications incomplete = Mockito.mock(IncompleteEventPublications.class);
        CompletedEventPublications completed = Mockito.mock(CompletedEventPublications.class);
        EventOpsJob job = new EventOpsJob(incomplete, completed);

        job.cleanup();

        verify(completed).deletePublicationsOlderThan(Duration.ofDays(7));
    }
}
