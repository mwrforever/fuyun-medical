package com.fuyun.nursing.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * NursingProperties 配置校验单测（B-2/A.2-2）：TaskOverdue 参数组负值/零值启动期即拒——
 * remindAfterMinutes 负值会令逾期阈值语义反转（未来计划时间被判逾期），契约层校验替代使用点逐处防御。
 *
 * <p>经 ApplicationContextRunner 走真实构造器绑定 + @Validated 校验链（非反射直构造），
 * 参数全部经 fuyun.nursing.task-overdue.* 配置注入（SecurityPropertiesTest 同型先例）；
 * 生产注册面同型（NursingWebConfig @EnableConfigurationProperties）。
 */
class NursingPropertiesValidationTest {

    /** 绑定载体（@Validated 归 NursingProperties 类本体承载，此处仅注册绑定） */
    @Configuration
    @EnableConfigurationProperties(NursingProperties.class)
    static class TestConfig {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("remindAfterMinutes 低于 1：@Min 下界拒绝，启动失败（防逾期阈值语义反转）")
    void remindAfterMinutes低于1时绑定失败() {
        runner.withPropertyValues("fuyun.nursing.task-overdue.remind-after-minutes=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("remindAfterMinutes");
                });
    }

    @Test
    @DisplayName("escalateAfterMinutes 低于 1：@Min 下界拒绝，启动失败（升级链开启阈值不可为零/负）")
    void escalateAfterMinutes低于1时绑定失败() {
        runner.withPropertyValues("fuyun.nursing.task-overdue.escalate-after-minutes=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("escalateAfterMinutes");
                });
    }

    @Test
    @DisplayName("escalateIntervalMinutes 低于 1：@Min 下界拒绝，启动失败（步进为零触发除零守卫依赖）")
    void escalateIntervalMinutes低于1时绑定失败() {
        runner.withPropertyValues("fuyun.nursing.task-overdue.escalate-interval-minutes=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("escalateIntervalMinutes");
                });
    }

    @Test
    @DisplayName("合法值三参数（30/60/30）：绑定成功且参数组取值与注入一致，tickSelfRearm 默认 true")
    void 合法值三参数绑定成功() {
        runner.withPropertyValues(
                        "fuyun.nursing.task-overdue.remind-after-minutes=30",
                        "fuyun.nursing.task-overdue.escalate-after-minutes=60",
                        "fuyun.nursing.task-overdue.escalate-interval-minutes=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NursingProperties.TaskOverdue group =
                            context.getBean(NursingProperties.class).taskOverdue();
                    assertThat(group.remindAfterMinutes()).isEqualTo(30);
                    assertThat(group.escalateAfterMinutes()).isEqualTo(60);
                    assertThat(group.escalateIntervalMinutes()).isEqualTo(30);
                    assertThat(group.tickSelfRearm()).isTrue();
                });
    }
}
