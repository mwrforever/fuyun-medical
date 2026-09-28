package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.CommandQueryRequest;
import com.fuyun.iot.dto.ConfirmChallengeRequest;
import com.fuyun.iot.dto.IssueCommandRequest;
import com.fuyun.iot.service.ICommandService;
import com.fuyun.iot.vo.CommandLogVO;
import com.fuyun.iot.vo.ConfirmChallengeVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命令端点（/api/v1/iot/commands 四端点，FU-M14-09 命令下发面）：二次确认凭证签发、下发
 * （必携凭证）、命令日志分页（设备/状态过滤）与按号查询。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用命令服务 + 编排响应；五步编排/状态机
 * CAS/事件发布归服务层与编排器。凭证签发与下发挂 WRITE 审计（M01 审计切面 detail 自动携带
 * deviceId/命令/参数操作摘要；治疗级豁免使用以 warn 日志与 iot_command_log 行双留痕，升级
 * 审批面缺位注记 GC17 归 Task 18）。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/commands")
public class CommandController {

    /** 命令服务：四端点唯一业务出口 */
    private final ICommandService commandService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param commandService 命令服务，非空
     */
    public CommandController(ICommandService commandService) {
        this.commandService = commandService;
    }

    /**
     * 二次确认凭证签发（POST /api/v1/iot/commands/confirm-challenge；WRITE 审计）。
     *
     * @param request 签发请求体（@Valid），非空
     * @return 凭证视图（challengeId/commandNo/expiresIn 120s）；200
     * @throws com.fuyun.common.exception.BizException IOT-1006（404）/ IOT-1014（409 门槛拒绝）
     */
    @PostMapping("/confirm-challenge")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ConfirmChallengeVO confirmChallenge(@Valid @RequestBody ConfirmChallengeRequest request) {
        return commandService.issueChallenge(request);
    }

    /**
     * 命令下发（POST /api/v1/iot/commands；WRITE 审计；必携二次确认凭证）。
     *
     * @param request 下发请求体（@Valid），非空
     * @return 命令日志视图（终态或异步 ISSUED）；200
     * @throws com.fuyun.common.exception.BizException IOT-1015（400）/ IOT-1014（409）/
     *                                                 IOT-1022（503）
     */
    @PostMapping
    @AuditLog(actionType = AuditActionType.WRITE)
    public CommandLogVO dispatch(@Valid @RequestBody IssueCommandRequest request) {
        return commandService.dispatch(request);
    }

    /**
     * 命令日志分页（GET /api/v1/iot/commands；设备/状态过滤，page 0 基）。
     *
     * @param request 分页查询请求（查询参数绑定，@Valid），非空
     * @return 分页出参；200
     */
    @GetMapping
    public PageResult<CommandLogVO> page(@Valid CommandQueryRequest request) {
        return commandService.page(request);
    }

    /**
     * 按命令号查询（GET /api/v1/iot/commands/{commandNo}）。
     *
     * @param commandNo 命令业务号（路径变量）
     * @return 命令日志视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1016（404）
     */
    @GetMapping("/{commandNo}")
    public CommandLogVO getByCommandNo(@PathVariable String commandNo) {
        return commandService.getByCommandNo(commandNo);
    }
}
