package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.CardBindRequest;
import com.fuyun.patient.dto.CardIssueRequest;
import com.fuyun.patient.dto.CardReplaceRequest;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.service.ICardAccountService;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.vo.CardVO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * 就诊卡全生命周期实现单测（FU-M02-04 状态机）：发卡开户联动、绑定无主卡改挂、挂失账户联动
 * （账户未启用 PAT-1013 静默跳过）、补卡换号转移（余额零迁移）、解绑终态，以及各非法状态
 * 转移守卫与 identifier.changed 的 changeType 断言；绑定/补卡写库 info 留痕与卡号摘要
 * 脱敏口径经 Logback ListAppender 断言（BUG-21，iot 模块同款先例）；并发防覆写护航
 * （EX-25：CAS 0 行重读定性 409/404、输家不覆写不重复发号不联动账户）。
 */
@ExtendWith(MockitoExtension.class)
class VisitCardServiceImplTest {

    @Mock
    private IPatientIdentifierService identifierService;

    @Mock
    private ICardAccountService cardAccountService;

    @Mock
    private PatientFieldCrypto crypto;

    private VisitCardServiceImpl visitCardService;

    /** 可变卡行夹具（ACTIVE 主卡）：findByCardNo 桩返回同一引用，供状态变更后断言 */
    private PatientIdentifier cardRow;

    /** Logback 挂钩：捕获就诊卡服务日志，断言绑定/补卡写操作 info 留痕与卡号明文脱敏 */
    private ListAppender<ILoggingEvent> logAppender;

    private Logger cardLogger;

    @BeforeEach
    void setUp() {
        visitCardService = new VisitCardServiceImpl(identifierService, cardAccountService, crypto);
        cardRow = cardRow(11L, 5L, "C-0001", "ACTIVE");
        // EX-25 卡状态 CAS 抢锚默认放行（返回 1=抢得迁移权）：并发冲突用例在单测内重桩为 0；
        // lenient 同模块惯例——守卫类用例在触达 CAS 前即抛出，默认桩不消费不报错
        lenient().when(identifierService.casBindUnowned(anyLong(), anyLong())).thenReturn(1);
        lenient().when(identifierService.casMarkLost(anyLong())).thenReturn(1);
        lenient().when(identifierService.casRetireReplaced(anyLong())).thenReturn(1);
        lenient().when(identifierService.casDisable(anyLong())).thenReturn(1);
        // 挂 ListAppender 捕获服务日志（BUG-21 写操作留痕断言；tearDown 统一卸载防用例间串扰）
        cardLogger = (Logger) LoggerFactory.getLogger(VisitCardServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        cardLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        cardLogger.detachAppender(logAppender);
    }

    /** 卡行夹具构造（可变对象，供 stub 与状态断言共用同一引用） */
    private PatientIdentifier cardRow(Long id, Long patientId, String cardNo, String status) {
        PatientIdentifier row = new PatientIdentifier();
        row.setId(id);
        row.setPatientId(patientId);
        row.setIdentifierType("VISIT_CARD");
        row.setCardNo(cardNo);
        row.setStatus(status);
        return row;
    }

    @Test
    @DisplayName("发卡成功：新建 VISIT_CARD 标识并联动开户，出参映射标识行 id")
    void issueCreatesIdentifierAndOpensAccount() {
        when(identifierService.attach(5L, "VISIT_CARD", "C-1001", "C-1001", false))
                .thenReturn(55L);
        when(identifierService.getById(55L)).thenReturn(cardRow(55L, 5L, "C-1001", "ACTIVE"));

        CardVO vo = visitCardService.issue(new CardIssueRequest(5L, "C-1001"));

        assertThat(vo.identifierId()).isEqualTo(55L);
        assertThat(vo.patientId()).isEqualTo(5L);
        assertThat(vo.cardNo()).isEqualTo("C-1001");
        assertThat(vo.status()).isEqualTo("ACTIVE");
        // 一卡通启用时联动开户（默认关闭由 openIfEnabled 内部短路，发卡侧不感知开关）
        verify(cardAccountService).openIfEnabled(5L);
    }

    @Test
    @DisplayName("发卡撞已登记卡号：attach 唯一约束兜底 PAT-1002 转 PAT-1012（409 卡号已登记），不触达开户")
    void issueOnRegisteredCardNoTranslatesPat1002ToPat1012() {
        when(identifierService.attach(5L, "VISIT_CARD", "C-1001", "C-1001", false))
                .thenThrow(new BizException(
                        PatientErrorCode.IDENTIFIER_ALREADY_BOUND, HttpStatus.CONFLICT, "该标识已登记在其他患者档案"));

        assertThatThrownBy(() -> visitCardService.issue(new CardIssueRequest(5L, "C-1001")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(cardAccountService, never()).openIfEnabled(anyLong());
    }

    @Test
    @DisplayName("发卡遇其他业务失败：异常原样透传（仅卡号冲突语义转义，不吞咽无关失败）")
    void issueRethrowsUnrelatedBizException() {
        when(identifierService.attach(5L, "VISIT_CARD", "C-1001", "C-1001", false))
                .thenThrow(new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者不存在"));

        assertThatThrownBy(() -> visitCardService.issue(new CardIssueRequest(5L, "C-1001")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND));
    }

    @Test
    @DisplayName("绑定无主卡成功：改挂档案并置 ACTIVE，发布 BOUND 事件（标识值密文与盲索引不动）")
    void bindAssignsUnownedCardToPatient() {
        PatientIdentifier orphan = cardRow(11L, null, "C-0001", "DISABLED");
        when(identifierService.findByCardNo("C-0001")).thenReturn(orphan);

        CardVO vo = visitCardService.bind(new CardBindRequest("C-0001", 5L));

        assertThat(orphan.getPatientId()).isEqualTo(5L);
        assertThat(orphan.getStatus()).isEqualTo("ACTIVE");
        verify(identifierService).updateById(orphan);
        verify(identifierService).publishChanged(5L, "VISIT_CARD", "C-0001", "BOUND");
        assertThat(vo.patientId()).isEqualTo(5L);
        assertThat(vo.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("零值占位无主卡绑定成功：patientId=0 视同未挂接（历史占位口径）")
    void bindAcceptsZeroPlaceholderUnownedCard() {
        PatientIdentifier placeholder = cardRow(11L, 0L, "C-0001", "DISABLED");
        when(identifierService.findByCardNo("C-0001")).thenReturn(placeholder);

        CardVO vo = visitCardService.bind(new CardBindRequest("C-0001", 5L));

        assertThat(placeholder.getPatientId()).isEqualTo(5L);
        assertThat(placeholder.getStatus()).isEqualTo("ACTIVE");
        verify(identifierService).updateById(placeholder);
        verify(identifierService).publishChanged(5L, "VISIT_CARD", "C-0001", "BOUND");
        assertThat(vo.patientId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("绑定他档卡：PAT-1012 拒绝（409 卡已挂接档案），不改挂不发布事件")
    void bindRejectsCardOwnedByAnotherPatient() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);

        assertThatThrownBy(() -> visitCardService.bind(new CardBindRequest("C-0001", 9L)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED));
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verify(identifierService, never()).publishChanged(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("LOST 有主卡绑定同档亦拒：PAT-1012（堵挂失卡经 bind 复活绕过挂失状态机）")
    void bindRejectsLostOwnedCardEvenForSamePatient() {
        PatientIdentifier lostOwned = cardRow(11L, 5L, "C-0001", "LOST");
        when(identifierService.findByCardNo("C-0001")).thenReturn(lostOwned);

        assertThatThrownBy(() -> visitCardService.bind(new CardBindRequest("C-0001", 5L)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED));
        // 状态机不可旁路：卡行不改写、无 BOUND 事件（LOST 找回合法转移待 TASK.md D-14 裁决）
        assertThat(lostOwned.getStatus()).isEqualTo("LOST");
        assertThat(lostOwned.getPatientId()).isEqualTo(5L);
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verify(identifierService, never()).publishChanged(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("绑定成功留痕：恰一条 info 含患者 id 与卡号 HMAC 摘要，卡号明文禁入日志（BUG-21）")
    void bindWritesSingleInfoLogWithCardDigest() {
        PatientIdentifier orphan = cardRow(11L, null, "C-0001", "DISABLED");
        when(identifierService.findByCardNo("C-0001")).thenReturn(orphan);
        // 摘要桩值不含卡号明文子串，保证下方"明文不入日志"断言可分辨摘要与原文
        when(crypto.hash("C-0001")).thenReturn("hmac-d1");

        visitCardService.bind(new CardBindRequest("C-0001", 5L));

        // 写库留痕口径对齐同文件 loss/unbind：恰一条 info，业务标识为患者 id + 卡号摘要
        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).as("绑定写库恰一条 info 留痕").hasSize(1);
        String message = infoEvents.get(0).getFormattedMessage();
        assertThat(message).contains("patientId=5");
        assertThat(message).contains("hmac-d1");
        // 敏感红线（类 javadoc）：卡号明文禁入日志，仅 HMAC 摘要定位卡片
        assertThat(message).doesNotContain("C-0001");
    }

    @Test
    @DisplayName("挂失成功：置 LOST 并落解绑时刻，联动冻结账户，发布 LOST 事件")
    void lossSetsLostAndFreezesAccount() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);

        visitCardService.loss("C-0001");

        assertThat(cardRow.getStatus()).isEqualTo("LOST");
        assertThat(cardRow.getUnboundAt()).isNotNull();
        verify(identifierService).updateById(cardRow);
        verify(identifierService).publishChanged(5L, "VISIT_CARD", "C-0001", "LOST");
        verify(cardAccountService).freezeByPatient(5L);
    }

    @Test
    @DisplayName("挂失时账户未启用（PAT-1013）：联动静默跳过，挂失本身成功")
    void lossSkipsMissingAccountSilently() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);
        doThrow(new BizException(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "一卡通账户不存在"))
                .when(cardAccountService)
                .freezeByPatient(5L);

        visitCardService.loss("C-0001");

        assertThat(cardRow.getStatus()).isEqualTo("LOST");
        verify(identifierService).publishChanged(5L, "VISIT_CARD", "C-0001", "LOST");
    }

    @Test
    @DisplayName("挂失时账户联动异常（非未启用）：异常透传，随事务回滚而非静默吞咽")
    void lossRethrowsUnexpectedAccountFailure() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);
        doThrow(new BizException(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "账户非冻结可达状态"))
                .when(cardAccountService)
                .freezeByPatient(5L);

        assertThatThrownBy(() -> visitCardService.loss("C-0001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("非 ACTIVE 卡挂失：PAT-1012 拒绝（409 仅正常状态可挂失），不触达账户联动")
    void lossRejectsNonActiveCard() {
        cardRow.setStatus("DISABLED");
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);

        assertThatThrownBy(() -> visitCardService.loss("C-0001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED));
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verifyNoInteractions(cardAccountService);
    }

    @Test
    @DisplayName("补卡成功：旧卡置 REPLACED 终态，新卡发号绑定同档案并发布 REPLACED 事件（余额零迁移）")
    void replaceRetiresLostCardAndIssuesNewOne() {
        PatientIdentifier lostCard = cardRow(11L, 5L, "C-0001", "LOST");
        when(identifierService.findByCardNo("C-0001")).thenReturn(lostCard);
        when(identifierService.attach(5L, "VISIT_CARD", "C-0002", "C-0002", false))
                .thenReturn(56L);
        when(identifierService.getById(56L)).thenReturn(cardRow(56L, 5L, "C-0002", "ACTIVE"));

        CardVO vo = visitCardService.replace(new CardReplaceRequest("C-0001", "C-0002"));

        assertThat(lostCard.getStatus()).isEqualTo("REPLACED");
        verify(identifierService).updateById(lostCard);
        verify(identifierService).publishChanged(5L, "VISIT_CARD", "C-0002", "REPLACED");
        assertThat(vo.identifierId()).isEqualTo(56L);
        assertThat(vo.cardNo()).isEqualTo("C-0002");
        assertThat(vo.patientId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("非 LOST 卡补卡：PAT-1012 拒绝（409 仅挂失卡可补），不发新卡号")
    void replaceRejectsNonLostCard() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);

        assertThatThrownBy(() -> visitCardService.replace(new CardReplaceRequest("C-0001", "C-0002")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED));
        verify(identifierService, never()).attach(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("补卡新卡号已存在：attach PAT-1002 原样透传（仅发卡入口做转义，事务整体回滚）")
    void replacePropagatesDuplicateNewCardNoAsPat1002() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow(11L, 5L, "C-0001", "LOST"));
        when(identifierService.attach(5L, "VISIT_CARD", "C-0002", "C-0002", false))
                .thenThrow(new BizException(
                        PatientErrorCode.IDENTIFIER_ALREADY_BOUND, HttpStatus.CONFLICT, "该标识已登记在其他患者档案"));

        assertThatThrownBy(() -> visitCardService.replace(new CardReplaceRequest("C-0001", "C-0002")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.IDENTIFIER_ALREADY_BOUND));
    }

    @Test
    @DisplayName("补卡成功留痕：恰一条 info 含患者 id 与新旧卡号双摘要，卡号明文禁入日志（BUG-21）")
    void replaceWritesSingleInfoLogWithBothCardDigests() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow(11L, 5L, "C-0001", "LOST"));
        when(identifierService.attach(5L, "VISIT_CARD", "C-0002", "C-0002", false))
                .thenReturn(56L);
        when(identifierService.getById(56L)).thenReturn(cardRow(56L, 5L, "C-0002", "ACTIVE"));
        // 双摘要桩值不含任一卡号明文子串，保证"明文不入日志"断言可分辨
        when(crypto.hash("C-0001")).thenReturn("hmac-d-old");
        when(crypto.hash("C-0002")).thenReturn("hmac-d-new");

        visitCardService.replace(new CardReplaceRequest("C-0001", "C-0002"));

        // 旧卡终态 + 新卡发号同属一次补卡写事务：留痕一条 info 概括新旧双卡
        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).as("补卡写库恰一条 info 留痕").hasSize(1);
        String message = infoEvents.get(0).getFormattedMessage();
        assertThat(message).contains("patientId=5");
        assertThat(message).contains("hmac-d-old");
        assertThat(message).contains("hmac-d-new");
        // 敏感红线（类 javadoc）：新旧卡号明文均禁入日志，仅 HMAC 摘要定位
        assertThat(message).doesNotContain("C-0001");
        assertThat(message).doesNotContain("C-0002");
    }

    @Test
    @DisplayName("解绑成功：置 DISABLED 终态并落解绑时刻，发布 UNBOUND 事件，账户不销户")
    void unbindDisablesActiveCardWithoutClosingAccount() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);

        visitCardService.unbind("C-0001");

        assertThat(cardRow.getStatus()).isEqualTo("DISABLED");
        assertThat(cardRow.getUnboundAt()).isNotNull();
        verify(identifierService).updateById(cardRow);
        verify(identifierService).publishChanged(5L, "VISIT_CARD", "C-0001", "UNBOUND");
        verifyNoInteractions(cardAccountService);
    }

    @Test
    @DisplayName("非 ACTIVE 卡解绑：PAT-1012 拒绝（409 仅正常状态可解绑）")
    void unbindRejectsNonActiveCard() {
        cardRow.setStatus("LOST");
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);

        assertThatThrownBy(() -> visitCardService.unbind("C-0001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED));
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
    }

    @Test
    @DisplayName("并发双 bind 防覆写护航：CAS 0 行重读定性 PAT-1012 拒绝，不覆写赢家挂接不发事件（EX-25）")
    void bindLosesCasRaceRejectedWithoutOverwrite() {
        PatientIdentifier orphan = cardRow(11L, null, "C-0001", "DISABLED");
        when(identifierService.findByCardNo("C-0001")).thenReturn(orphan);
        // 模拟并发赢家交错：bind 读快照（无主过守卫）后、CAS 前另一事务已抢先绑定患者 9
        when(identifierService.casBindUnowned(11L, 5L)).thenAnswer(inv -> {
            orphan.setPatientId(9L);
            orphan.setStatus("ACTIVE");
            return 0;
        });

        assertThatThrownBy(() -> visitCardService.bind(new CardBindRequest("C-0001", 5L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("并发");
                });
        // 输家不得覆写赢家挂接：卡行不回写、BOUND 事件不发布（丢单链防线）
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verify(identifierService, never()).publishChanged(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("并发双 bind 防覆写护航（输家重读行已消失）：PAT-1011 404 定性（与入口缺卡语义一致）")
    void bindCasZeroWithVanishedRowClassifiedAsPat1011() {
        PatientIdentifier orphan = cardRow(11L, null, "C-0001", "DISABLED");
        // 首查命中守卫入口、CAS 败北后重读已无行（并发删除/不可达）
        when(identifierService.findByCardNo("C-0001")).thenReturn(orphan, (PatientIdentifier) null);
        when(identifierService.casBindUnowned(11L, 5L)).thenReturn(0);

        assertThatThrownBy(() -> visitCardService.bind(new CardBindRequest("C-0001", 5L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.CARD_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
    }

    @Test
    @DisplayName("并发挂失防覆写护航：CAS 0 行重读定性 PAT-1012 拒绝，不回写不联动冻结不发事件（EX-25）")
    void lossLosesCasRaceRejectedWithoutAccountFreeze() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);
        // 模拟并发赢家交错：loss 读快照（ACTIVE 过守卫）后、CAS 前另一事务已抢先置 LOST
        when(identifierService.casMarkLost(11L)).thenAnswer(inv -> {
            cardRow.setStatus("LOST");
            return 0;
        });

        assertThatThrownBy(() -> visitCardService.loss("C-0001")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 输家不得重复执行：卡行不回写、账户不联动冻结、LOST 事件不发布
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verify(identifierService, never()).publishChanged(anyLong(), any(), any(), any());
        verifyNoInteractions(cardAccountService);
    }

    @Test
    @DisplayName("并发双补卡防重复发号护航：CAS 0 行重读定性 PAT-1012 拒绝，旧卡不回写不发新卡（EX-25）")
    void replaceLosesCasRaceRejectedWithoutIssuingNewCard() {
        PatientIdentifier lostCard = cardRow(11L, 5L, "C-0001", "LOST");
        when(identifierService.findByCardNo("C-0001")).thenReturn(lostCard);
        // 模拟并发赢家交错：replace 读快照（LOST 过守卫）后、CAS 前另一事务已抢先置 REPLACED
        when(identifierService.casRetireReplaced(11L)).thenAnswer(inv -> {
            lostCard.setStatus("REPLACED");
            return 0;
        });

        assertThatThrownBy(() -> visitCardService.replace(new CardReplaceRequest("C-0001", "C-0002")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 输家不得重复发号：旧卡不回写、新卡不 attach、REPLACED 事件不发布
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verify(identifierService, never()).attach(anyLong(), any(), any(), any(), anyBoolean());
        verify(identifierService, never()).publishChanged(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("并发解绑防覆写护航：CAS 0 行重读定性 PAT-1012 拒绝，不回写不发事件（EX-25）")
    void unbindLosesCasRaceRejectedWithoutOverwrite() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);
        // 模拟并发赢家交错：unbind 读快照（ACTIVE 过守卫）后、CAS 前另一事务已抢先置 DISABLED
        when(identifierService.casDisable(11L)).thenAnswer(inv -> {
            cardRow.setStatus("DISABLED");
            return 0;
        });

        assertThatThrownBy(() -> visitCardService.unbind("C-0001")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verify(identifierService, never()).publishChanged(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("按卡号查询：命中任意状态行出参（标识值禁出，仅卡面号）")
    void getByCardNoReturnsCardView() {
        when(identifierService.findByCardNo("C-0001")).thenReturn(cardRow);

        CardVO vo = visitCardService.getByCardNo("C-0001");

        assertThat(vo.identifierId()).isEqualTo(11L);
        assertThat(vo.cardNo()).isEqualTo("C-0001");
        assertThat(vo.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("按卡号查询无命中：PAT-1011（404 就诊卡不存在）")
    void getByCardNoWithoutHitFailsAsPat1011() {
        when(identifierService.findByCardNo("C-XXXX")).thenReturn(null);

        assertThatThrownBy(() -> visitCardService.getByCardNo("C-XXXX"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.CARD_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("挂失/解绑按卡号查非 ACTIVE 行可达（LOST 行经 findByCardNo 命中而非解析路径）")
    void lossReachableForLostRowViaFindByCardNo() {
        PatientIdentifier lostRow = cardRow(11L, 5L, "C-0001", "LOST");
        when(identifierService.findByCardNo("C-0001")).thenReturn(lostRow);

        assertThatThrownBy(() -> visitCardService.loss("C-0001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_STATE_NOT_ALLOWED));
        // 守卫用任意状态查询入口而非 ACTIVE 解析（resolveActive 仅解析服务用）
        verify(identifierService, never()).resolveActive(eq("VISIT_CARD"), eq("C-0001"));
    }
}
