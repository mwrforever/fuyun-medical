package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientCreatedPayload;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.dto.PatientCreateRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.gateway.IdentityMediaGateway;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.IPrivacyAuthService;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * 患者建档主用例单测（FU-M02-01/02）：归一不新建、新建发 created 事件、标识撞唯一转业务失败、
 * 未实名标记、知情同意落痕五条核心路径，另补卡类/非卡介质并行挂接两条介质路径
 * （核心功能 100% 行覆盖的主体）。
 */
@ExtendWith(MockitoExtension.class)
class PatientRegistrationServiceImplTest {

    @Mock
    private PatientMatchingService matchingService;

    @Mock
    private IPatientService patientService;

    @Mock
    private IPatientIdentifierService identifierService;

    @Mock
    private IPrivacyAuthService privacyAuthService;

    @Mock
    private PatientFieldCrypto crypto;

    @Mock
    private IdentityMediaGateway mediaGateway;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PatientRegistrationServiceImpl registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new PatientRegistrationServiceImpl(
                matchingService,
                patientService,
                identifierService,
                privacyAuthService,
                crypto,
                mediaGateway,
                eventPublisher);
        // 加密构件替身按真实语义 null 进 null 出（可空列禁落空串占位断言的前提）
        lenient().when(crypto.encrypt(any())).thenAnswer(inv -> inv.getArgument(0) == null ? null : "cipher");
        lenient().when(crypto.hash(any())).thenAnswer(inv -> inv.getArgument(0) == null ? null : "hash");
        lenient()
                .when(mediaGateway.verify(anyString(), any()))
                .thenReturn(new IdentityMediaGateway.IdentityExtract(true, "ID_CARD", "v", null, true));
        lenient()
                .when(identifierService.attach(anyLong(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(99L);
        lenient()
                .when(privacyAuthService.recordInformedConsent(anyLong(), anyString()))
                .thenReturn(98L);
    }

    private PatientCreateRequest request() {
        return new PatientCreateRequest(
                "张三",
                "1",
                "1990-03-07",
                null,
                null,
                null,
                null,
                "110101199003077890",
                "13800001234",
                "北京市朝阳区",
                "WINDOW",
                "STANDARD",
                null,
                null,
                null,
                "paper-001");
    }

    @Test
    @DisplayName("强标识 AUTO_MATCH：归一返回既有档 id、补挂标识、不新建不发 created 事件")
    void autoMatchNormalizesToExistingArchiveWithoutCreating() {
        when(matchingService.preCheck(any()))
                .thenReturn(new PatientMatchCheckVO("AUTO_MATCH", 1L, null, List.of("ID_CARD_EXACT")));
        PatientMatchCheckVO vo = registrationService.register(request());
        assertThat(vo.candidatePatientId()).isEqualTo(1L);
        verify(identifierService).attach(eq(1L), eq("ID_CARD"), eq("110101199003077890"), eq(null), eq(true));
        verify(patientService, never()).save(any(Patient.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("NO_MATCH 新建：保存主索引、落知情同意、发布 patient.created 应用事件（载荷无敏感明文）")
    void noMatchCreatesArchiveAndPublishesCreatedEvent() {
        when(matchingService.preCheck(any())).thenReturn(new PatientMatchCheckVO("NO_MATCH", null, null, List.of()));
        when(patientService.save(any(Patient.class))).thenAnswer(inv -> {
            inv.getArgument(0, Patient.class).setPatientId(777L);
            return true;
        });
        PatientMatchCheckVO vo = registrationService.register(request());
        assertThat(vo.candidatePatientId()).isEqualTo(777L);
        ArgumentCaptor<PatientDomainEvent> eventCaptor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        PatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(PatientMessagingConstants.EVENT_CREATED);
        PatientCreatedPayload payload = (PatientCreatedPayload) event.payload();
        assertThat(payload.patientId()).isEqualTo(777L);
        assertThat(payload.realNameFlag()).isTrue();
        // 敏感红线：载荷不含姓名/证件号/手机号明文
        assertThat(String.valueOf(payload))
                .doesNotContain("张三")
                .doesNotContain("110101199003077890")
                .doesNotContain("13800001234");
        verify(privacyAuthService).recordInformedConsent(eq(777L), eq("paper-001"));
    }

    @Test
    @DisplayName("介质核验降级（无证件号）：档案标记未实名（授权建档路径）")
    void degradedVerificationMarksUnrealName() {
        when(mediaGateway.verify(anyString(), any()))
                .thenReturn(new IdentityMediaGateway.IdentityExtract(false, "ID_CARD", null, null, false));
        when(matchingService.preCheck(any())).thenReturn(new PatientMatchCheckVO("NO_MATCH", null, null, List.of()));
        when(patientService.save(any(Patient.class))).thenAnswer(inv -> {
            inv.getArgument(0, Patient.class).setPatientId(888L);
            return true;
        });
        PatientCreateRequest req = new PatientCreateRequest(
                "无名氏",
                "1",
                null,
                null,
                null,
                null,
                null,
                "",
                "",
                "",
                "EMERGENCY",
                "TEMP_ANONYMOUS",
                null,
                null,
                null,
                "paper-002");
        registrationService.register(req);
        ArgumentCaptor<Patient> patientCaptor = ArgumentCaptor.forClass(Patient.class);
        verify(patientService).save(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getRealNameFlag()).isFalse();
        assertThat(patientCaptor.getValue().getArchiveSource()).isEqualTo("TEMP_ANONYMOUS");
    }

    @Test
    @DisplayName("标识撞唯一约束：PAT-1002 业务失败上抛（数据库最终兜底语义）")
    void duplicateIdentifierSurfacesAsBusinessFailure() {
        when(matchingService.preCheck(any())).thenReturn(new PatientMatchCheckVO("NO_MATCH", null, null, List.of()));
        when(patientService.save(any(Patient.class))).thenAnswer(inv -> {
            inv.getArgument(0, Patient.class).setPatientId(999L);
            return true;
        });
        when(identifierService.attach(anyLong(), anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new BizException(
                        PatientErrorCode.IDENTIFIER_ALREADY_BOUND, HttpStatus.CONFLICT, "该标识已登记在其他患者档案"));
        assertThatThrownBy(() -> registrationService.register(request())).isInstanceOf(BizException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("并行卡类介质（电子健康卡）：建档时随证件号一并挂接且回填卡面号、非主标识")
    void cardMediumAttachedAlongsideIdCard() {
        when(matchingService.preCheck(any())).thenReturn(new PatientMatchCheckVO("NO_MATCH", null, null, List.of()));
        when(patientService.save(any(Patient.class))).thenAnswer(inv -> {
            inv.getArgument(0, Patient.class).setPatientId(778L);
            return true;
        });
        PatientCreateRequest req = new PatientCreateRequest(
                "张三",
                "1",
                "1990-03-07",
                null,
                null,
                null,
                null,
                "110101199003077890",
                null,
                null,
                "WINDOW",
                "STANDARD",
                "HEALTH_CARD",
                "H32019900101",
                "CARD-0001",
                "paper-003");
        registrationService.register(req);
        // 证件号恒为主标识（归一检索主路径）；卡类介质并行挂接回填卡面号、非主标识
        verify(identifierService).attach(eq(778L), eq("ID_CARD"), eq("110101199003077890"), eq(null), eq(true));
        verify(identifierService).attach(eq(778L), eq("HEALTH_CARD"), eq("H32019900101"), eq("CARD-0001"), eq(false));
    }

    @Test
    @DisplayName("并行非卡介质（护照）：挂接不携带卡面号（卡面号仅卡类介质语义）")
    void nonCardMediumAttachedWithoutCardNo() {
        when(matchingService.preCheck(any())).thenReturn(new PatientMatchCheckVO("NO_MATCH", null, null, List.of()));
        when(patientService.save(any(Patient.class))).thenAnswer(inv -> {
            inv.getArgument(0, Patient.class).setPatientId(779L);
            return true;
        });
        PatientCreateRequest req = new PatientCreateRequest(
                "张三",
                "1",
                "1990-03-07",
                null,
                null,
                null,
                null,
                "110101199003077890",
                null,
                null,
                "WINDOW",
                "STANDARD",
                "PASSPORT",
                "E12345678",
                "误录卡面号应被忽略",
                "paper-004");
        registrationService.register(req);
        // 非卡介质：cardNo 恒为 null（请求误录卡面号亦被忽略）
        verify(identifierService).attach(eq(779L), eq("PASSPORT"), eq("E12345678"), eq(null), eq(false));
    }

    @Test
    @DisplayName("档案来源缺省：archiveSource 未传时新建档案落默认 STANDARD（渠道端未传的兜底口径）")
    void blankArchiveSourceDefaultsToStandard() {
        when(matchingService.preCheck(any())).thenReturn(new PatientMatchCheckVO("NO_MATCH", null, null, List.of()));
        when(patientService.save(any(Patient.class))).thenAnswer(inv -> {
            inv.getArgument(0, Patient.class).setPatientId(780L);
            return true;
        });
        PatientCreateRequest req = new PatientCreateRequest(
                "张三",
                "1",
                "1990-03-07",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "WINDOW",
                null,
                null,
                null,
                null,
                "paper-005");
        registrationService.register(req);
        ArgumentCaptor<Patient> patientCaptor = ArgumentCaptor.forClass(Patient.class);
        verify(patientService).save(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getArchiveSource()).isEqualTo("STANDARD");
        // 请求无证件号/手机号：密文与盲索引列落 null（可空列语义，禁落空串占位）
        assertThat(patientCaptor.getValue().getIdCardNoCipher()).isNull();
        assertThat(patientCaptor.getValue().getMobileHash()).isNull();
    }
}
