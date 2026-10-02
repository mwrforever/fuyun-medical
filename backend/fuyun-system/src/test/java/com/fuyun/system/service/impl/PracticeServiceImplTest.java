package com.fuyun.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.dto.PracticeGrantCreateRequest;
import com.fuyun.system.entity.PracticeGrant;
import com.fuyun.system.enums.PracticeGrantStatus;
import com.fuyun.system.internal.PracticeChangedEvent;
import com.fuyun.system.mapper.PracticeGrantMapper;
import com.fuyun.system.vo.PracticeCheckResponse;
import com.fuyun.system.vo.PracticeGrantVO;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 执业授权校验与管理服务单测（M01 FU-M01-04，V704 practice_grant 真实化）：check 三态语义
 * （生效放行/过期/无记录，读侧派生无定时任务）、授权登记（唯一索引冲突 409 + practice.changed
 * 事务内发布）、停权 CAS（0 行 SYS-1021 + SUSPENDED 事件）、清单读侧派生 EXPIRED 不回写、
 * check 缺省时钟分歧时区锚（时区纪律专项 A 类，锚定模式确定性红/绿）。
 * AFTER_COMMIT 的 MQ 发送时机归 SystemEventPublisherTest 与集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class PracticeServiceImplTest {

    /** 演示医师员工 ID（与 V704 种子 employee_id=3 同源，身份链对齐红线锚点） */
    private static final long EMPLOYEE_ID = 3L;

    private static final String GRANT_TYPE = "PRESCRIPTION";

    private static final String OPERATOR = "admin001";

    /** 北京钟面（医疗日界权威时区）：分歧时区锚与必然同步期望面的推导源（三域先例形态） */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    @Mock
    private PracticeGrantMapper practiceGrantMapper;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<PracticeChangedEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<LocalDate> checkDateCaptor;

    private PracticeServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // check 未命中回查与清单查询的 lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PracticeGrant.class);
    }

    @BeforeEach
    void setUp() {
        service = new PracticeServiceImpl(practiceGrantMapper, events);
        OperatorContextHolder.set(OPERATOR);
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    /** 生效授权行替身：validTo 可空（null=长期有效） */
    private PracticeGrant grant(LocalDate validTo) {
        PracticeGrant grant = new PracticeGrant();
        grant.setId(9L);
        grant.setEmployeeId(EMPLOYEE_ID);
        grant.setGrantType(GRANT_TYPE);
        grant.setStatus(PracticeGrantStatus.EFFECTIVE);
        grant.setValidFrom(LocalDate.of(2026, 1, 1));
        grant.setValidTo(validTo);
        return grant;
    }

    @Test
    @DisplayName("check 生效授权在有效期内（valid_to=明日）：passed=true，reason=执业授权有效")
    void checkPassesWhenEffectiveGrantWithinValidity() {
        when(practiceGrantMapper.selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), any()))
                .thenReturn(grant(LocalDate.now().plusDays(1)));
        OffsetDateTime checkTime = OffsetDateTime.now();

        PracticeCheckResponse response = service.check(new PracticeCheckRequest(EMPLOYEE_ID, GRANT_TYPE, checkTime));

        assertThat(response.passed()).isTrue();
        assertThat(response.reason()).isEqualTo("执业授权有效：PRESCRIPTION");
        // 契约冻结面：employeeId 以字符串回显、checkTime 原样回显
        assertThat(response.employeeId()).isEqualTo("3");
        assertThat(response.checkTime()).isEqualTo(checkTime);
    }

    @Test
    @DisplayName("check 长期授权（valid_to=NULL）：passed=true")
    void checkPassesWhenGrantHasNoExpiry() {
        when(practiceGrantMapper.selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), any()))
                .thenReturn(grant(null));

        PracticeCheckResponse response = service.check(new PracticeCheckRequest(EMPLOYEE_ID, GRANT_TYPE, null));

        assertThat(response.passed()).isTrue();
        assertThat(response.reason()).isEqualTo("执业授权有效：PRESCRIPTION");
    }

    @Test
    @DisplayName("check 无授权记录：passed=false，reason=无有效执业授权记录（不回查到过期行）")
    void checkFailsWhenNoGrantRow() {
        when(practiceGrantMapper.selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), any()))
                .thenReturn(null);
        when(practiceGrantMapper.selectOne(any())).thenReturn(null);

        PracticeCheckResponse response = service.check(new PracticeCheckRequest(EMPLOYEE_ID, GRANT_TYPE, null));

        assertThat(response.passed()).isFalse();
        assertThat(response.reason()).isEqualTo("无有效执业授权记录：PRESCRIPTION");
    }

    @Test
    @DisplayName("check 停权授权不放行：SUSPENDED 行被生效查询排除，reason 同无记录口径（存在性仅清单可见）")
    void checkFailsWhenGrantSuspended() {
        when(practiceGrantMapper.selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), any()))
                .thenReturn(null);
        when(practiceGrantMapper.selectOne(any())).thenReturn(null);

        PracticeCheckResponse response = service.check(new PracticeCheckRequest(EMPLOYEE_ID, GRANT_TYPE, null));

        assertThat(response.passed()).isFalse();
        assertThat(response.reason()).isEqualTo("无有效执业授权记录：PRESCRIPTION");
    }

    @Test
    @DisplayName("check 授权在校验时点已过期（valid_to=昨日）：passed=false，reason=授权已过期（读侧派生）")
    void checkFailsWhenGrantExpiredAtCheckTime() {
        when(practiceGrantMapper.selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), any()))
                .thenReturn(null);
        when(practiceGrantMapper.selectOne(any()))
                .thenReturn(grant(LocalDate.now().minusDays(1)));

        PracticeCheckResponse response = service.check(new PracticeCheckRequest(EMPLOYEE_ID, GRANT_TYPE, null));

        assertThat(response.passed()).isFalse();
        assertThat(response.reason()).isEqualTo("授权已过期：PRESCRIPTION");
    }

    @Test
    @DisplayName("check 缺省校验时点：入参 checkTime=null 时按服务端当前日期查询生效授权")
    void checkDefaultsCheckTimeToNowWhenAbsent() {
        when(practiceGrantMapper.selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), any()))
                .thenReturn(grant(null));

        service.check(new PracticeCheckRequest(EMPLOYEE_ID, GRANT_TYPE, null));

        // 查询日期取服务端北京钟面当日（有效期含当日语义：valid_from <= 今日 <= valid_to）：
        // 服务端医疗日来源已收敛 HEALTHCARE_TZ，期望面同源推导（必然同步——反证实证：保留裸
        // now() 期望在 -12h 分歧钟面下对修复后实现必红）
        verify(practiceGrantMapper).selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), checkDateCaptor.capture());
        assertThat(checkDateCaptor.getValue()).isEqualTo(LocalDate.now(BEIJING_TZ));
    }

    @Test
    @DisplayName("check 缺省时点分歧时区锚（时区纪律专项 A 类）：默认时区钟面与北京不同日时，缺省校验日期与回显时刻按北京钟面取值")
    void checkDefaultsCheckClockToBeijingUnderDivergedDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            // 构造与北京当前日历日期必然分歧的默认时区：-12h/+14h 固定偏移二选一（两偏移对北京
            // 的日期分歧窗并集覆盖全天，引理见锚定模式）——任意时刻可复现「非北京时区 JVM 按
            // 容器日期取错医疗日」；时区 ID 必须取偏移字面量（自定义 ID 使 LocalDate.now()
            // 抛 ZoneRulesException，Task 2 实证）
            Instant now = Instant.now();
            int divergeMillis = -12 * 3600_000;
            if (now.atZone(BEIJING_TZ)
                    .toLocalDate()
                    .equals(now.atZone(ZoneOffset.ofTotalSeconds(divergeMillis / 1000))
                            .toLocalDate())) {
                divergeMillis = 14 * 3600_000; // -12h 与北京同日时改用 +14h（引理保证必分歧）
            }
            // 期望按北京钟面锁定推导（禁裸 now() 当期望源）
            LocalDate expectedToday = LocalDate.now(BEIJING_TZ);
            when(practiceGrantMapper.selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), any()))
                    .thenReturn(grant(null));
            // setDefault 窗口最小化（锚定模式）：stub 先行 → setDefault → 调用捕获 → finally 恢复
            TimeZone.setDefault(new SimpleTimeZone(
                    divergeMillis,
                    ZoneOffset.ofTotalSeconds(divergeMillis / 1000).getId()));

            PracticeCheckResponse response = service.check(new PracticeCheckRequest(EMPLOYEE_ID, GRANT_TYPE, null));

            // 服务端计算的校验日期（selectEffective 入参）按北京钟面取当日：缺陷实现（裸 now()
            // 取容器日期）在分歧时区下必红；若走 reason 二态断言，-12h 窗容器昨日仍命中
            // valid_from<=d<=valid_to 有效窗（PracticeGrantMapper SQL），对缺陷代码不红，故锚
            // 服务端日期本体（确定性红/绿，锚定模式第 1 条）
            verify(practiceGrantMapper).selectEffective(eq(EMPLOYEE_ID), eq(GRANT_TYPE), checkDateCaptor.capture());
            assertThat(checkDateCaptor.getValue()).isEqualTo(expectedToday);
            // 缺省回显时刻同源北京钟面：偏移随断言值钉死 +08:00（缺陷实现取容器偏移
            // -12:00/+14:00 必红，照 BUG-03 先例偏移钉死口径）
            assertThat(response.checkTime().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("登记授权重复冲突：同员工同类型已有生效行触发唯一索引 violation，转 SYS-1022/409")
    void grantRejectsDuplicateEffectiveGrant() {
        when(practiceGrantMapper.insert(any(PracticeGrant.class)))
                .thenThrow(new DuplicateKeyException("uk_practice_grant_active"));

        assertThatThrownBy(() -> service.grant(
                        new PracticeGrantCreateRequest(EMPLOYEE_ID, GRANT_TYPE, null, LocalDate.now(), null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.PRACTICE_GRANT_DUPLICATE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 冲突即拒绝：不发变更事件（无状态变更事实）
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("登记授权成功：事务内发布 practice.changed 触发事件（employeeId/grantType/status=EFFECTIVE）")
    void grantPublishesPracticeChangedOnGrant() {
        // ASSIGN_ID 由 MP 在 insert 时回填雪花 ID：替身模拟回填，服务层据此返回登记 ID
        when(practiceGrantMapper.insert(any(PracticeGrant.class))).thenAnswer(inv -> {
            inv.getArgument(0, PracticeGrant.class).setId(77L);
            return 1;
        });

        long id = service.grant(
                new PracticeGrantCreateRequest(EMPLOYEE_ID, GRANT_TYPE, "演示执业证书 PR-2026-001", LocalDate.now(), null));

        assertThat(id).isEqualTo(77L);
        verify(events).publishEvent(eventCaptor.capture());
        PracticeChangedEvent event = eventCaptor.getValue();
        assertThat(event.employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(event.grantType()).isEqualTo(GRANT_TYPE);
        assertThat(event.status()).isEqualTo("EFFECTIVE");
    }

    @Test
    @DisplayName("停权成功：CAS 命中后发布 practice.changed（status=SUSPENDED），操作人入 CAS 条件更新")
    void withdrawMarksSuspendedAndPublishesPracticeChanged() {
        PracticeGrant row = grant(null);
        row.setGrantType("NARCOTIC");
        when(practiceGrantMapper.casWithdraw(eq(9L), eq(OPERATOR))).thenReturn(1);
        when(practiceGrantMapper.selectById(9L)).thenReturn(row);

        service.withdraw(9L, "医务处停权处理");

        verify(events).publishEvent(eventCaptor.capture());
        PracticeChangedEvent event = eventCaptor.getValue();
        assertThat(event.employeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(event.grantType()).isEqualTo("NARCOTIC");
        assertThat(event.status()).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("停权未知记录：CAS 影响行 0（不存在或已非生效态）按 SYS-1021/404 拒绝且不发事件")
    void withdrawRejectsUnknownGrant() {
        when(practiceGrantMapper.casWithdraw(eq(9L), eq(OPERATOR))).thenReturn(0);

        assertThatThrownBy(() -> service.withdraw(9L, "未知行停权")).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.PRACTICE_GRANT_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("员工授权清单：过期生效行读侧派生 EXPIRED 展示、停权行原态直出，均不回写库")
    void listByEmployeeDerivesExpiredDisplayWithoutWriteback() {
        // 过期造数按北京钟面推导（必然同步）：valid_to 与服务端展示基准日（HEALTHCARE_TZ 当日）
        // 经 isBefore 耦合——裸 now() 造数在容器日期=北京次日的分歧钟面下 valid_to=北京今日会被
        // 新基准日判未过期（EFFECTIVE），期望 EXPIRED 即红（反证实证：+18h 模拟该分歧向必红）
        PracticeGrant expired = grant(LocalDate.now(BEIJING_TZ).minusDays(1));
        PracticeGrant longTerm = grant(null);
        PracticeGrant suspended = grant(null);
        suspended.setStatus(PracticeGrantStatus.SUSPENDED);
        when(practiceGrantMapper.selectList(any())).thenReturn(List.of(expired, longTerm, suspended));

        List<PracticeGrantVO> rows = service.listByEmployee(EMPLOYEE_ID);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).status()).isEqualTo("EXPIRED");
        assertThat(rows.get(1).status()).isEqualTo("EFFECTIVE");
        assertThat(rows.get(2).status()).isEqualTo("SUSPENDED");
        // 读侧派生语义：不回写库（派生仅作用于展示态）
        verify(practiceGrantMapper, never()).updateById(any(PracticeGrant.class));
    }
}
