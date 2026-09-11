package com.fuyun.iot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fuyun.iot.entity.IotConsumeErrorLogEntity;
import com.fuyun.iot.enums.ConsumeErrorStage;
import com.fuyun.iot.enums.ConsumeErrorStatus;
import com.fuyun.iot.mapper.IotConsumeErrorLogMapper;
import com.fuyun.iot.service.impl.ConsumeErrorLogServiceImpl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 消费错误日志服务单元测试（BRIEF-PR4-01 §3 单测清单：摘要/截断/PENDING、落库失败吞并告警）。
 * JaCoCo 核心包 com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>毒丸隔离优先于留痕是本服务核心契约：落库失败必须全吞不向消费循环上抛（否则毒丸帧无法
 * 被确认抛弃，IoTDA 重推无限循环）；测试载荷为无意义合成文本，与任何真实报文无关。
 */
@ExtendWith(MockitoExtension.class)
class ConsumeErrorLogServiceImplTest {

    private static final String QUEUE_NAME = "it.iot.telemetry";

    @Mock
    private IotConsumeErrorLogMapper consumeErrorLogMapper;

    @Captor
    private ArgumentCaptor<IotConsumeErrorLogEntity> entityCaptor;

    private ConsumeErrorLogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConsumeErrorLogServiceImpl(consumeErrorLogMapper);
    }

    @Test
    @DisplayName("留痕落 PENDING 行：SHA-256 摘要 64 位十六进制且与原文一致、stage 枚举化、replay_count=0")
    void recordParseFailureWritesPendingRowWithDigest() {
        String rawText = "{\"deviceId\":\"dev-01\",\"broken";

        service.recordParseFailure(QUEUE_NAME, rawText, "PARSE", "帧不是合法 JSON 报文");

        verify(consumeErrorLogMapper).insert(entityCaptor.capture());
        IotConsumeErrorLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getQueueName()).isEqualTo(QUEUE_NAME);
        assertThat(entity.getRawDigest()).isHexadecimal().hasSize(64).isEqualTo(expectedSha256Hex(rawText));
        // 短载荷原样留痕（未触发截断）
        assertThat(entity.getRawPayload()).isEqualTo(rawText);
        assertThat(entity.getErrorStage()).isEqualTo(ConsumeErrorStage.PARSE);
        assertThat(entity.getStatus()).isEqualTo(ConsumeErrorStatus.PENDING);
        assertThat(entity.getReplayCount()).isZero();
    }

    @Test
    @DisplayName("载荷超 4000 字符截断、errorMsg 超 500 字符截断（列宽防线），摘要仍取全文")
    void recordParseFailureTruncatesPayloadAndMessageToColumnLimits() {
        String oversizePayload = "x".repeat(5000);
        String oversizeMsg = "y".repeat(600);

        service.recordParseFailure(QUEUE_NAME, oversizePayload, "VALIDATE", oversizeMsg);

        verify(consumeErrorLogMapper).insert(entityCaptor.capture());
        IotConsumeErrorLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getRawPayload()).hasSize(4000);
        assertThat(entity.getErrorMsg()).hasSize(500);
        // 摘要恒对全文计算（与截断无关，篡改检测口径）
        assertThat(entity.getRawDigest()).isEqualTo(expectedSha256Hex(oversizePayload));
    }

    @Test
    @DisplayName("落库失败：异常全吞不向消费循环上抛（毒丸隔离优先于留痕契约）")
    void recordParseFailureSwallowsPersistenceFailure() {
        doThrow(new RuntimeException("db connection lost"))
                .when(consumeErrorLogMapper)
                // MP 3.5.17 BaseMapper 存在 insert(T)/insert(Collection) 重载，any() 须显式定型消歧
                .insert(any(IotConsumeErrorLogEntity.class));

        assertThatCode(() -> service.recordParseFailure(QUEUE_NAME, "payload", "PARSE", "非 JSON"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("errorMsg 为 null（可空列语义）：留痕行错误原因保持 null")
    void recordParseFailureKeepsNullErrorMessage() {
        service.recordParseFailure(QUEUE_NAME, "payload", "PARSE", null);

        verify(consumeErrorLogMapper).insert(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getErrorMsg()).isNull();
    }

    @Test
    @DisplayName("SHA-256 算法不可用（JDK 环境缺陷注入）：包装为 IllegalStateException 显性暴露")
    void unavailableDigestAlgorithmIsWrappedAsIllegalState() {
        try (MockedStatic<MessageDigest> mockedDigest = Mockito.mockStatic(MessageDigest.class)) {
            // 环境缺陷注入：理论不可达分支（JDK 内置算法缺失）须显性暴露而非静默吞
            mockedDigest
                    .when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("injected"));

            assertThatThrownBy(() -> service.recordParseFailure(QUEUE_NAME, "payload", "PARSE", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SHA-256");
        }
    }

    /** 测试侧独立计算 SHA-256 期望值（与服务实现同算法不同代码路径，防实现自证） */
    private static String expectedSha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
