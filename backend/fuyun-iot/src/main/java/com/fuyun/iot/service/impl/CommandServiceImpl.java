package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.CommandQueryRequest;
import com.fuyun.iot.dto.ConfirmChallengeRequest;
import com.fuyun.iot.dto.IssueCommandRequest;
import com.fuyun.iot.entity.IotCommandLogEntity;
import com.fuyun.iot.internal.CommandDispatcher;
import com.fuyun.iot.mapper.IotCommandLogMapper;
import com.fuyun.iot.service.ICommandService;
import com.fuyun.iot.vo.CommandLogVO;
import com.fuyun.iot.vo.ConfirmChallengeVO;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 命令服务实现（FU-M14-09 命令域 HTTP 门面）：凭证签发与下发编排委托
 * internal/CommandDispatcher（五步实装单点），操作者上下文与 MDC traceId 在本层采集透传；
 * 命令日志分页与按号查询直连 mapper（AlarmServiceImpl 同款形态）。
 *
 * <p>错误码归属：命令不存在 IOT-1016（本域专属码位）；其余借承由编排器按五步语义抛出。
 * 装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class CommandServiceImpl implements ICommandService {

    private final CommandDispatcher dispatcher;

    private final IotCommandLogMapper commandLogMapper;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param dispatcher       命令下发编排器，非空；五步实装单点
     * @param commandLogMapper 命令日志 mapper，非空；查询通道
     * @param objectMapper     JSON 转换器，非空；出网视图参数反序列化
     */
    public CommandServiceImpl(
            CommandDispatcher dispatcher, IotCommandLogMapper commandLogMapper, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.commandLogMapper = commandLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConfirmChallengeVO issueChallenge(ConfirmChallengeRequest request) {
        return dispatcher.issueChallenge(request);
    }

    @Override
    public CommandLogVO dispatch(IssueCommandRequest request) {
        // 操作者与 traceId 在门面采集（编排器不依赖 Web 上下文，AMQP 消费侧复用零耦合）
        CommandLogVO vo = dispatcher.dispatch(request, operator(), MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY));
        log.info(
                "命令下发完成：commandNo={}，deviceId={}，deliverMode={}，status={}，operator={}",
                vo.commandNo(),
                vo.deviceId(),
                vo.deliverMode(),
                vo.status(),
                vo.operator());
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<CommandLogVO> page(CommandQueryRequest request) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：过滤分页（MP 分页 1 基 current 换算 0 基请求；idx_iot_command_device_status
        // 准入，@TableLogic 自动携带 deleted=0），issued_at 降序稳定输出
        Page<IotCommandLogEntity> result = commandLogMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<IotCommandLogEntity>lambdaQuery()
                        .eq(request.deviceId() != null, IotCommandLogEntity::getDeviceId, request.deviceId())
                        .eq(request.status() != null, IotCommandLogEntity::getStatus, request.status())
                        .orderByDesc(IotCommandLogEntity::getIssuedAt));
        return PageResult.of(
                result.getRecords().stream()
                        .map(row -> CommandLogVO.from(row, objectMapper))
                        .toList(),
                page,
                size,
                result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public CommandLogVO getByCommandNo(String commandNo) {
        return CommandLogVO.from(requireCommand(commandNo), objectMapper);
    }

    /**
     * 命令行存在性校验（IOT-1016 专属码位）。
     *
     * @param commandNo 命令业务号，非空
     * @return 命令实体，非空
     */
    private IotCommandLogEntity requireCommand(String commandNo) {
        // 数据库读操作：自然键 command_no 单查（@TableLogic 自动携带 deleted=0）
        IotCommandLogEntity entity = commandLogMapper.selectOne(
                Wrappers.<IotCommandLogEntity>lambdaQuery().eq(IotCommandLogEntity::getCommandNo, commandNo));
        if (entity == null) {
            throw new BizException(IotErrorCode.COMMAND_NOT_FOUND, HttpStatus.NOT_FOUND, "命令不存在：" + commandNo);
        }
        return entity;
    }

    /** 操作者取值（写路径审计留痕；无登录上下文回退 system，与审计列默认同源）。 */
    private static String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? "system" : operator;
    }
}
