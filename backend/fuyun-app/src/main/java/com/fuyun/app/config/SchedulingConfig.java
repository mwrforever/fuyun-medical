package com.fuyun.app.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务与多实例互斥装配：@Scheduled 任务总开关 + ShedLock JDBC 分布式锁。
 *
 * <p>落地依据：宪法 A.5-14（多实例 @Scheduled 强制配 ShedLock，锁表放公共 schema）；
 * D-2 裁决（2026-09-14）引入 Modulith 后 EventOpsJob 定时重试/清理挂锁运行。
 * usingDbTime()：锁的到期判定使用数据库时钟，规避多实例应用机时钟漂移误释放；
 * defaultLockAtMostFor 兜底持锁上界（任务方法各自的 lockAtMostFor 优先）。
 * 归 app config/（装配域，宪法 B.1 装配模块职责）；JaCoCo 按宪法 C.5-2 排除 config/。
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT60S")
public class SchedulingConfig {

    /**
     * ShedLock JDBC 锁提供器：复用业务数据源（锁表 public.shedlock，V7 迁移建表）。
     *
     * @param jdbcTemplate Spring 自动装配的 JDBC 模板，非空；与业务库同源
     * @return 锁提供器，非空；ShedLock 代理经此存取锁行
     */
    @Bean
    LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build());
    }
}
