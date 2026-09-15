package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.entity.MdmSubscription;
import com.fuyun.integration.mapper.MdmSubscriptionMapper;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 主数据订阅服务单测：登记幂等（不覆盖既有对账进度）、未知主题拒绝、并发冲突回读、
 * 注销不存在语义、初值 PENDING 与分发目标清单读取。
 */
@ExtendWith(MockitoExtension.class)
class MdmSubscriptionServiceImplTest {

    @Mock
    private MdmSubscriptionMapper mdmSubscriptionMapper;

    private MdmSubscriptionServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MdmSubscription.class);
    }

    @BeforeEach
    void setUp() {
        service = new MdmSubscriptionServiceImpl(IntegrationConverter.INSTANCE);
        ReflectionTestUtils.setField(service, "baseMapper", mdmSubscriptionMapper);
        ReflectionTestUtils.setField(service, "entityClass", MdmSubscription.class);
    }

    @Test
    @DisplayName("登记新订阅：落行含 PENDING 对账初值与同步方式，出参回填登记值")
    void registerInsertsRowWithPendingReconStatus() {
        when(mdmSubscriptionMapper.selectOne(any())).thenReturn(null);
        when(mdmSubscriptionMapper.insert(any(MdmSubscription.class))).thenReturn(1);

        MdmSubscriptionVO vo = service.register(new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_DICT, "patient", MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));

        ArgumentCaptor<MdmSubscription> captor = ArgumentCaptor.forClass(MdmSubscription.class);
        verify(mdmSubscriptionMapper).insert(captor.capture());
        assertThat(captor.getValue().getReconStatus()).isEqualTo(MdmConstants.RECON_STATUS_PENDING);
        assertThat(captor.getValue().getTopic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(vo.subscriberModule()).isEqualTo("patient");
        assertThat(vo.reconStatus()).isEqualTo(MdmConstants.RECON_STATUS_PENDING);
    }

    @Test
    @DisplayName("重复登记：幂等跳过且不插入（不覆盖既有对账进度）")
    void registerSkipsExistingSubscription() {
        MdmSubscription existing = new MdmSubscription();
        existing.setId(1L);
        existing.setTopic(MdmConstants.TOPIC_DICT);
        existing.setSubscriberModule("patient");
        existing.setSyncMode(MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE);
        existing.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionMapper.selectOne(any())).thenReturn(existing);

        MdmSubscriptionVO vo = service.register(new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_DICT, "patient", MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));

        assertThat(vo.id()).isEqualTo(1L);
        verify(mdmSubscriptionMapper, never()).insert(any(MdmSubscription.class));
    }

    @Test
    @DisplayName("未知主题：抛 400 业务异常（INT-1012），不触达落库")
    void registerRejectsUnknownTopic() {
        assertThatThrownBy(() -> service.register(
                        new MdmSubscriptionCreateRequest("unknown-topic", "patient", MdmConstants.SYNC_MODE_API_PULL)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.MDM_TOPIC_UNKNOWN);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(mdmSubscriptionMapper, never()).insert(any(MdmSubscription.class));
    }

    @Test
    @DisplayName("并发首登记：唯一索引冲突后回读既有行返回（幂等语义）")
    void registerFallsBackToExistingRowOnConcurrentInsert() {
        MdmSubscription existing = new MdmSubscription();
        existing.setId(2L);
        existing.setTopic(MdmConstants.TOPIC_ORG);
        existing.setSubscriberModule("nursing");
        existing.setSyncMode(MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE);
        existing.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionMapper.selectOne(any())).thenReturn(null, existing);
        when(mdmSubscriptionMapper.insert(any(MdmSubscription.class)))
                .thenThrow(new DuplicateKeyException("uk_mdm_subscription_topic_subscriber"));

        MdmSubscriptionVO vo = service.register(new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_ORG, "nursing", MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));

        assertThat(vo.id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("注销不存在：抛 404 业务异常（INT-1011）")
    void unregisterRejectsMissingRow() {
        when(mdmSubscriptionMapper.deleteById(9L)).thenReturn(0);

        assertThatThrownBy(() -> service.unregister(9L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.MDM_SUBSCRIPTION_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    @Test
    @DisplayName("注销存在行：逻辑删命中即完成注销（不抛异常）")
    void unregisterRemovesExistingRow() {
        when(mdmSubscriptionMapper.deleteById(3L)).thenReturn(1);

        service.unregister(3L);

        verify(mdmSubscriptionMapper).deleteById(3L);
    }

    @Test
    @DisplayName("订阅方清单读取：按主题返回模块标识清单（分发流水 target_modules 数据源）")
    void listSubscriberModulesReturnsTargets() {
        MdmSubscription first = new MdmSubscription();
        first.setSubscriberModule("lab");
        MdmSubscription second = new MdmSubscription();
        second.setSubscriberModule("pharmacy");
        when(mdmSubscriptionMapper.selectList(any())).thenReturn(List.of(first, second));

        assertThat(service.listSubscriberModules(MdmConstants.TOPIC_DICT)).containsExactly("lab", "pharmacy");
    }

    @Test
    @DisplayName("矩阵查询：0 基分页契约（请求与出参同口径）且矩阵行字段完整映射")
    void queryKeepsZeroBasedContractAndMapsMatrixRow() {
        MdmSubscription row = new MdmSubscription();
        row.setId(5L);
        row.setTopic(MdmConstants.TOPIC_DICT);
        row.setSubscriberModule("patient");
        row.setSyncMode(MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE);
        row.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenAnswer(invocation -> {
                    IPage<MdmSubscription> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(1L);
                    return page;
                });

        PageResult<MdmSubscriptionVO> result =
                service.query(new MdmSubscriptionQuery(MdmConstants.TOPIC_DICT, "patient", 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20L);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.content()).hasSize(1);
        MdmSubscriptionVO vo = result.content().get(0);
        assertThat(vo.topic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(vo.subscriberModule()).isEqualTo("patient");
        assertThat(vo.reconStatus()).isEqualTo(MdmConstants.RECON_STATUS_PENDING);
        ArgumentCaptor<IPage<MdmSubscription>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
        verify(mdmSubscriptionMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        // MP 分页器以 1 基接收（契约 0 基 → 内部 1 基转换，服务层唯一转换点）
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
    }
}
