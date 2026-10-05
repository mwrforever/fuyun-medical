package com.fuyun.outpatient.internal;

import com.fuyun.outpatient.properties.OutpatientProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 号源超时 tick 启动播种器（P2 PR-4E Task 8，W-27，归 internal/ 禁外引；Bean 注册点
 * OutpatientMessagingConfig @Import；nursing TaskOverdueTickSeeder 克隆基准）：应用启动后向
 * delay.appointment-timeout-tick 档位投递首条 tick 心跳帧，点燃自续期链路（此后链路自我维持
 * ——消费后续投，进程存活即有心跳）。幂等口径：档位已有积压帧则跳过（惰性判定经 messageCount
 * 无副作用查询）；多实例同时启动的播种竞争窗口（双双见零双播种）经 TTL 自然对齐无害——多一帧
 * 即扫描周期内多一次幂等扫描（markTimeout CAS 谓词承载，零重复副作用），注记不引入分布式锁。
 * 播种失败不阻断应用启动（error 留痕——MQ 瞬断不应拖垮门诊业务启动，链路可经重启或运维补发重建）。
 * 线程安全：无状态单例。
 */
@Slf4j
public class AppointmentTimeoutTickSeeder implements ApplicationRunner {

    private final AppointmentTimeoutTickSender tickSender;

    private final OutpatientProperties properties;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import）。
     *
     * @param tickSender tick 心跳帧发送器，非空
     * @param properties 门诊域参数，非空；tickSelfRearm 为自续期总开关
     */
    public AppointmentTimeoutTickSeeder(AppointmentTimeoutTickSender tickSender, OutpatientProperties properties) {
        this.tickSender = tickSender;
        this.properties = properties;
    }

    /**
     * 启动播种入口：开关关闭跳过 → 档位积压惰性判定跳过 → 投递首帧点燃链路。
     * 异常吞留痕不阻断启动（见类 javadoc 可用性口径）。
     *
     * @param args 启动参数，非空（本播种器不消费）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.tickSelfRearm()) {
            log.info("号源超时 tick 自续期已关闭，启动播种跳过（tickSelfRearm=false）");
            return;
        }
        try {
            // 幂等守卫：档位已有积压帧（链路在飞）跳过——messageCount 被动查询零消费
            if (tickSender.hasPendingTick()) {
                log.info("号源超时延迟档位已有积压 tick，启动播种跳过（幂等——自续期链路在飞）");
                return;
            }
            tickSender.sendTick();
            log.info("号源超时 tick 首帧已播种（自续期心跳链路点燃）");
        } catch (Exception e) {
            // 播种失败不阻断启动：MQ 瞬断场景链路缺失仅影响超时兜底扫描时效，可经重启/运维补发自愈
            log.error("号源超时 tick 启动播种失败（不阻断应用启动，自愈口径：重启或手工补发）", e);
        }
    }
}
