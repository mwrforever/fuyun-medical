package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.dto.CommandQueryRequest;
import com.fuyun.iot.dto.ConfirmChallengeRequest;
import com.fuyun.iot.dto.IssueCommandRequest;
import com.fuyun.iot.entity.IotCommandLogEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotProductCommandEntity;
import com.fuyun.iot.enums.CommandDeliverMode;
import com.fuyun.iot.enums.CommandSafetyLevel;
import com.fuyun.iot.enums.CommandStatus;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.CommandDispatcher;
import com.fuyun.iot.mapper.IotCommandLogMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotProductCommandMapper;
import com.fuyun.iot.properties.CommandProperties;
import com.fuyun.iot.vo.CommandLogVO;
import com.fuyun.iot.vo.ConfirmChallengeVO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 命令服务单测（P2 PR-2 Task 8 Step 2，五步门槛的凭证签发侧 + 查询面）：白名单默认拒（行缺失/
 * allowed=false）、治疗级未豁免拒与豁免放行、设备未编病区/离线/快照未知拒签、签发载荷预占命令号
 * 与 120s TTL、下发委托操作者与链路追踪号透传、分页过滤与 0 基换算、按号查询与 IOT-1016。
 *
 * <p>门槛用例经真实 {@link CommandDispatcher}（基础设施 mock）驱动——门槛逻辑单点实装于编排器，
 * 服务为薄门面；凭证消费侧（GETDEL 一次性/同步超时/结果回推终态+事件）归
 * {@link com.fuyun.iot.internal.CommandDispatcherTest}。真实 SQL 归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandServiceImplTest {

    private static final String DEVICE_ID = "dev-001";

    private static final String COMMAND_NAME = "setWorkMode";

    private static final String SNAPSHOT_KEY = "fy:iot:snapshot:device-status:" + DEVICE_ID;

    private static final String COMMAND_NO = "CMD2026092600001";

    private static final String OPERATOR = "nurse-01";

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IotProductCommandMapper commandMapper;

    @Mock
    private IotCommandLogMapper commandLogMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private IotSeqGate seqGate;

    @Mock
    private com.fuyun.iot.registry.IotDeviceRegistry registry;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Captor
    private ArgumentCaptor<String> challengeValueCaptor;

    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<IotCommandLogEntity>> queryCaptor;

    private CommandServiceImpl service;

    private CommandDispatcher dispatcher;

    private ObjectMapper objectMapper;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次，覆盖门槛链三实体）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotCommandLogEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotDeviceEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotProductCommandEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(seqGate.nextCommandNo()).thenReturn(COMMAND_NO);
        // 操作人上下文注入（下发审计留痕口径）
        com.fuyun.common.context.OperatorContextHolder.set(OPERATOR);
        objectMapper = new ObjectMapper();
        dispatcher = new CommandDispatcher(
                deviceMapper,
                commandMapper,
                commandLogMapper,
                redisTemplate,
                registry,
                seqGate,
                events,
                new TransactionTemplate(transactionManager),
                objectMapper,
                new CommandProperties(Duration.ofMillis(80), false));
        service = new CommandServiceImpl(dispatcher, commandLogMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        // 请求结束清理操作人上下文（ThreadLocal 防线程复用泄漏）
        com.fuyun.common.context.OperatorContextHolder.clear();
    }

    // ---------------------------------------------------------------- 凭证签发门槛（步骤①②③签发侧）

    @Test
    @DisplayName("凭证签发成功：预占命令号入载荷，TTL 120s，出网含 challengeId/expiresIn")
    void issueChallengeSignsWithPreIssuedCommandNoAndTtl() {
        stubGates(CommandSafetyLevel.SAFETY, true);
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn("{\"deviceId\":\"dev-001\",\"status\":\"ONLINE\"}");

        ConfirmChallengeVO vo =
                service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)));

        assertThat(vo.challengeId()).isNotBlank();
        assertThat(vo.commandNo()).isEqualTo(COMMAND_NO);
        assertThat(vo.expiresIn()).isEqualTo(120L);
        verify(valueOperations)
                .set(
                        eq("fy:iot:cmd:challenge:" + vo.challengeId()),
                        challengeValueCaptor.capture(),
                        eq(Duration.ofSeconds(120)));
        String payload = challengeValueCaptor.getValue();
        assertThat(payload).contains(COMMAND_NO).contains(DEVICE_ID).contains(COMMAND_NAME);
    }

    @Test
    @DisplayName("凭证签发：设备不存在拒绝（IOT-1006，404）")
    void issueChallengeRejectsUnknownDevice() {
        when(deviceMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("凭证签发：设备未编病区拒绝（数据范围=病区归属，IOT-1014）")
    void issueChallengeRejectsDeviceWithoutWard() {
        IotDeviceEntity device = deviceRow();
        device.setWardId(null);
        when(deviceMapper.selectOne(any())).thenReturn(device);

        assertThatThrownBy(() -> service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
    }

    @Test
    @DisplayName("白名单默认拒：命令未登记白名单（IOT-1014，409）")
    void issueChallengeRejectsCommandMissingFromWhitelist() {
        when(deviceMapper.selectOne(any())).thenReturn(deviceRow());
        when(commandMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
    }

    @Test
    @DisplayName("白名单默认拒：allowed=false 拒签（IOT-1014）")
    void issueChallengeRejectsDisallowedCommand() {
        stubGates(CommandSafetyLevel.SAFETY, false);

        assertThatThrownBy(() -> service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
    }

    @Test
    @DisplayName("治疗级未豁免拒：TREATMENT 且豁免开关关闭（IOT-1014）")
    void issueChallengeRejectsTreatmentCommandWithoutExemption() {
        stubGates(CommandSafetyLevel.TREATMENT, true);

        assertThatThrownBy(() -> service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
    }

    @Test
    @DisplayName("治疗级豁免放行：豁免开关显式开启方签发凭证")
    void issueChallengeAllowsTreatmentCommandWhenExemptionEnabled() {
        dispatcher = new CommandDispatcher(
                deviceMapper,
                commandMapper,
                commandLogMapper,
                redisTemplate,
                registry,
                seqGate,
                events,
                new TransactionTemplate(transactionManager),
                objectMapper,
                new CommandProperties(Duration.ofMillis(80), true));
        service = new CommandServiceImpl(dispatcher, commandLogMapper, objectMapper);
        stubGates(CommandSafetyLevel.TREATMENT, true);
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn("{\"deviceId\":\"dev-001\",\"status\":\"ONLINE\"}");

        ConfirmChallengeVO vo = service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null));

        assertThat(vo.commandNo()).isEqualTo(COMMAND_NO);
    }

    @Test
    @DisplayName("离线拒签：快照 status=OFFLINE 不签发凭证（IOT-1014）")
    void issueChallengeRejectsOfflineDevice() {
        stubGates(CommandSafetyLevel.SAFETY, true);
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn("{\"deviceId\":\"dev-001\",\"status\":\"OFFLINE\"}");

        assertThatThrownBy(() -> service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
    }

    @Test
    @DisplayName("快照未知拒签：设备状态快照缺失（TTL 过期/设备未上线）不签发（IOT-1014）")
    void issueChallengeRejectsUnknownOnlineState() {
        stubGates(CommandSafetyLevel.SAFETY, true);
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
    }

    // ---------------------------------------------------------------- 下发委托与查询面

    @Test
    @DisplayName("下发委托：操作者取上下文、traceId 取 MDC 后透传编排器")
    void dispatchDelegatesWithOperatorAndTraceId() {
        CommandDispatcher delegated = mock(CommandDispatcher.class);
        CommandLogVO expected = commandVo(CommandStatus.SUCCESS);
        when(delegated.dispatch(any(), eq(OPERATOR), eq(null))).thenReturn(expected);
        CommandServiceImpl facade = new CommandServiceImpl(delegated, commandLogMapper, objectMapper);
        IssueCommandRequest request = new IssueCommandRequest("chg-1", DEVICE_ID, COMMAND_NAME, null);

        CommandLogVO vo = facade.dispatch(request);

        assertThat(vo).isSameAs(expected);
        verify(delegated).dispatch(eq(request), eq(OPERATOR), eq(null));
    }

    @Test
    @DisplayName("分页：设备+状态过滤透传、0 基页码换算、issued_at 降序")
    void pageAppliesDeviceAndStatusFilters() {
        when(commandLogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<IotCommandLogEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(commandEntity(CommandStatus.SUCCESS)));
            result.setTotal(1);
            return result;
        });

        PageResult<CommandLogVO> result =
                service.page(new CommandQueryRequest(1, 50, DEVICE_ID, CommandStatus.SUCCESS));

        verify(commandLogMapper).selectPage(any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment()).contains("device_id").contains("status");
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content().get(0).commandNo()).isEqualTo(COMMAND_NO);
    }

    @Test
    @DisplayName("按号查询：命令不存在拒绝（IOT-1016，404）")
    void getRejectsMissingCommand() {
        when(commandLogMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getByCommandNo(COMMAND_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_FOUND));
    }

    @Test
    @DisplayName("按号查询：命中行出网（参数 JSON 反序列化为键值对）")
    void getReturnsCommandWithParsedParams() {
        when(commandLogMapper.selectOne(any())).thenReturn(commandEntity(CommandStatus.SUCCESS));

        CommandLogVO vo = service.getByCommandNo(COMMAND_NO);

        assertThat(vo.commandNo()).isEqualTo(COMMAND_NO);
        assertThat(vo.deliverMode()).isEqualTo(CommandDeliverMode.SYNC);
        assertThat(vo.params()).containsEntry("mode", 3);
    }

    // ---------------------------------------------------------------- 夹具与桩件

    private void stubGates(CommandSafetyLevel safetyLevel, boolean allowed) {
        when(deviceMapper.selectOne(any())).thenReturn(deviceRow());
        when(commandMapper.selectOne(any())).thenReturn(whitelistRow(safetyLevel, allowed));
    }

    private static IotDeviceEntity deviceRow() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setProductId("prod-001");
        device.setWardId(1001L);
        device.setStatus(DeviceStatus.ONLINE);
        return device;
    }

    private static IotProductCommandEntity whitelistRow(CommandSafetyLevel safetyLevel, boolean allowed) {
        IotProductCommandEntity row = new IotProductCommandEntity();
        row.setProductId("prod-001");
        row.setCommandName(COMMAND_NAME);
        row.setSafetyLevel(safetyLevel);
        row.setAllowed(allowed);
        return row;
    }

    private static IotCommandLogEntity commandEntity(CommandStatus status) {
        IotCommandLogEntity entity = new IotCommandLogEntity();
        entity.setId(1L);
        entity.setCommandNo(COMMAND_NO);
        entity.setDeviceId(DEVICE_ID);
        entity.setCommandName(COMMAND_NAME);
        entity.setCommandParams("{\"mode\":3}");
        entity.setSafetyLevel(CommandSafetyLevel.SAFETY);
        entity.setOperator(OPERATOR);
        entity.setDeliverMode(CommandDeliverMode.SYNC);
        entity.setStatus(status);
        return entity;
    }

    private static CommandLogVO commandVo(CommandStatus status) {
        return new CommandLogVO(
                1L,
                COMMAND_NO,
                DEVICE_ID,
                COMMAND_NAME,
                null,
                CommandSafetyLevel.SAFETY,
                OPERATOR,
                CommandDeliverMode.SYNC,
                status,
                null,
                null,
                null,
                null,
                null);
    }
}
