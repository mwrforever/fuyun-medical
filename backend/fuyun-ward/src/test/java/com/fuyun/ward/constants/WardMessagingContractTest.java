package com.fuyun.ward.constants;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.ward.api.ColdChainAlertArchivedPayload;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M16 事件契约三方一致锚（红线可执行化，IotMessagingContractTest 同款形态）：V1102 种子
 * payload_desc ↔ WardMessagingConstants 事件字面量 ↔ ward/api 载荷 record 组件名逐字同源——
 * 任何一侧单独漂移即红灯。另守护：id 82 排段与幂等 INSERT 形态、订阅事件常量字面量与队列
 * 命名前缀冻结。
 */
class WardMessagingContractTest {

    /** V1102 事件种子 SQL 原文（本模块 classpath 内，登记侧基准） */
    private static final String V1102_SQL =
            loadClasspathSql("/db/migration/ward/V1102__seed_ward_cold_chain_event.sql");

    /** 种子行提取正则：SELECT &lt;id&gt;, '&lt;event_type&gt;'（INSERT...SELECT 形态统一先例） */
    private static final Pattern SEED_ROW_PATTERN = Pattern.compile("SELECT (\\d+), '([^']+)");

    /** ward 域发布事件冻结字面量（V1102 id 82） */
    private static final String EVENT_LITERAL = "ward.cold-chain.alert-archived";

    /** 订阅事件冻结字面量全集（iot/nursing 侧登记事件，ward 消费队列命名来源） */
    private static final List<String> SUBSCRIBED_LITERALS =
            List.of("iot.alarm.triggered", "iot.telemetry.anomaly", "nursing.infusion.completed");

    /** 订阅事件常量全集（与上面字面量按下标一一对应） */
    private static final List<String> SUBSCRIBED_CONSTANTS = List.of(
            WardMessagingConstants.EVENT_IOT_ALARM_TRIGGERED,
            WardMessagingConstants.EVENT_IOT_TELEMETRY_ANOMALY,
            WardMessagingConstants.EVENT_NURSING_INFUSION_COMPLETED);

    @Test
    @DisplayName("V1102 种子恰一行：id 82 且 ward 前缀（排段红线）")
    void v1102SeedRowMatchesPlannedId() {
        List<int[]> ids = new ArrayList<>();
        List<String> types = new ArrayList<>();
        Matcher matcher = SEED_ROW_PATTERN.matcher(V1102_SQL);
        while (matcher.find()) {
            ids.add(new int[] {Integer.parseInt(matcher.group(1))});
            types.add(matcher.group(2));
        }
        assertThat(ids).as("V1102 登记行数与计划 1 行不符").hasSize(1);
        assertThat(ids.get(0)[0]).as("V1102 登记行 id 偏离计划排段").isEqualTo(82);
        assertThat(types.get(0)).as("V1102 登记行须为 ward 前缀").startsWith(WardMessagingConstants.MODULE + ".");
    }

    @Test
    @DisplayName("发布事件字面量与常量逐字相等且在 V1102 登记原文中在位（三方一致第一、二方）")
    void publishedConstantMatchesSeedLiteral() {
        assertThat(WardMessagingConstants.EVENT_COLD_CHAIN_ALERT_ARCHIVED)
                .as("发布事件常量与冻结字面量漂移")
                .isEqualTo(EVENT_LITERAL);
        assertThat(V1102_SQL).as("V1102 缺 ward 登记行：" + EVENT_LITERAL).contains("'" + EVENT_LITERAL + "'");
        assertThat(WardMessagingConstants.QUEUE_PREFIX)
                .as("队列命名前缀冻结（q.&lt;消费者模块&gt;.&lt;事件类型&gt; 推导锚）")
                .isEqualTo("q.ward.");
    }

    @Test
    @DisplayName("payload_desc 组件名与载荷 record 组件名逐字一致（三方一致第三方——GC4 红线）")
    void payloadDescMatchesRecordComponents() throws IOException {
        // 种子 desc 段截取：'冷链告警处置归档：' 之后至 '；' 之前即组件名清单文本
        int descStart = V1102_SQL.indexOf("冷链告警处置归档：");
        assertThat(descStart).as("V1102 缺业务描述锚文本").isGreaterThan(0);
        int descEnd = V1102_SQL.indexOf('；', descStart);
        String componentText = V1102_SQL.substring(descStart + "冷链告警处置归档：".length(), descEnd);
        List<String> seedComponents = Arrays.stream(componentText.split("/"))
                .map(name -> name.replaceAll("\\(.*\\)", "").trim())
                .toList();
        List<String> recordComponents = Arrays.stream(ColdChainAlertArchivedPayload.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(seedComponents)
                .as("V1102 payload_desc 组件名与 ColdChainAlertArchivedPayload 组件名三方漂移")
                .containsExactlyElementsOf(recordComponents);
    }

    @Test
    @DisplayName("订阅事件三常量与登记字面量逐字相等（消费队列命名与幂等域来源冻结）")
    void subscribedConstantsMatchRegisteredLiterals() {
        for (int i = 0; i < SUBSCRIBED_LITERALS.size(); i++) {
            assertThat(SUBSCRIBED_CONSTANTS.get(i))
                    .as("第 %d 个订阅常量与冻结字面量漂移", i + 1)
                    .isEqualTo(SUBSCRIBED_LITERALS.get(i));
        }
        assertThat(WardMessagingConstants.QUEUE_IOT_ALARM_TRIGGERED).isEqualTo("q.ward.iot.alarm.triggered");
        assertThat(WardMessagingConstants.QUEUE_IOT_TELEMETRY_ANOMALY).isEqualTo("q.ward.iot.telemetry.anomaly");
        assertThat(WardMessagingConstants.QUEUE_NURSING_INFUSION_COMPLETED)
                .isEqualTo("q.ward.nursing.infusion.completed");
    }

    /** classpath SQL 读取（登记侧基准原文） */
    private static String loadClasspathSql(String location) {
        try (InputStream in = WardMessagingContractTest.class.getResourceAsStream(location)) {
            assertThat(in).as("classpath 缺种子 SQL：" + location).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 classpath SQL 失败：" + location, e);
        }
    }
}
