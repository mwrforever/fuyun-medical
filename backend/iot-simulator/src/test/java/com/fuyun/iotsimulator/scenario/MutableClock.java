package com.fuyun.iotsimulator.scenario;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可手动推进的测试时钟（剧本引擎确定性单测专用夹具）：剧本状态机按注入时钟的流逝推进，
 * 测试用例逐段设定流逝时长即可精确复现任一时刻的余量/滴速/阶段——禁止真实时钟进单测
 * （真实时钟流逝量不定，状态机断言失去确定性）。
 */
final class MutableClock extends Clock {

    /** 当前时刻（UTC；advance 系列方法手动推进） */
    private Instant current;

    /**
     * 以初始时刻构造。
     *
     * @param initial 初始时刻，非空；测试取固定基准点保证跨运行回放一致
     */
    MutableClock(Instant initial) {
        this.current = initial;
    }

    /**
     * 时钟前移指定秒数（模拟真实时间的流逝；剧本倍速在剧本侧换算仿真时长）。
     *
     * @param seconds 前移秒数，非负
     */
    void advanceSeconds(long seconds) {
        current = current.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return current;
    }
}
