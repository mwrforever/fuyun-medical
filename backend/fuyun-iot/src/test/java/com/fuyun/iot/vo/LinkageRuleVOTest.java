package com.fuyun.iot.vo;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.entity.IotLinkageRuleEntity;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 联动规则视图对象单测（EX-30 降级留痕锚定）：JSONB 原文反序列化三分支——正常解析、缺席
 * （null/空白）降级 null 无留痕、畸形原文降级 null + warn 留痕（规则标识与字段名入日志、
 * 库内原文可对账，CommandLogVO 同款口径）；可空契约保持（畸形字段降级 null、其余字段照常
 * 出网、不抛异常）。日志断言走 ListAppender（TelemetryIngestServiceImplTest 同款先例）。
 */
class LinkageRuleVOTest {

    /** 测试规则行雪花 id（warn 留痕断言锚点） */
    private static final long RULE_ID = 9001L;

    /** 测试规则名称（warn 留痕断言锚点） */
    private static final String RULE_NAME = "心率异常联动病房广播";

    private ListAppender<ILoggingEvent> logAppender;

    private Logger voLogger;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 挂 ListAppender 捕获 VO 降级留痕日志（EX-30 断言锚点；逐用例挂/摘防串扰）
        voLogger = (Logger) LoggerFactory.getLogger(LinkageRuleVO.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        voLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        voLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("正常路径：合法 JSONB 原文解析为键值对，全字段逐项映射出网（零留痕）")
    void fromParsesLegalJsonbAndMapsAllFields() {
        IotLinkageRuleEntity entity =
                ruleEntity("{\"metricCode\":\"vital.heart-rate\",\"threshold\":\"120\"}", "{\"channel\":\"ward\"}");

        LinkageRuleVO vo = LinkageRuleVO.from(entity, objectMapper);

        assertThat(vo.id()).isEqualTo(RULE_ID);
        assertThat(vo.ruleName()).isEqualTo(RULE_NAME);
        assertThat(vo.triggerSource()).isEqualTo(LinkageTriggerSource.ALARM_TRIGGERED);
        assertThat(vo.triggerCondition()).containsEntry("metricCode", "vital.heart-rate");
        assertThat(vo.actionType()).isEqualTo(LinkageActionType.WARD_BROADCAST);
        assertThat(vo.actionConfig()).containsEntry("channel", "ward");
        assertThat(vo.targetWardId()).isEqualTo(1001L);
        assertThat(vo.enabled()).isTrue();
        assertThat(warnEvents()).as("合法原文零留痕").isEmpty();
    }

    @Test
    @DisplayName("边界：原文缺席（null/空白）降级 null 出网且不产生留痕（无数据≠畸形）")
    void fromDegradesAbsentJsonbSilently() {
        LinkageRuleVO vo = LinkageRuleVO.from(ruleEntity(null, "  "), objectMapper);

        assertThat(vo.triggerCondition()).as("原文 null 降级 null").isNull();
        assertThat(vo.actionConfig()).as("原文空白降级 null").isNull();
        assertThat(warnEvents()).as("缺席降级非异常路径，零留痕").isEmpty();
    }

    @Test
    @DisplayName("异常路径（EX-30 锚定）：畸形原文降级 null 出网不抛异常 + warn 留痕规则标识与字段名")
    void fromDegradesMalformedJsonbWithWarnTrace() {
        LinkageRuleVO vo = LinkageRuleVO.from(ruleEntity("{not-json", "{\"ward\":1}"), objectMapper);

        // 可空契约保持：畸形字段降级 null，其余字段照常出网，整体转换不抛异常
        assertThat(vo.triggerCondition()).as("畸形触发条件降级 null").isNull();
        assertThat(vo.actionConfig()).as("合法动作配置照常解析").containsEntry("ward", 1);
        assertThat(vo.id()).isEqualTo(RULE_ID);
        List<ILoggingEvent> warns = warnEvents();
        assertThat(warns).as("仅畸形字段产生一条 warn 留痕").hasSize(1);
        String message = warns.get(0).getFormattedMessage();
        assertThat(message).as("留痕含规则 id（库内原文对账锚点）").contains("ruleId=" + RULE_ID);
        assertThat(message).as("留痕含规则名称（业务标识）").contains("ruleName=" + RULE_NAME);
        assertThat(message).as("留痕含字段名（定位畸形原文所在列）").contains("field=triggerCondition");
        assertThat(message).as("留痕含异常摘要").contains("原因=");
    }

    /** 过滤捕获日志中的 warn 级事件（降级留痕唯一级别） */
    private List<ILoggingEvent> warnEvents() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
    }

    /** 规则实体夹具（触发条件/动作配置原文可覆写，其余字段固定） */
    private static IotLinkageRuleEntity ruleEntity(String triggerCondition, String actionConfig) {
        IotLinkageRuleEntity entity = new IotLinkageRuleEntity();
        entity.setId(RULE_ID);
        entity.setRuleName(RULE_NAME);
        entity.setTriggerSource(LinkageTriggerSource.ALARM_TRIGGERED);
        entity.setTriggerCondition(triggerCondition);
        entity.setActionType(LinkageActionType.WARD_BROADCAST);
        entity.setActionConfig(actionConfig);
        entity.setTargetWardId(1001L);
        entity.setEnabled(Boolean.TRUE);
        return entity;
    }
}
