package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.LinkageLogQueryRequest;
import com.fuyun.iot.dto.SaveLinkageRuleRequest;
import com.fuyun.iot.service.ILinkageRuleService;
import com.fuyun.iot.vo.LinkageLogVO;
import com.fuyun.iot.vo.LinkageRuleVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联动规则与联动日志端点（/api/v1/iot/linkage-rules 四端点 + /api/v1/iot/linkage-logs 两端点，
 * FU-M14-10 管理面）：规则清单（GET）、登记（POST，201）、更新（PUT）、软删（DELETE，204）；
 * 联动日志分页（GET）与 FAILED 行人工重推（POST /{no}/retry）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用联动规则服务 + 编排响应；触发条件词表
 * 校验（未知键/非对象/空白值拒保存）与重推状态机裁决（仅 FAILED/CAS 并发兜底）归服务层；
 * 写端点（登记/更新/软删/重推）挂 WRITE 审计。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot")
public class LinkageRuleController {

    /** 联动规则服务：六端点唯一业务出口 */
    private final ILinkageRuleService linkageRuleService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param linkageRuleService 联动规则服务，非空
     */
    public LinkageRuleController(ILinkageRuleService linkageRuleService) {
        this.linkageRuleService = linkageRuleService;
    }

    /**
     * 规则清单（GET /api/v1/iot/linkage-rules；纯读，id 升序稳定输出）。
     *
     * @return 规则视图清单；200（空表为空数组）
     */
    @GetMapping("/linkage-rules")
    public List<LinkageRuleVO> list() {
        return linkageRuleService.list();
    }

    /**
     * 规则登记（POST /api/v1/iot/linkage-rules；WRITE 审计）。
     *
     * @param request 登记请求体（@Valid），非空
     * @return 落库后的规则视图；201
     * @throws com.fuyun.common.exception.BizException IOT-1018（409 条件词表违例）
     */
    @PostMapping("/linkage-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public LinkageRuleVO create(@Valid @RequestBody SaveLinkageRuleRequest request) {
        return linkageRuleService.create(request);
    }

    /**
     * 规则更新（PUT /api/v1/iot/linkage-rules/{id}；WRITE 审计）。
     *
     * @param id      规则行 id（路径变量）
     * @param request 更新请求体（@Valid），非空
     * @return 更新后的规则视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1017（404）或 IOT-1018（409）
     */
    @PutMapping("/linkage-rules/{id}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public LinkageRuleVO update(@PathVariable Long id, @Valid @RequestBody SaveLinkageRuleRequest request) {
        return linkageRuleService.update(id, request);
    }

    /**
     * 规则软删（DELETE /api/v1/iot/linkage-rules/{id}；WRITE 审计；@TableLogic 逻辑删）。
     *
     * @param id 规则行 id（路径变量）
     * @return 204 无体
     * @throws com.fuyun.common.exception.BizException IOT-1017（404 规则不存在）
     */
    @DeleteMapping("/linkage-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void delete(@PathVariable Long id) {
        linkageRuleService.delete(id);
    }

    /**
     * 联动日志分页（GET /api/v1/iot/linkage-logs；规则/来源/结果过滤，page 0 基）。
     *
     * @param request 分页查询请求（查询参数绑定，@Valid），非空
     * @return 分页出参；200
     */
    @GetMapping("/linkage-logs")
    public PageResult<LinkageLogVO> page(@Valid LinkageLogQueryRequest request) {
        return linkageRuleService.page(request);
    }

    /**
     * 联动日志人工重推（POST /api/v1/iot/linkage-logs/{linkageNo}/retry；WRITE 审计；仅 FAILED
     * 行可重推，重执行 + CAS 登记 + executed 事件同事务）。
     *
     * @param linkageNo 联动执行业务号（路径变量）
     * @return 重推后的日志视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1017（404 借承日志行不存在）或
     *                                                  IOT-1018（409 非 FAILED/规则删停/并发被承接）
     */
    @PostMapping("/linkage-logs/{linkageNo}/retry")
    @AuditLog(actionType = AuditActionType.WRITE)
    public LinkageLogVO retry(@PathVariable String linkageNo) {
        return linkageRuleService.retry(linkageNo);
    }
}
