package com.fuyun.inpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.inpatient.service.IOrderPlanService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 日切分解定时任务单测（internal/ 不在 service.impl 门禁包，分批事务边界与幂等兜底逻辑归
 * OrderPlanServiceImplTest 承载——本测试锚定调度触发面：次日计划日期入参与单一委托调用）。
 */
@ExtendWith(MockitoExtension.class)
class OrderPlanDecomposeJobTest {

    /** 北京钟面（时区纪律专项 A 类）：期望面推导与生产医疗日同源口径，禁裸 now() */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    /** 日切批量分解业务面（候选查询/分批事务/幂等兜底全归服务层） */
    @Mock
    private IOrderPlanService orderPlanService;

    private OrderPlanDecomposeJob job;

    @BeforeEach
    void setUp() {
        job = new OrderPlanDecomposeJob(orderPlanService);
    }

    @Test
    @DisplayName("日切触发：以次日为计划日期单一委托批量分解（凌晨 02:00 生成次日计划，任务无自有业务逻辑）")
    void decomposeDelegatesToServiceWithNextDayPlanDate() {
        // 期望面必然同步北京钟面（时区纪律专项 A 类修复环）：生产基准日已收敛 HEALTHCARE_TZ，
        // 裸 now() 期望在 CI UTC 深夜窗（北京 00:00-08:00）日期分歧即碎（90c88f5 先例同款）
        LocalDate expected = LocalDate.now(BEIJING_TZ).plusDays(1);
        when(orderPlanService.decomposeNextDay(expected)).thenReturn(42);

        job.decompose();

        // 单一委托调用：任务类禁业务逻辑（A.1-8 同源口径——分批/幂等/候选全归服务层）
        verify(orderPlanService).decomposeNextDay(expected);
        verifyNoMoreInteractions(orderPlanService);
    }

    // ===== 时区纪律专项 A 类：日切计划日北京钟面锚 =====

    @Test
    @DisplayName("日切时区锚：默认时区与北京日期分歧时，分解基准日仍取北京钟面（次日计划不错位整日）")
    void decomposeBindsPlanDateToBeijingClockUnderDivergedDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            // 构造与北京当前日期必然分歧的默认时区：-12h/+14h 二选一（两分歧窗北京钟面 [00:00,20:00)
            // 与 [18:00,24:00) 并集覆盖全天）——BUG-03 先例 setDefault(UTC) 每日 16h 重合窗内对缺陷
            // 代码也绿，本构造任意时刻可复现「非北京时区 JVM 取错医疗日」。时区 ID 必须取偏移字面量
            // （如 -12:00）：java.time 解析不了任意自定义 ID，否则 LocalDate.now() 抛 ZoneRulesException
            Instant now = Instant.now();
            ZoneId beijing = ZoneId.of("Asia/Shanghai");
            int divergeMillis = -12 * 3600_000;
            if (now.atZone(beijing)
                    .toLocalDate()
                    .equals(now.atZone(ZoneOffset.ofTotalSeconds(divergeMillis / 1000))
                            .toLocalDate())) {
                divergeMillis = 14 * 3600_000; // -12h 与北京同日时改用 +14h（引理保证必分歧）
            }
            TimeZone.setDefault(new SimpleTimeZone(
                    divergeMillis,
                    ZoneOffset.ofTotalSeconds(divergeMillis / 1000).getId()));

            job.decompose();

            // 断言面=服务端计算并传入分解服务的计划日（captor 捕获，期望按北京钟面推导禁裸 now()）；
            // 缺陷实现（裸 LocalDate.now()）在分歧默认时区下以容器日期为基准日——凌晨日切把次日
            // 整日计划错位，对北京明日断言即红
            ArgumentCaptor<LocalDate> planDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(orderPlanService).decomposeNextDay(planDateCaptor.capture());
            assertThat(planDateCaptor.getValue())
                    .isEqualTo(LocalDate.now(beijing).plusDays(1));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
