package com.fuyun.outpatient.convert;

import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.ScheduleTemplate;
import com.fuyun.outpatient.vo.ScheduleTemplateVO;
import com.fuyun.outpatient.vo.ScheduleVO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 排班域 MapStruct 转换器（backend 宪法 A.7-4；BUG-22 迁入 ScheduleServiceImpl 两段手写直映）：
 * 排班模板/排班日历实体 → 出参 VO 直映集中点——record 构造器映射按组件名对位生成，字段增删/
 * 调序即编译失败（模板加停诊标记等字段演进时出参漏映射由编译期拦截，替代原手写 15/12 位
 * 位置参数直映的静默错位风险）；可空字段（失效日/诊疗室/放号时点/停诊原因等）保持 null 直传
 * 语义，时段/号别/状态枚举与模板 status 字符串同型直映零换算。放号生成的 Entity←Entity 装配块
 * （模板→日历行）属持久化边界非出参投影，维持服务层现状不迁。
 *
 * <p>componentModel 取默认（非 spring）：服务侧经 {@link #INSTANCE} 静态获取
 * （AppointmentConverter 同款，宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface ScheduleConverter {

    /** 默认组件模型的生成实现获取入口（单测与服务调用同源） */
    ScheduleConverter INSTANCE = Mappers.getMapper(ScheduleConverter.class);

    /**
     * 排班模板实体 → 出参直映（15 字段；effTo/room/releaseTime 可空直传——长期有效/未配置时为
     * null）。
     *
     * @param template 排班模板实体，非空
     * @return 模板出参，非空
     */
    ScheduleTemplateVO toTemplateVO(ScheduleTemplate template);

    /**
     * 排班日历实体 → 出参直映（12 字段；room/stopReason 可空直传——未配置诊疗室/未停诊时为
     * null）。
     *
     * @param schedule 排班日历实体，非空
     * @return 排班出参，非空
     */
    ScheduleVO toScheduleVO(Schedule schedule);
}
