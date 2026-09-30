package com.fuyun.outpatient.convert;

import com.fuyun.outpatient.entity.Appointment;
import com.fuyun.outpatient.entity.ApptCreditRecord;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.ApptCreditVO;
import com.fuyun.outpatient.vo.VisitVO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 预约域 MapStruct 转换器（backend 宪法 A.7-4；BUG-08 基建 + BUG-09 三段手写直映迁入）：
 * 预约单/爽约信用/就诊记录实体 → 出参 VO 直映集中点——出参均为 record，MapStruct 构造器映射
 * 按组件名对位生成，字段增删/调序即编译失败（替代原手写 14 位位置参数直映的静默错位风险）；
 * 可空字段（支付时限/就诊号/限约区间/解除留痕等）保持 null 直传语义，状态枚举同型直映零换算。
 *
 * <p>componentModel 取默认（非 spring）：服务侧经 {@link #INSTANCE} 静态获取
 * （AuthConverter/IntegrationConverter 同款，宪法 B.1 装配归 app 侧配置）。
 *
 * <p>纪律依据（A.7-4）：本域出参无金额字段（资金无涉红线裁决 7）；状态/渠道/号别枚举同型
 * 直映由 AppointmentConverterTest 全字段单测覆盖。
 */
@Mapper
public interface AppointmentConverter {

    /** 默认组件模型的生成实现获取入口（单测与服务调用同源） */
    AppointmentConverter INSTANCE = Mappers.getMapper(AppointmentConverter.class);

    /**
     * 预约单实体 → 出参直映（14 字段；payDeadline/visitId 可空直传——窗口渠道与取号前为 null）。
     *
     * @param appointment 预约单实体，非空
     * @return 预约单出参，非空
     */
    AppointmentVO toAppointmentVO(Appointment appointment);

    /**
     * 爽约信用记录实体 → 出参直映（8 字段；restrictFrom/restrictTo/releaseReason 可空直传——
     * 未达限约阈值且未解除时均为 null）。
     *
     * @param record 信用记录实体，非空
     * @return 信用记录出参，非空
     */
    ApptCreditVO toCreditVO(ApptCreditRecord record);

    /**
     * 就诊记录实体 → 出参直映（11 字段；apptId/doctorId/triageLevel 可空直传——挂号链路
     * 未分诊/医生未排班时为 null）。
     *
     * @param visit 就诊记录实体，非空
     * @return 就诊记录出参，非空
     */
    VisitVO toVisitVO(Visit visit);
}
