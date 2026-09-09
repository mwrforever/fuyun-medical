package com.fuyun.system.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.config.JacksonLongToStringConfig;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * CF-2 主数据事件占位载荷单元测试：冻结五个事件的线格式契约（M01 Spec §7 事件名 + 载荷字段名）。
 *
 * <p>线格式冻结语义：payload 以嵌套 JSON 对象进信封线格式，订阅方按字段名解析——字段名漂移即破坏
 * 契约，故对五个 record 逐一断言 JSON 字段名全集；雪花 ID 字段必须以 JSON 字符串承载（backend 宪法
 * A.3-8 Long→String 精度防线在事件线格式同样生效，经 {@link JacksonLongToStringConfig} 定制验证）。
 *
 * <p>测试用 ObjectMapper 经 Boot 同源管道构建（Jackson2ObjectMapperBuilder + 全局定制器），与生产
 * ObjectMapper 序列化行为一致。
 */
class SystemMasterDataPayloadTest {

    /** 与 Boot 自动装配同源的序列化器：应用 Long→String 全局定制后构建 */
    private final ObjectMapper objectMapper = bootMirroredMapper();

    /**
     * 构建 Boot 姿态镜像的 ObjectMapper：经 Jackson2ObjectMapperBuilder 管道应用 Long→String 全局定制，
     * 保证测试断言的线格式与生产 ObjectMapper 输出一致。
     *
     * @return Boot 姿态镜像的 ObjectMapper
     */
    private ObjectMapper bootMirroredMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonLongToStringConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("线格式字段名冻结：五个占位载荷的 JSON 字段名全集与契约一致，无漂移")
    void payloadWireFieldNamesAreFrozenForAllFiveEvents() {
        assertThat(fieldNamesOf(new DictPublishedPayload("gender", 1)))
                .containsExactlyInAnyOrder("dictType", "version");
        assertThat(fieldNamesOf(new OrgChangedPayload(1L, "UPDATED"))).containsExactlyInAnyOrder("orgId", "changeType");
        assertThat(fieldNamesOf(new UserChangedPayload(1L, "UPDATED")))
                .containsExactlyInAnyOrder("userId", "changeType");
        assertThat(fieldNamesOf(new ParamChangedPayload("billing", "invoice.title")))
                .containsExactlyInAnyOrder("module", "paramKey");
        assertThat(fieldNamesOf(new PracticeChangedPayload(1L, "处方权", "EFFECTIVE")))
                .containsExactlyInAnyOrder("employeeId", "grantType", "status");
    }

    @Test
    @DisplayName("雪花 ID 字段以 JSON 字符串承载：Long→String 精度防线在事件线格式生效")
    void longIdFieldsSerializeAsJsonStrings() {
        // 超出 JS Number.MAX_SAFE_INTEGER 的雪花 ID 值：JSON 数值形态会丢精度，必须为字符串
        long snowflakeId = 1234567890123456789L;
        JsonNode org = objectMapper.valueToTree(new OrgChangedPayload(snowflakeId, "UPDATED"));
        JsonNode user = objectMapper.valueToTree(new UserChangedPayload(snowflakeId, "UPDATED"));
        JsonNode practice = objectMapper.valueToTree(new PracticeChangedPayload(snowflakeId, "处方权", "EFFECTIVE"));

        assertThat(org.get("orgId").isTextual()).isTrue();
        assertThat(org.get("orgId").asText()).isEqualTo("1234567890123456789");
        assertThat(user.get("userId").isTextual()).isTrue();
        assertThat(user.get("userId").asText()).isEqualTo("1234567890123456789");
        assertThat(practice.get("employeeId").isTextual()).isTrue();
        assertThat(practice.get("employeeId").asText()).isEqualTo("1234567890123456789");
    }

    @Test
    @DisplayName("字典版本号保留 JSON 数值形态：版本号非雪花 ID，不随 Long→String 定制字符串化")
    void dictVersionKeepsJsonNumberForm() {
        JsonNode dict = objectMapper.valueToTree(new DictPublishedPayload("gender", 1));

        assertThat(dict.get("dictType").asText()).isEqualTo("gender");
        assertThat(dict.get("version").isNumber()).isTrue();
        assertThat(dict.get("version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("载荷 record 往返无损：JSON 反序列化回 record 与原实例字段相等")
    void payloadRoundTripPreservesAllFields() throws Exception {
        DictPublishedPayload dict = new DictPublishedPayload("gender", 1);
        OrgChangedPayload org = new OrgChangedPayload(1001L, "UPDATED");
        UserChangedPayload user = new UserChangedPayload(2001L, "UPDATED");
        ParamChangedPayload param = new ParamChangedPayload("billing", "invoice.title");
        PracticeChangedPayload practice = new PracticeChangedPayload(3001L, "麻精处方权", "SUSPENDED");

        assertThat(objectMapper.treeToValue(objectMapper.valueToTree(dict), DictPublishedPayload.class))
                .isEqualTo(dict);
        assertThat(objectMapper.treeToValue(objectMapper.valueToTree(org), OrgChangedPayload.class))
                .isEqualTo(org);
        assertThat(objectMapper.treeToValue(objectMapper.valueToTree(user), UserChangedPayload.class))
                .isEqualTo(user);
        assertThat(objectMapper.treeToValue(objectMapper.valueToTree(param), ParamChangedPayload.class))
                .isEqualTo(param);
        assertThat(objectMapper.treeToValue(objectMapper.valueToTree(practice), PracticeChangedPayload.class))
                .isEqualTo(practice);
    }

    /**
     * 提取载荷序列化产物的 JSON 字段名全集（线格式冻结断言锚点）。
     *
     * @param payload 载荷 record，非空
     * @return 顶层字段名集合
     */
    private Set<String> fieldNamesOf(Object payload) {
        JsonNode node = objectMapper.valueToTree(payload);
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
