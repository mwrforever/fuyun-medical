package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.LinkageExecutedPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.LinkageLogQueryRequest;
import com.fuyun.iot.dto.SaveLinkageRuleRequest;
import com.fuyun.iot.entity.IotLinkageLogEntity;
import com.fuyun.iot.entity.IotLinkageRuleEntity;
import com.fuyun.iot.enums.LinkageActionResult;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.internal.LinkageExecutor;
import com.fuyun.iot.mapper.IotLinkageLogMapper;
import com.fuyun.iot.mapper.IotLinkageRuleMapper;
import com.fuyun.iot.service.ILinkageRuleService;
import com.fuyun.iot.vo.LinkageLogVO;
import com.fuyun.iot.vo.LinkageRuleVO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 联动规则服务实现（iot.linkage_rule / iot.iot_linkage_log 管理面唯一写入口）：规则 CRUD（软删）、
 * 联动日志分页与 FAILED 行人工重推。
 *
 * <p>触发条件词表校验（FU-M14-10）：键域冻结为 alarm_type/metric_code/device_type，值为非空白
 * 文本；未知键/非 JSON 对象/空白值即 IOT-1018 409 拒保存——防「永不命中」规则静默入库（执行器
 * 对未知键保守不命中）。规则型校验同告警规则域「拒保存缺失行」口径（引擎侧零防御分叉）。
 *
 * <p>人工重推事务边界（CommandDispatcher 同款裁决）：动作重执行在事务外（WS 推送禁入事务，
 * 宪法 A.4.2-7）；CAS 结果迁移 + executed 事件发布经 TransactionTemplate 同事务承载
 * （AFTER_COMMIT 出 MQ——回滚不发布）；CAS 以 action_result='FAILED' 旧状态限定兜底并发双推，
 * 仅首个重推方生效，败方 IOT-1018 409。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 *
 * <p>主表配对（宪法 A.4.3-20，EX-09 收拢）：extends ServiceImpl 声明主表 iot_linkage_rule 继承面
 * （baseMapper 由容器注入基类字段）；既有构造器注入的 ruleMapper 与其并存，方法体维持原 mapper
 * 通道不变（收拢完成态由后续演进消化）；logMapper 为联动日志副表通道（分页查询与重推 CAS）。
 */
@Slf4j
public class LinkageRuleServiceImpl extends ServiceImpl<IotLinkageRuleMapper, IotLinkageRuleEntity>
        implements ILinkageRuleService {

    /** 审计留痕系统操作人（无登录上下文回退值，与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 触发条件词表键域（brief 冻结三键；未知键拒保存） */
    private static final Set<String> CONDITION_KEYS = Set.of("alarm_type", "metric_code", "device_type");

    private final IotLinkageRuleMapper ruleMapper;

    private final IotLinkageLogMapper logMapper;

    private final LinkageExecutor executor;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param ruleMapper   联动规则 mapper，非空；规则 CRUD 通道
     * @param logMapper    联动日志 mapper，非空；日志分页与重推 CAS 通道
     * @param executor     联动执行器，非空；人工重推的动作重执行出口（模块内组件，宪法 B.1）
     * @param events       Spring 事件发布器，非空；重推 executed 事件事务内发布入口
     * @param transactions 事务模板，非空；重推 CAS+发布同事务承载
     * @param objectMapper JSON 转换器，非空；视图 JSONB 出网解析
     */
    public LinkageRuleServiceImpl(
            IotLinkageRuleMapper ruleMapper,
            IotLinkageLogMapper logMapper,
            LinkageExecutor executor,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.logMapper = logMapper;
        this.executor = executor;
        this.events = events;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LinkageRuleVO> listAll() {
        // 数据库读操作：全量规则清单（id 升序稳定输出；@TableLogic 自动携带 deleted=0）
        return ruleMapper
                .selectList(Wrappers.<IotLinkageRuleEntity>lambdaQuery().orderByAsc(IotLinkageRuleEntity::getId))
                .stream()
                .map(entity -> LinkageRuleVO.from(entity, objectMapper))
                .toList();
    }

    @Override
    @Transactional
    public LinkageRuleVO create(SaveLinkageRuleRequest request) {
        // 触发条件词表校验：未知键/非对象/空白值拒保存（防永不命中规则入库）
        validateConditionShape(request.triggerCondition());
        IotLinkageRuleEntity entity = new IotLinkageRuleEntity();
        applyRequest(entity, request);
        // 数据库写操作：规则落行（雪花 id 由 MP ASSIGN_ID 生成）
        ruleMapper.insert(entity);
        log.info(
                "联动规则已登记：ruleId={}，ruleName={}，triggerSource={}，actionType={}，enabled={}",
                entity.getId(),
                entity.getRuleName(),
                entity.getTriggerSource(),
                entity.getActionType(),
                entity.getEnabled());
        return LinkageRuleVO.from(entity, objectMapper);
    }

    @Override
    @Transactional
    public LinkageRuleVO update(Long id, SaveLinkageRuleRequest request) {
        IotLinkageRuleEntity entity = requireRule(id);
        validateConditionShape(request.triggerCondition());
        applyRequest(entity, request);
        // 数据库写操作：字段全量覆写（updated_at 由数据库触发器维护，应用层不触碰审计列）
        ruleMapper.updateById(entity);
        log.info(
                "联动规则已更新：ruleId={}，ruleName={}，triggerSource={}，actionType={}",
                entity.getId(),
                entity.getRuleName(),
                entity.getTriggerSource(),
                entity.getActionType());
        return LinkageRuleVO.from(entity, objectMapper);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        IotLinkageRuleEntity entity = requireRule(id);
        // 数据库写操作：软删（@TableLogic 逻辑删，历史联动日志 rule_id 留痕不受影响）
        ruleMapper.deleteById(entity.getId());
        log.info("联动规则已软删：ruleId={}，ruleName={}", entity.getId(), entity.getRuleName());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<LinkageLogVO> page(LinkageLogQueryRequest request) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：过滤分页（MP 分页 1 基 current 换算 0 基请求；idx_rule_result 准入，
        // @TableLogic 自动携带 deleted=0），executed_at 降序稳定输出
        Page<IotLinkageLogEntity> result = logMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<IotLinkageLogEntity>lambdaQuery()
                        .eq(request.ruleId() != null, IotLinkageLogEntity::getRuleId, request.ruleId())
                        .eq(
                                request.triggerSource() != null,
                                IotLinkageLogEntity::getTriggerSource,
                                request.triggerSource())
                        .eq(
                                request.actionResult() != null,
                                IotLinkageLogEntity::getActionResult,
                                request.actionResult())
                        .orderByDesc(IotLinkageLogEntity::getExecutedAt));
        return PageResult.of(
                result.getRecords().stream().map(LinkageLogVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    public LinkageLogVO retry(String linkageNo) {
        IotLinkageLogEntity row = requireLog(linkageNo);
        if (row.getActionResult() != LinkageActionResult.FAILED) {
            // 非 FAILED 行拒绝：SUCCESS 为终态、PENDING 待回接方收口（均不在人工重推面）
            log.warn("联动人工重推拒绝（仅 FAILED 可重推）：linkageNo={}，actionResult={}", linkageNo, row.getActionResult());
            throw new BizException(
                    IotErrorCode.LINKAGE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅 FAILED 状态可人工重推（当前 " + row.getActionResult() + "）：" + linkageNo);
        }
        IotLinkageRuleEntity rule = ruleMapper.selectById(row.getRuleId());
        if (rule == null) {
            // 规则已软删：动作配置不可得，重推无语义
            log.warn("联动人工重推拒绝（规则已删除）：linkageNo={}，ruleId={}", linkageNo, row.getRuleId());
            throw new BizException(
                    IotErrorCode.LINKAGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "联动规则已删除，无法重推：" + linkageNo);
        }
        if (!Boolean.TRUE.equals(rule.getEnabled())) {
            // 规则已停用：禁用规则不产生新执行（含人工重推触发面）
            log.warn("联动人工重推拒绝（规则已停用）：linkageNo={}，ruleId={}", linkageNo, rule.getId());
            throw new BizException(
                    IotErrorCode.LINKAGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "联动规则已停用，无法重推：" + linkageNo);
        }
        // 动作重执行（事务外——推送禁入事务，宪法 A.4.2-7；沿既有联动号作 WS 联动标记）
        LinkageExecutor.ActionExecution outcome = executor.execute(rule, row.getTriggerRef(), row.getLinkageNo());
        // CAS 结果迁移 + 事件发布同事务（AFTER_COMMIT 出 MQ；CAS 旧状态限定兜底并发双推）
        Boolean claimed = transactions.execute(status -> {
            // 数据库写操作：重推结果 CAS（仅 FAILED 行可迁移，败方零行）
            if (logMapper.casRetryResult(linkageNo, outcome.result().getCode(), outcome.errorMsg(), operator()) == 0) {
                return null;
            }
            // 消息发送：事务内发布重推结果事件（供审计订阅方同步重推面）
            events.publishEvent(new IotDomainEvent(
                    IotMessagingConstants.EVENT_LINKAGE_EXECUTED,
                    new LinkageExecutedPayload(
                            linkageNo,
                            row.getRuleId(),
                            row.getTriggerSource().getCode(),
                            row.getTriggerRef(),
                            row.getActionType().getCode(),
                            outcome.result().getCode(),
                            outcome.executedAt()),
                    outcome.executedAt(),
                    MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY)));
            return Boolean.TRUE;
        });
        if (claimed == null) {
            // 并发窗口被他方承接（CAS 零行）：IOT-1018 拒绝（重执行已发生属强化语义，无副作用残留）
            log.warn("联动人工重推拒绝（并发已被其他重推承接）：linkageNo={}", linkageNo);
            throw new BizException(
                    IotErrorCode.LINKAGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "重推已被并发请求承接，请刷新后查看：" + linkageNo);
        }
        log.info(
                "联动人工重推完成：linkageNo={}，result={}，retryCount 增量=1，operator={}", linkageNo, outcome.result(), operator());
        return LinkageLogVO.from(requireLog(linkageNo));
    }

    /**
     * 规则存在性校验（404 专属码位 IOT-1017）。
     *
     * @param id 规则行 id，非空
     * @return 规则实体，非空
     */
    private IotLinkageRuleEntity requireRule(Long id) {
        // 数据库读操作：主键单查（@TableLogic 自动携带 deleted=0）
        IotLinkageRuleEntity entity = ruleMapper.selectById(id);
        if (entity == null) {
            throw new BizException(IotErrorCode.LINKAGE_RULE_NOT_FOUND, HttpStatus.NOT_FOUND, "联动规则不存在：" + id);
        }
        return entity;
    }

    /**
     * 联动日志存在性校验（404 借承 IOT-1017——词表无日志专属码位，消息显式区分日志行场景，
     * AlarmServiceImpl 借承先例同口径）。
     *
     * @param linkageNo 联动执行业务号，非空
     * @return 日志实体，非空
     */
    private IotLinkageLogEntity requireLog(String linkageNo) {
        // 数据库读操作：自然键 linkage_no 单查（@TableLogic 自动携带 deleted=0）
        IotLinkageLogEntity entity = logMapper.selectOne(
                Wrappers.<IotLinkageLogEntity>lambdaQuery().eq(IotLinkageLogEntity::getLinkageNo, linkageNo));
        if (entity == null) {
            throw new BizException(IotErrorCode.LINKAGE_RULE_NOT_FOUND, HttpStatus.NOT_FOUND, "联动执行日志不存在：" + linkageNo);
        }
        return entity;
    }

    /**
     * 触发条件词表校验：非 JSON 对象/未知键/空白文本值即 IOT-1018 409 拒保存（防永不命中规则
     * 静默入库——执行器对未知键保守不命中）。
     *
     * @param condition 触发条件（@NotNull 后置校验已过），非空
     */
    private static void validateConditionShape(JsonNode condition) {
        if (!condition.isObject()) {
            throw new BizException(
                    IotErrorCode.LINKAGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "触发条件必须为 JSON 对象（键值等值匹配形态）");
        }
        for (Map.Entry<String, JsonNode> entry : condition.properties()) {
            if (!CONDITION_KEYS.contains(entry.getKey())) {
                throw new BizException(
                        IotErrorCode.LINKAGE_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "触发条件含未知条件键：" + entry.getKey() + "（词表：alarm_type/metric_code/device_type）");
            }
            String value = entry.getValue().asText("");
            if (value.isBlank()) {
                throw new BizException(
                        IotErrorCode.LINKAGE_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "触发条件值必须为非空白文本：key=" + entry.getKey());
            }
        }
    }

    /**
     * 请求字段全量覆写到实体（登记/更新共用；enabled 缺省补 true 同告警规则域口径）。
     *
     * @param entity  目标实体（登记为新行/更新为既有行），非空
     * @param request 保存请求，非空
     */
    private static void applyRequest(IotLinkageRuleEntity entity, SaveLinkageRuleRequest request) {
        entity.setRuleName(request.ruleName());
        entity.setTriggerSource(request.triggerSource());
        // JSONB 列以原文 String 承载（JsonbTypeHandler 透传，压缩形态由 Jackson toString 定）
        entity.setTriggerCondition(request.triggerCondition().toString());
        entity.setActionType(request.actionType());
        entity.setActionConfig(
                request.actionConfig() == null ? null : request.actionConfig().toString());
        entity.setTargetWardId(request.targetWardId());
        entity.setEnabled(request.enabled() == null ? Boolean.TRUE : request.enabled());
    }

    /** 操作者取值（写路径审计留痕；无登录上下文回退 system，与审计列默认同源）。 */
    private static String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
