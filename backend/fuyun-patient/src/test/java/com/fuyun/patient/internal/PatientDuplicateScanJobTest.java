package com.fuyun.patient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fuyun.patient.service.IPossibleDuplicateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 批量扫描任务单测：委托 scanBatch 并透传计数（ShedLock/cron 由装配与真栈验证承载）。 */
class PatientDuplicateScanJobTest {

    @Test
    @DisplayName("scan 委托 scanBatch 并返回新增计数")
    void scanDelegatesToBatchScan() {
        IPossibleDuplicateService duplicateService = mock(IPossibleDuplicateService.class);
        org.mockito.Mockito.when(duplicateService.scanBatch()).thenReturn(3);
        int created = new PatientDuplicateScanJob(duplicateService).scan();
        assertThat(created).isEqualTo(3);
        verify(duplicateService).scanBatch();
    }
}
