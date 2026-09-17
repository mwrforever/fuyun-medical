package com.fuyun.app.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import jakarta.annotation.PostConstruct;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.converters.ModelConverterRegistrar;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI schema 治理配置：int64 → string 覆写（D-18 根治，裁决方向②）。
 *
 * <p>动机：backend A.3-8 Jackson 全局 Long→String 序列化使运行时所有 int64 字段（雪花 ID、
 * 金额分值）输出 JSON 字符串，而 springdoc 生成的契约按 int64 标 number——前端以契约为准
 * 即与运行时漂移（openapi-typescript 生成 number）。本配置把契约钉回 string，三处口径
 * （后端输出/web A.3-6 红线/生成物）单一事实化。
 *
 * <p>生效面：仅 /v3/api-docs 文档生成链路；不影响任何运行时序列化（Jackson 配置独立）。
 *
 * <p>注册时序与落点（真栈实测定稿，禁改回裸 {@code ModelConverters.getInstance()}）：
 * swagger-core 2.2.47 起按 openapi31 开关分双单例，springdoc 的 ModelConverterRegistrar
 * 在其构造器中把自带转换器逐个插链首注册进 {@code getInstance(isOpenapi31())} 实例——
 * 裸 getInstance() 是另一个单例，注册进去永不被消费（首轮真栈实测 97 处 int64 原样）。
 * 故此处构造注入 registrar 固化「其后注册」时序，并同源取 isOpenapi31 定位同一实例；
 * addConverter 插链首且本覆写器先委托后覆写，先于终态解析器拿到 int64 产物
 * （3.0 形态 IntegerSchema / 3.1 形态 JsonSchema，判定兼容双形态见内联说明）。
 * 日志取原生 SLF4J Logger：fuyun-app 模块不依赖 lombok（与模块现状一致），不为此单类引入新依赖。
 */
@Configuration
public class OpenApiSchemaConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSchemaConfig.class);

    /** 仅作时序锚点：其构造器完成 springdoc 自带转换器注册后，本类 @PostConstruct 才插入链首 */
    private final ModelConverterRegistrar modelConverterRegistrar;

    /** 与 ModelConverterRegistrar 同源读取 openapi31 开关，定位 springdoc 实际消费的 ModelConverters 单例 */
    private final SpringDocConfigProperties springDocConfigProperties;

    /**
     * 构造注入 springdoc 注册器与配置：依赖顺序即注册时序，构造完成即被 Spring 强制先于本类初始化。
     *
     * @param modelConverterRegistrar springdoc 转换器注册器（构造期注册自带转换器），仅作时序锚点，非空
     * @param springDocConfigProperties springdoc 配置属性（取 openapi31 开关定位目标单例），非空
     */
    public OpenApiSchemaConfig(
            ModelConverterRegistrar modelConverterRegistrar, SpringDocConfigProperties springDocConfigProperties) {
        this.modelConverterRegistrar = modelConverterRegistrar;
        this.springDocConfigProperties = springDocConfigProperties;
    }

    /** 把 int64 覆写器插入 springdoc 实际消费单例的转换器链首（registrar 注册完成后执行，链首即最先被调用） */
    @PostConstruct
    void registerInt64ToString() {
        ModelConverters.getInstance(springDocConfigProperties.isOpenapi31()).addConverter(new Int64ToStringConverter());
        log.info("OpenAPI 契约治理：int64 schema 已覆写为 string（D-18 根治，与 Jackson Long→String 对齐）");
    }

    /** int64 数值 schema → StringSchema 覆写器（委托链式其余类型原样透传） */
    static final class Int64ToStringConverter implements ModelConverter {

        @Override
        public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
            Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
            if (isInt64NumberSchema(resolved)) {
                StringSchema stringSchema = new StringSchema();
                // 保留原 schema 的必填/描述/示例元数据，类型与 format 改钉 string
                stringSchema.setDescription(resolved.getDescription());
                stringSchema.setExample("0");
                return stringSchema;
            }
            return resolved;
        }

        /**
         * 判定是否 int64 数值 schema，兼容两条解析路径的产物形态：
         * OpenAPI 3.0 路径为 IntegerSchema（type=integer）；本项目实际生效的 3.1 路径上
         * ModelResolver(Json31) 产出 JsonSchema（types 集合含 integer）——首轮真栈实测
         * 仅判 IntegerSchema 时 3.1 形态全部漏网（97 处 int64 原样），故双形态都须覆盖。
         */
        private static boolean isInt64NumberSchema(Schema<?> schema) {
            if (schema == null || !"int64".equals(schema.getFormat())) {
                return false;
            }
            if (schema instanceof IntegerSchema) {
                return true;
            }
            // 3.1 形态：types 集合承载类型信息（JsonSchema），覆盖 integer 即 int64 数值
            return schema.getTypes() != null && schema.getTypes().contains("integer");
        }
    }
}
