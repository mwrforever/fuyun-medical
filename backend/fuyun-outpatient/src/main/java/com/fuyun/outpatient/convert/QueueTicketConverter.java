package com.fuyun.outpatient.convert;

import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.vo.QueueTicketVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 候诊票据域 MapStruct 转换器（backend 宪法 A.7-4；BUG-20 迁入 TriageServiceImpl 手写直映）：
 * 票据实体 → 出参 VO 的 11 个票面直映字段集中到此（record 构造器映射按组件名对位生成，字段
 * 增删/调序即编译失败）；patientName/triageLevel 为跨源拼装字段（脱敏展示名来自 patient 侧
 * 掩码收口、分诊级别权威在 visit.triage_level——queue_ticket 表无此列，D-2），仅以标量参数
 * 经 {@code @Mapping} 透传，跨源取数装配（visit 读取+展示名解析）仍留服务层不迁。
 * 可空字段（指派医生/最近叫号时间/分级快照/展示名）保持 null 直传语义，票别/状态枚举同型
 * 直映零换算，QueueTicketConverterTest 全字段等价单测守护。
 *
 * <p>componentModel 取默认（非 spring）：服务侧经 {@link #INSTANCE} 静态获取
 * （AppointmentConverter 同款，宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface QueueTicketConverter {

    /** 默认组件模型的生成实现获取入口（单测与服务调用同源） */
    QueueTicketConverter INSTANCE = Mappers.getMapper(QueueTicketConverter.class);

    /**
     * 票据实体 → 出参直映（11 票面字段 + 2 跨源透传；doctorId/callTime 可空直传——未指派/未叫为
     * null；票据实体无同名属性，patientName/triageLevel 由参数名显式对位无歧义）。
     *
     * @param ticket      票据实体，非空
     * @param triageLevel 分诊级别快照（visit.triage_level），可空（未分级）
     * @param patientName 脱敏展示名（patient 侧掩码收口），可空（无命中）
     * @return 票据出参，非空
     */
    @Mapping(target = "triageLevel", source = "triageLevel")
    @Mapping(target = "patientName", source = "patientName")
    QueueTicketVO toQueueTicketVO(QueueTicket ticket, Integer triageLevel, String patientName);
}
