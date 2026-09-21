package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.pharmacy.api.PrescriptionOpenCommand;
import com.fuyun.pharmacy.api.PrescriptionOpenResult;
import com.fuyun.pharmacy.dto.PrescriptionCreateRequest;
import com.fuyun.pharmacy.service.IPrescriptionService;
import com.fuyun.pharmacy.vo.PrescriptionVO;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 处方开立端口单测（跨模块 PortImpl 转调件——pharmacy service.impl 包 LINE=1.00 行覆盖承载）：
 * 命令对象 → 开方入参逐组件镜像映射 + 结果四组件映射断言，禁第二套开方逻辑（纯转调既有 create 主链）。
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionOpenPortImplTest {

    @Mock
    private IPrescriptionService prescriptionService;

    @Test
    @DisplayName("开方端口转调：cmd→create 镜像映射逐组件断言 + 结果 rxNo/status/reviewLevel/skinTestRequired 四组件映射")
    void openDelegatesAndMapsResultComponents() {
        PrescriptionOpenPortImpl impl = new PrescriptionOpenPortImpl(prescriptionService);
        PrescriptionOpenCommand cmd = new PrescriptionOpenCommand(
                700101L,
                "O2026092100001",
                "OUTPATIENT",
                "NEIKE",
                List.of("J06.900"),
                Boolean.TRUE,
                List.of(new PrescriptionOpenCommand.Item(11L, "2", "盒", "0.5g", "ORAL", "TID", 3, "饭后服")));
        PrescriptionVO vo = new PrescriptionVO(
                100L,
                "R20260921000001",
                "OUTPATIENT",
                700101L,
                "O2026092100001",
                "3",
                "NEIKE",
                List.of("J06.900"),
                "NORMAL",
                true,
                "PASS",
                "APPROVED",
                null,
                List.of(),
                null);
        when(prescriptionService.create(any(PrescriptionCreateRequest.class))).thenReturn(vo);

        PrescriptionOpenResult result = impl.open(cmd);

        // 转调断言：命令对象逐组件镜像映射进既有 create 入参（dto 禁外引，api 面镜像）
        ArgumentCaptor<PrescriptionCreateRequest> captor = ArgumentCaptor.forClass(PrescriptionCreateRequest.class);
        verify(prescriptionService).create(captor.capture());
        PrescriptionCreateRequest mapped = captor.getValue();
        assertThat(mapped.patientId()).isEqualTo(700101L);
        assertThat(mapped.visitId()).isEqualTo("O2026092100001");
        assertThat(mapped.rxType()).isEqualTo("OUTPATIENT");
        assertThat(mapped.deptCode()).isEqualTo("NEIKE");
        assertThat(mapped.diagnosisCodes()).containsExactly("J06.900");
        assertThat(mapped.skinTestRequired()).isTrue();
        assertThat(mapped.items()).hasSize(1);
        assertThat(mapped.items().get(0).drugId()).isEqualTo(11L);
        assertThat(mapped.items().get(0).quantity()).isEqualTo("2");
        assertThat(mapped.items().get(0).unit()).isEqualTo("盒");
        assertThat(mapped.items().get(0).singleDose()).isEqualTo("0.5g");
        assertThat(mapped.items().get(0).routeCode()).isEqualTo("ORAL");
        assertThat(mapped.items().get(0).frequency()).isEqualTo("TID");
        assertThat(mapped.items().get(0).days()).isEqualTo(3);
        assertThat(mapped.items().get(0).usageNote()).isEqualTo("饭后服");
        // 结果映射断言：PrescriptionVO 四组件 → 出参（Task 12 IT 依赖的冻结面）
        assertThat(result.rxNo()).isEqualTo("R20260921000001");
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.reviewLevel()).isEqualTo("PASS");
        assertThat(result.skinTestRequired()).isTrue();
    }
}
