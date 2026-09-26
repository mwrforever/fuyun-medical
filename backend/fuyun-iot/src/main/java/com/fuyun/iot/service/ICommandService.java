package com.fuyun.iot.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.CommandQueryRequest;
import com.fuyun.iot.dto.ConfirmChallengeRequest;
import com.fuyun.iot.dto.IssueCommandRequest;
import com.fuyun.iot.vo.CommandLogVO;
import com.fuyun.iot.vo.ConfirmChallengeVO;

/**
 * 命令下发服务契约（FU-M14-09 命令域出网面，管理台唯一业务出口）：二次确认凭证签发、五步
 * 下发、命令日志分页与按号查询。五步编排（门槛/凭证消费/预检/下发/结果回推）实装于
 * internal/CommandDispatcher，本契约为 HTTP 面门面（操作者上下文与 traceId 采集归实现）。
 */
public interface ICommandService {

    /**
     * 签发二次确认凭证：门槛校验（设备/病区/白名单/治疗级/在线）+ 预占命令号 + 凭证落 Redis
     * （TTL 120s，一次性消费）。
     *
     * @param request 签发请求（设备/命令/参数绑定），非空
     * @return 凭证视图（challengeId/commandNo/expiresIn），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404）/ IOT-1014（409 门槛拒绝）
     */
    ConfirmChallengeVO issueChallenge(ConfirmChallengeRequest request);

    /**
     * 命令下发（五步编排）：凭证一次性消费 + 门槛复检 + 在线预检选道 + 下发 + 终态迁移与
     * iot.command.completed 事件发布。
     *
     * @param request 下发请求（必携凭证），非空
     * @return 命令日志视图（终态 SUCCESS/FAILED/TIMEOUT 或异步 ISSUED），非空
     * @throws com.fuyun.common.exception.BizException IOT-1015（400）/ IOT-1014（409）/
     *                                                 IOT-1022（503 受理失败）
     */
    CommandLogVO dispatch(IssueCommandRequest request);

    /**
     * 命令日志分页（设备/状态过滤，issued_at 降序，page 0 基）。
     *
     * @param request 分页查询请求，非空
     * @return 分页出参，非空
     */
    PageResult<CommandLogVO> page(CommandQueryRequest request);

    /**
     * 按命令号查询命令日志。
     *
     * @param commandNo 命令业务号，非空
     * @return 命令日志视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1016（404 命令不存在）
     */
    CommandLogVO getByCommandNo(String commandNo);
}
