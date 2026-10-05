package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.system.internal.PermissionRegistry;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 403 鉴权矩阵集成测试（PR-4D Task 8，W-37 主体的端到端行为锚 + D4 完整性门禁的测试承载）：
 * 真实穿过 TraceIdFilter → AuthTokenInterceptor → AuthorizationInterceptor → Controller 全链
 * （RANDOM_PORT + TestRestTemplate），四组断言：
 *
 * <p>①<b>全量端点登记对照</b>（矩阵完整性守护，D4 语义的测试承载）：反射扫描
 * {@link RequestMappingHandlerMapping} 全部 /api/v1 端点（方法+路径模板），逐一断言
 * {@link PermissionRegistry#resolve} 非空——豁免 12 端点显式列在 {@link #EXEMPT_ENDPOINTS}
 * 常量（附件 C 权威清单）。守卫语义：新增 Controller 端点未登记权限点即本断言红（CI 拦截，
 * D4「未登记放行+warn」的运行期兜底不成为登记缺口的长期通道）。
 *
 * <p>②<b>ADMIN 全放</b>（D3 超管运行期一票放行）：admin 登录抽验 5 个跨域端点全非 403
 * （2xx/4xx 业务码均可——断言 ≠403，防把「矩阵拒绝」与「业务校验失败」混同）。
 *
 * <p>③<b>业务角色正反例</b>：nursedemo（NURSE）正例 GET /nursing/tasks 非 403、跨域反例
 * （billing 审批面 / iot 产品面）403+errorCode=SYS-1033；registrardemo（REGISTRAR）正例
 * GET /patient/patients/search 非 403、反例 POST /billing/settlements 403。
 *
 * <p>④<b>哨兵可达</b>（W-39 限行内的三端点+越面拒绝）：bigscreen-token 签发（wardId=1001，
 * 数字形态——iot/alarms 的 wardId 绑定为 Long，字符串病区编码会 400 而非 200）→
 * board 与 alarms 区内 200、越面 GET /billing/settlements 非 200（403 SYS-1032 或 401，
 * 勿过锁状态码——拦截层演进不破测试）。
 *
 * <p>夹具说明：nursedemo 未种 nurse_assignment 绑定行（V1117:512-513 口径），而护理域
 * API 挂码端点统一挂 W-40 当班绑定守卫（fail-closed，ADMIN 无豁免）——正例用例先照
 * V1114 种子行形态直插 nursedemo→W01 绑定行（NurseBoardWsIT admin 夹具同款先例），
 * 使「非 403」只归因于 RBAC 矩阵放行而非病区守卫；board 族端点本类不碰（哨兵组承载）。
 *
 * <p>容器三件套类级独占（GC9 红线，NurseBoardWsIT :86-103 逐字同型：tag 与 deploy compose
 * 严格一致 + it/rabbitmq.conf 挂载 + @ServiceConnection）；测试假密钥经基类
 * {@link FuyunStackITBase} @DynamicPropertySource 注入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RbacMatrixIT extends FuyunStackITBase {

    /** TimescaleDB 容器：RBAC 种子（V1116/V1117）与矩阵装载的断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：登录/哨兵会话键（fy:system:session:{sid}）的真实存储 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：完整上下文装配必需（消费容器随上下文启动），本类不断言消息链路 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"),
                    "/etc/rabbitmq/conf.d/20-fuyun-rbac-it.conf");

    /** 模板变量段识别器（{var} 单段形态，PathPattern 支持的变量语法子集） */
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^}/]*}");

    /**
     * 403 矩阵豁免清单（附件 C 权威 12 行，硬编码显式登记——每行注释标注防线归属）：
     * 豁免语义=该端点不要求登记 API 权限点（其安全防线在别处承载）；清单外任何 /api/v1
     * 端点未登记权限点即第①组断言红。模板变量段比对经 {@link #normalizeEndpoint} 归一
     * （{var}→{}），豁免常量与 Controller 实际变量命名差异不产生假缺口。
     */
    private static final List<String> EXEMPT_ENDPOINTS = List.of(
            // 免认证白名单（防线：AUTH_WHITELIST，无需登录）
            "POST /api/v1/system/auth/login",
            // 免认证白名单（防线：refresh 令牌强校验）
            "POST /api/v1/system/auth/refresh",
            // 免认证白名单（防线：哨兵签发端点，5min 短令牌）
            "POST /api/v1/system/auth/bigscreen-token",
            // 登录态自我会话管理（防线：AuthTokenInterceptor 401）
            "POST /api/v1/system/auth/logout",
            // portal 患者匿名通道（防线：服务端介质解析定 patientId）
            "GET /api/v1/outpatient/portal/schedules",
            // portal 患者匿名通道（防线：同上）
            "POST /api/v1/outpatient/portal/appointments",
            // portal 患者匿名通道（防线：同上）
            "POST /api/v1/outpatient/portal/appointments/{no}/cancel",
            // 候诊榜匿名只读快照（防线：出参脱敏）
            "GET /api/v1/outpatient/queues/{queueId}/tickets",
            // 哨兵可达端点（防线：W-39 哨兵限行+W-40 当班绑定守卫，roles 空集必被 403）
            "GET /api/v1/nursing/board/{wardId}",
            // 哨兵可达端点（防线：同上）
            "GET /api/v1/ward/infusion-board/{wardId}",
            // 哨兵可达端点（防线：W-39 哨兵限行，wardId query 一致性）
            "GET /api/v1/iot/alarms",
            // 非 /api/v1（IoTDA HTTP 兜底通道，不在拦截路径；防线：IoTDA 凭证）
            "POST /ingest/iotda-fallback");

    /** 403 业务错误码（AuthorizationInterceptor 矩阵拒绝面） */
    private static final String PERMISSION_DENIED_CODE = "SYS-1033";

    /** nursedemo→W01 当班绑定行夹具主键（V1114 种子段外保留段——雪花 19 位量级永不冲突） */
    private static final long NURSEDEMO_BINDING_ROW_ID = 9114000000000000102L;

    /** nursedemo 种子账号（V1117 id=11，NURSE 角色） */
    private static final String NURSE_DEMO_LOGIN_NAME = "nursedemo";

    /** registrardemo 种子账号（V1117 id=14，REGISTRAR 角色） */
    private static final String REGISTRAR_DEMO_LOGIN_NAME = "registrardemo";

    /** 哨兵令牌绑定病区（数字形态：iot/alarms 的 wardId 绑定为 Long，字符串编码会 400） */
    private static final String SENTINEL_WARD_ID = "1001";

    /** MVC 请求映射注册表：全量端点扫描源（第①组断言的反射入口）——按名注入：actuator 的
     * controllerEndpointHandlerMapping 同为该类型，按类型注入会歧义 */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** 403 鉴权矩阵登记面（fuyun-system internal——app 测试引用有 IotAmqpMetrics 先例，Modulith 排除谓词覆盖 com.fuyun.app..） */
    @Autowired
    private PermissionRegistry permissionRegistry;

    /**
     * 第①组：全量端点登记对照——扫描 /api/v1 全端点逐一 resolve 非空（豁免清单除外），
     * 新增端点不登记权限点即本断言红（D4 完整性门禁承载，CI 拦截登记缺口）。
     */
    @Test
    @DisplayName("全量登记对照：/api/v1 全端点 PermissionRegistry.resolve 非空（豁免 12 端点显式清单）")
    void allApiEndpointsAreRegisteredInPermissionMatrix() {
        assertThat(EXEMPT_ENDPOINTS).as("豁免清单须为附件 C 权威 12 行").hasSize(12);
        // 豁免集归一化：模板变量段抹平（{var}→{}），与扫描键同构比对
        Set<String> exemptNormalized =
                EXEMPT_ENDPOINTS.stream().map(RbacMatrixIT::normalizeEndpoint).collect(Collectors.toSet());

        List<String> scanned = new ArrayList<>();
        List<String> unregistered = new ArrayList<>();
        handlerMapping
                .getHandlerMethods()
                .forEach((info, handler) -> collectEndpointKeys(info, scanned, unregistered, exemptNormalized));
        assertThat(scanned).as("端点扫描面不得为空（空面=守护静默失效）").isNotEmpty();
        // 扫描面自证：全部键的路径段以 /api/v1 开头（过滤不变式；ingest 等 /ingest 通道天然出界，
        // 若被搬入 /api/v1 即出现在扫描面且不在豁免归一集，由未登记断言拦截）
        assertThat(scanned).as("扫描面只含 /api/v1 端点（过滤不变式）").allSatisfy(key -> assertThat(
                        key.substring(key.indexOf(' ') + 1))
                .startsWith("/api/v1"));

        // 未登记缺口逐条报出（完整端点键列在失败消息，缺口定位零成本）
        assertThat(unregistered)
                .as("全部 /api/v1 端点（豁免 12 行除外）必须在 403 鉴权矩阵登记：新增端点须同步 V1116 种子")
                .isEmpty();

        // 豁免清单自身不腐化：11 个 /api/v1 豁免行必须仍存在于扫描面（端点下线须同步删豁免行）
        Set<String> scannedNormalized =
                scanned.stream().map(RbacMatrixIT::normalizeEndpoint).collect(Collectors.toSet());
        List<String> staleExemptions = EXEMPT_ENDPOINTS.stream()
                .filter(key -> key.substring(key.indexOf(' ') + 1).startsWith("/api/v1"))
                .filter(key -> !scannedNormalized.contains(normalizeEndpoint(key)))
                .toList();
        assertThat(staleExemptions).as("豁免清单中的 /api/v1 端点必须真实存在（防豁免清单腐化为永久空洞）").isEmpty();
    }

    /**
     * 第②组：ADMIN 全放——admin 登录抽验 5 个跨域端点全非 403（D3 一票放行）。
     *
     * <p>抽验面覆盖五域：billing 资金审批面 / system 字典写面 / iot 产品写面 / integration
     * 治理读面 / outpatient 排班维护面——均为业务角色零绑定或不绑定的「高敏面」，ADMIN
     * 若被矩阵拒绝即 D3 失效。请求体非业务合法（"{}" 占位）——本组只断言鉴权面（≠403），
     * 2xx/4xx 业务码均可。
     */
    @Test
    @DisplayName("ADMIN 全放：admin 登录抽验 5 个跨域高敏端点全非 403（D3 一票放行）")
    void adminPassesAllCrossDomainEndpointsWithoutForbidden() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        List<String> forbidden = new ArrayList<>();
        // 五端点（方法+URI）——非法路径参数/占位请求体只触发业务 4xx，不触鉴权面
        assertNotForbidden(forbidden, token, HttpMethod.POST, "/api/v1/billing/refunds/1/approve", "{}");
        assertNotForbidden(forbidden, token, HttpMethod.POST, "/api/v1/system/dict-types", "{}");
        assertNotForbidden(forbidden, token, HttpMethod.POST, "/api/v1/iot/products", "{}");
        assertNotForbidden(forbidden, token, HttpMethod.GET, "/api/v1/integration/dead-letters", null);
        assertNotForbidden(forbidden, token, HttpMethod.POST, "/api/v1/outpatient/schedule-templates", "{}");
        assertThat(forbidden).as("ADMIN 对五个跨域高敏端点不得收到 403（D3 运行期全放）").isEmpty();
    }

    /**
     * 第③组：业务角色正反例——nursedemo/registrardemo 各验正例（本域挂码端点非 403）
     * 与反例（跨域端点 403+errorCode=SYS-1033，矩阵拒绝面而非业务校验面）。
     */
    @Test
    @DisplayName("业务角色正反例：nursedemo 本域放行+跨域 403 SYS-1033；registrardemo 正反例")
    void businessRoleMatrixAllowsOwnDomainAndDeniesCrossDomain() throws Exception {
        // nursedemo→W01 当班绑定行夹具（V1114 种子行形态直插，长期有效窗当日命中）：
        // 护理域 API 挂码端点统一挂 W-40 fail-closed 守卫，无绑定行则正例会 403 NS-1028
        jdbcTemplate.update("""
                INSERT INTO nursing.nurse_assignment
                  (id, ward_id, nurse_id, assignment_type, shift_code, bed_no, patient_id,
                   valid_from, valid_to, status, created_by, updated_by, deleted)
                VALUES (?, 'W01', '11', 'PRIMARY', 'DAY', NULL, NULL,
                   DATE '2026-01-01', NULL, 'ACTIVE', 'IT', 'IT', 0)
                """, NURSEDEMO_BINDING_ROW_ID);

        String nurseToken = loginToken(NURSE_DEMO_LOGIN_NAME);
        // 正例：护理域任务清单（NURSE 挂码端点，携绑定行+W01 越过 W-40 守卫）——非 403
        assertThat(bearerJson("/api/v1/nursing/tasks?wardId=W01", HttpMethod.GET, nurseToken, null)
                        .getStatusCode()
                        .value())
                .as("nursedemo 访问护理域任务清单不应 403（NURSE 矩阵放行）")
                .isNotEqualTo(403);
        // 反例①：billing 资金审批面（CASHIER 绑定，NURSE 集外）——403+SYS-1033
        assertMatrixForbidden(nurseToken, HttpMethod.POST, "/api/v1/billing/refunds/1/approve", "{}");
        // 反例②：iot 产品写面（IOT_ADMIN 绑定，NURSE 集外）——403+SYS-1033
        assertMatrixForbidden(nurseToken, HttpMethod.POST, "/api/v1/iot/products", "{}");

        String registrarToken = loginToken(REGISTRAR_DEMO_LOGIN_NAME);
        // 正例：患者检索（REGISTRAR 挂码端点，空关键词返回空数据页）——非 403
        assertThat(bearerJson("/api/v1/patient/patients/search?keyword=", HttpMethod.GET, registrarToken, null)
                        .getStatusCode()
                        .value())
                .as("registrardemo 访问患者检索不应 403（REGISTRAR 矩阵放行）")
                .isNotEqualTo(403);
        // 反例：billing 结算写面（CASHIER 绑定，REGISTRAR 集外）——403+SYS-1033
        assertMatrixForbidden(registrarToken, HttpMethod.POST, "/api/v1/billing/settlements", "{}");
    }

    /**
     * 第④组：哨兵可达——bigscreen-token 签发后三端点行为面：board/alarms 区内 200
     * （W-39 限行内+哨兵守卫豁免）、越面 GET /billing/settlements 非 200（限行拒绝，
     * 403 SYS-1032 或 401——勿过锁状态码，拦截层语义演进不破测试）。
     */
    @Test
    @DisplayName("哨兵可达：bigscreen-token 签发→board/alarms 区内 200+越面端点非 200")
    void sentinelTokenReachesBoardEndpointsAndIsDeniedBeyondAllowlist() {
        // 匿名签发哨兵令牌（AUTH_WHITELIST 通道，W-39 签发面；wardId 数字形态服务 alarms Long 绑定）
        HttpHeaders issueHeaders = new HttpHeaders();
        issueHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> tokenResp = restTemplate.exchange(
                "/api/v1/system/auth/bigscreen-token?wardId=" + SENTINEL_WARD_ID,
                HttpMethod.POST,
                new HttpEntity<>(null, issueHeaders),
                String.class);
        assertThat(tokenResp.getStatusCode().value()).as("哨兵令牌签发应 200").isEqualTo(200);
        String sentinelToken = JsonPath.<String>read(tokenResp.getBody(), "$.accessToken");

        // 区内 board：路径尾段=令牌绑定病区 → 200（W-68 闭合+空病区快照四段空集仍 200）
        assertThat(bearerJson("/api/v1/nursing/board/" + SENTINEL_WARD_ID, HttpMethod.GET, sentinelToken, null)
                        .getStatusCode()
                        .value())
                .as("哨兵区内 board 快照应 200")
                .isEqualTo(200);
        // 区内 alarms：query wardId 与令牌一致 → 200（W-39 精确端点 query 一致性面）
        assertThat(bearerJson("/api/v1/iot/alarms?wardId=" + SENTINEL_WARD_ID, HttpMethod.GET, sentinelToken, null)
                        .getStatusCode()
                        .value())
                .as("哨兵区内 alarms 清单应 200")
                .isEqualTo(200);
        // 越面：billing 结算面不在哨兵 allowlist → 非 200（403 SYS-1032 限行拒绝；
        // 该路径无 GET 列表端点，拒绝对象为拦截层而非 handler，404 亦满足非 200 语义）
        assertThat(bearerJson("/api/v1/billing/settlements", HttpMethod.GET, sentinelToken, null)
                        .getStatusCode()
                        .value())
                .as("哨兵越面端点应非 200（W-39 限行拒绝）")
                .isNotEqualTo(200);
    }

    /**
     * 归集单个映射信息的全部（方法×路径模板）端点键：/api/v1 前缀过滤 → 豁免集比对 →
     * 矩阵 resolve 判定（模板串直接 resolve——PathPattern 的 {var} 段对模板串本身命中）。
     *
     * @param info             MVC 映射信息，非空；来源：handlerMapping.getHandlerMethods() 键集
     * @param scanned          扫描面累积器，非空；供过滤不变式与豁免清单存在性比对
     * @param unregistered     未登记缺口累积器，非空；断言失败消息的逐条报出面
     * @param exemptNormalized 豁免清单归一化集，非空；来源：EXEMPT_ENDPOINTS 归一化
     */
    private void collectEndpointKeys(
            RequestMappingInfo info, List<String> scanned, List<String> unregistered, Set<String> exemptNormalized) {
        // 无显式方法的映射（@RequestMapping 裸用）无矩阵键可比，跳过——仓内 Controller 均为显式动词注解
        for (RequestMethod requestMethod : info.getMethodsCondition().getMethods()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/v1")) {
                    continue;
                }
                String key = requestMethod.name() + " " + pattern;
                scanned.add(key);
                if (exemptNormalized.contains(normalizeEndpoint(key))) {
                    continue;
                }
                // 模板串直接 resolve：{var} 权限点模板对同名模板串天然命中（纪律 2 实现要点）
                if (permissionRegistry.resolve(requestMethod.name(), pattern).isEmpty()) {
                    unregistered.add(key);
                }
            }
        }
    }

    /**
     * 断言携带 Bearer 令牌的请求未收到 403（ADMIN 全放组的逐端点判定；403 记入缺口清单集中报出）。
     *
     * @param forbidden 403 缺口累积器，非空；断言集中报出
     * @param token     Bearer 令牌原文，非空；经 Authorization 头注入（禁入日志）
     * @param method    HTTP 方法，非空
     * @param path      目标 URI，非空
     * @param jsonBody  请求体 JSON 字符串，可空；GET 传 null，占位体只触发业务校验不触鉴权面
     */
    private void assertNotForbidden(
            List<String> forbidden, String token, HttpMethod method, String path, String jsonBody) {
        int status = bearerJson(path, method, token, jsonBody).getStatusCode().value();
        if (status == 403) {
            forbidden.add(method.name() + " " + path);
        }
    }

    /**
     * 断言矩阵拒绝面：403 且 body.errorCode=SYS-1033（AuthorizationInterceptor 手工写出的
     * ProblemDetail，结构同全局渲染——照 AuthFlowIT 第 10 步 body 解析形态）。
     *
     * @param token    Bearer 令牌原文，非空
     * @param method   HTTP 方法，非空
     * @param path     目标 URI，非空
     * @param jsonBody 请求体 JSON 字符串，非空；403 场景请求体在拦截器层即被拒不参与校验
     * @throws Exception 响应体非合法 JSON（契约断裂显式失败，禁静默吞解析异常）
     */
    private void assertMatrixForbidden(String token, HttpMethod method, String path, String jsonBody) throws Exception {
        ResponseEntity<String> response = bearerJson(path, method, token, jsonBody);
        assertThat(response.getStatusCode().value())
                .as(method.name() + " " + path + " 应 403")
                .isEqualTo(403);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("errorCode").asText())
                .as(method.name() + " " + path + " 拒绝码须为矩阵拒绝面 SYS-1033")
                .isEqualTo(PERMISSION_DENIED_CODE);
    }

    /**
     * 携 Bearer 令牌的原始响应请求助手（NurseBoardWsIT bearerJson 同款——状态码断言通道）。
     *
     * @param path     目标 URI（/api/v1 前缀），非空
     * @param method   HTTP 方法，非空
     * @param token    Bearer 令牌原文，非空；经 Authorization 头注入（禁入日志）
     * @param jsonBody 请求体 JSON 字符串，可空；GET 传 null
     * @return 原始 HTTP 响应（状态码与响应体可断言），非空
     */
    private ResponseEntity<String> bearerJson(String path, HttpMethod method, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(jsonBody, headers), String.class);
    }

    /**
     * 端点键归一化：模板变量段抹平（{var}→{}）——豁免常量与 Controller 实际变量命名差异
     * （如 {no} vs {orderNo}）不产生假缺口，方法与路径结构仍严格比对。
     *
     * @param endpointKey 端点键（METHOD + 空格 + 路径模板），非空
     * @return 归一化端点键，非空
     */
    private static String normalizeEndpoint(String endpointKey) {
        return TEMPLATE_VARIABLE.matcher(endpointKey).replaceAll("{}");
    }
}
