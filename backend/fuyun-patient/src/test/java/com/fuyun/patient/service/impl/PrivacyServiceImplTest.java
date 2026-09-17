package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.UnmaskRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PrivacyAccessLog;
import com.fuyun.patient.enums.MaskTargetField;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PrivacyAccessLogMapper;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PrivacyMaskService;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.UnmaskVO;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/**
 * 明文查阅与留痕服务单测（FU-M02-06 双留痕出口）：403 无豁免前置拒绝不落台账、豁免放行台账落痕、
 * 档案不存在守卫、台账空页与词表收口抛错；明文值断言仅存活于用例内，日志红线（禁打印明文）由实现保证。
 */
@ExtendWith(MockitoExtension.class)
class PrivacyServiceImplTest {

    @Mock
    private PrivacyMaskService privacyMaskService;

    @Mock
    private IPatientService patientService;

    @Mock
    private PrivacyAccessLogMapper privacyAccessLogMapper;

    @Mock
    private PatientFieldCrypto crypto;

    private PrivacyServiceImpl privacyService;

    @BeforeAll
    static void initTableInfo() {
        // listAccessLogs 的 wrapper orderBy 列解析依赖实体表信息（模块内既有单测同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrivacyAccessLog.class);
    }

    @BeforeEach
    void setUp() {
        privacyService = new PrivacyServiceImpl(privacyMaskService, patientService, privacyAccessLogMapper, crypto);
        // 豁免角色与操作人上下文种子（ADMIN 命中种子规则豁免清单）；用例间隔离收尾必清
        RoleContextHolder.set(List.of("ADMIN"));
        OperatorContextHolder.set("op-001");
    }

    @AfterEach
    void tearDown() {
        RoleContextHolder.clear();
        OperatorContextHolder.clear();
        MDC.clear();
    }

    /** 档案替身：敏感三列密文占位（明文经 crypto 替身解出，密文值不出现在断言外） */
    private Patient patient() {
        Patient patient = new Patient();
        patient.setPatientId(5L);
        patient.setName("张三");
        patient.setIdCardNoCipher("card-cipher");
        patient.setMobileCipher("mobile-cipher");
        patient.setAddressCipher("addr-cipher");
        patient.setBirthDate(LocalDate.of(1990, 3, 7));
        return patient;
    }

    @Test
    @DisplayName("明文查阅无豁免角色：PAT-1018 403 前置拒绝，不触档案不落查阅台账（留痕归审计 FAIL 行）")
    void unmaskWithoutExemptRoleRejectedBeforeAnyTrace() {
        when(privacyMaskService.isExempt(anyList(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> privacyService.unmask(new UnmaskRequest(5L, List.of("idCardNo"), "临床核验")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.UNMASK_NOT_AUTHORIZED);

        // 403 分支无查阅事实：台账零落行；档案解密链零触达（明文不出）
        verify(privacyAccessLogMapper, never()).insert(any(PrivacyAccessLog.class));
        verifyNoInteractions(patientService, crypto);
    }

    @Test
    @DisplayName("明文查阅豁免放行：全词表五字段解密直出，台账落 UNMASK_QUERY 行（操作人/目的/字段/traceId null 安全）")
    void unmaskWithExemptRoleReturnsPlaintextAndWritesLedger() {
        when(privacyMaskService.isExempt(anyList(), anyString())).thenReturn(true);
        when(patientService.getById(5L)).thenReturn(patient());
        when(crypto.decrypt("card-cipher")).thenReturn("110101199003077890");
        when(crypto.decrypt("mobile-cipher")).thenReturn("13800001234");
        when(crypto.decrypt("addr-cipher")).thenReturn("北京市朝阳区xx路1号");

        // 请求全词表五字段：plaintextOf 穷举分派行全覆盖（审查 I3 词表收口）
        UnmaskRequest request =
                new UnmaskRequest(5L, List.of("name", "idCardNo", "mobile", "address", "birthDate"), "临床核验");
        UnmaskVO vo = privacyService.unmask(request);

        assertThat(vo.patientId()).isEqualTo(5L);
        assertThat(vo.values())
                .containsEntry("name", "张三")
                .containsEntry("idCardNo", "110101199003077890")
                .containsEntry("mobile", "13800001234")
                .containsEntry("address", "北京市朝阳区xx路1号")
                .containsEntry("birthDate", "1990-03-07");

        ArgumentCaptor<PrivacyAccessLog> captor = ArgumentCaptor.forClass(PrivacyAccessLog.class);
        verify(privacyAccessLogMapper).insert(captor.capture());
        PrivacyAccessLog ledger = captor.getValue();
        assertThat(ledger.getAccessType()).isEqualTo("UNMASK_QUERY");
        assertThat(ledger.getOperatorId()).isEqualTo("op-001");
        assertThat(ledger.getPatientId()).isEqualTo(5L);
        assertThat(ledger.getPurpose()).isEqualTo("临床核验");
        assertThat(ledger.getFields()).isEqualTo("name,idCardNo,mobile,address,birthDate");
        // 单测无链路上下文（MDC 清理态）：traceId null 安全落空列
        assertThat(ledger.getTraceId()).isNull();
    }

    @Test
    @DisplayName("明文查阅档案不存在：PAT-1001 404 拒绝且不落台账（无查阅事实）")
    void unmaskMissingArchiveRejectedAndNoLedgerRow() {
        when(privacyMaskService.isExempt(anyList(), anyString())).thenReturn(true);
        when(patientService.getById(5L)).thenReturn(null);

        assertThatThrownBy(() -> privacyService.unmask(new UnmaskRequest(5L, List.of("name"), "临床核验")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);

        verify(privacyAccessLogMapper, never()).insert(any(PrivacyAccessLog.class));
    }

    @Test
    @DisplayName("查阅台账分页：空台账返回空数据页（0 基页码与总条数直映）")
    void listAccessLogsReturnsEmptyPageOnEmptyLedger() {
        when(privacyAccessLogMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<PrivacyAccessLog> page = inv.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        });

        PageResult<PrivacyAccessLogVO> result = privacyService.listAccessLogs(5L, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isZero();
    }

    @Test
    @DisplayName("词表收口：未知落库词经 MaskTargetField.ofColumn 抛 IllegalArgumentException（脏数据显式暴露）")
    void ofColumnThrowsOnUnknownWord() {
        assertThatThrownBy(() -> MaskTargetField.ofColumn("unknown")).isInstanceOf(IllegalArgumentException.class);
    }
}
