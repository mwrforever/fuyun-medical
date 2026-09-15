package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 死信管理服务单测：分页契约转换、条件过滤、详情不存在语义（mapper 以 Mockito 模拟，
 * MP 表信息缓存手工装载——容器外单测的既有范式，见 EventRegistryServiceImplTest）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeadLetterServiceImplTest {

    @Mock
    private DeadLetterMapper deadLetterMapper;

    private DeadLetterServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件解析列名依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeadLetter.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeadLetterServiceImpl(IntegrationConverter.INSTANCE);
        // ServiceImpl 的 baseMapper 为 protected 字段，单测经反射注入 mock（既有范式）
        ReflectionTestUtils.setField(service, "baseMapper", deadLetterMapper);
        ReflectionTestUtils.setField(service, "entityClass", DeadLetter.class);
    }

    @Test
    @DisplayName("分页查询：0 基请求转为 MP 1 基后的出参页码仍为 0 基，出参含预览与摘要")
    void queryKeepsZeroBasedPageContract() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<DeadLetter> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1L);
            return page;
        });

        PageResult<DeadLetterVO> result =
                service.query(new DeadLetterQuery("PENDING", "system.dict.published", null, null, 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20L);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).payloadPreview()).isEqualTo("{\"eventId\":\"x\"}");
        // MP 分页器以 1 基接收（契约 0 基 → 内部 1 基转换）
        verify(deadLetterMapper).selectPage(any(IPage.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("详情查询：命中返回全文载荷；未命中抛 404 业务异常（INT-1001）")
    void detailReturnsFullPayloadOrThrowsNotFound() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectById(9L)).thenReturn(row);
        DeadLetterDetailVO detail = service.detail(9L);
        assertThat(detail.payloadBody()).isEqualTo("{\"eventId\":\"x\"}");
        assertThat(detail.status()).isEqualTo("PENDING");

        when(deadLetterMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(404L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    /**
     * 构造 PENDING 死信样本行。
     *
     * @param id          死信 ID
     * @param replayCount 重放计数
     * @return 死信实体
     */
    private DeadLetter pendingRow(Long id, int replayCount) {
        DeadLetter row = new DeadLetter();
        row.setId(id);
        row.setSourceQueue("q.it.system.dict.published");
        row.setRoutingKey("system.dict.published");
        row.setEventType("system.dict.published");
        row.setEventId("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        row.setPayloadBody("{\"eventId\":\"x\"}");
        row.setPayloadDigest("d".repeat(64));
        row.setFailReason("消费死信：reason=rejected");
        row.setFirstDeadAt(OffsetDateTime.parse("2026-09-15T01:02:03Z"));
        row.setStatus("PENDING");
        row.setReplayCount(replayCount);
        return row;
    }
}
