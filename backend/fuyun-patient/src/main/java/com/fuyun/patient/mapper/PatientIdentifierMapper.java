package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.PatientIdentifier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 患者标识注册表 mapper：单表操作经 BaseMapper/IService 链式能力（无 XML，宪法 A.4.3-15），
 * 另声明就诊卡状态机四支 CAS 条件更新（EX-25 读后写收口，fuyun-billing
 * RefundRequestMapper/nursing NursingTaskMapper 同族先例）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface PatientIdentifierMapper extends BaseMapper<PatientIdentifier> {

    /**
     * 无主卡绑定 CAS 条件更新（EX-25）：仅未挂接（patient_id 空/零占位）行可绑定——并发双 bind
     * 同卡恰一赢（本语句行锁上等待，先到者提交后谓词对新行版本重评估不命中），输家 0 行由调用方
     * 重读定性拒，后提交者不得覆写先到者的挂接（丢单链防线）。挂接档案与置 ACTIVE 同语句原子
     * 落库；deleted=0 显式补齐（@TableLogic 仅自动作用于 wrapper，注解 SQL 不继承）。
     *
     * @param id        标识行 id，非空；来源：bind 入口 findByCardNo 读回行主键
     * @param patientId 绑定目标患者主索引，非空；来源：CardBindRequest
     * @return 影响行数（1=抢得绑定权；0=卡已被并发绑定或行不可达，调用方重读定性报错）
     */
    @Update("UPDATE patient.patient_identifier SET status = 'ACTIVE', patient_id = #{patientId} "
            + "WHERE id = #{id} AND (patient_id IS NULL OR patient_id = 0) AND deleted = 0")
    int casBindUnowned(@Param("id") long id, @Param("patientId") long patientId);

    /**
     * 挂失 CAS 条件更新（EX-25）：仅 ACTIVE 可挂失——与解绑/补卡并发交错时 0 行由调用方重读
     * 定性拒（ACTIVE→LOST 单次迁移，后提交者不得以过期快照覆写）。LOST 与解绑时刻同语句原子
     * 落库（时刻取 DB now()，与审计列同源时钟）；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param id 标识行 id，非空；来源：loss 入口 findByCardNo 读回行主键
     * @return 影响行数（1=挂失抢锚成功；0=非 ACTIVE 被并发处理或行不可达，调用方重读定性报错）
     */
    @Update("UPDATE patient.patient_identifier SET status = 'LOST', unbound_at = now() "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND deleted = 0")
    int casMarkLost(@Param("id") long id);

    /**
     * 补卡旧卡退役 CAS 条件更新（EX-25）：仅 LOST 可补——并发双补卡恰一赢，输家 0 行由调用方
     * 重读定性拒；新卡发号严格后置于本锚抢占成功（防重复发号）。REPLACED 终态单列落库；
     * deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param id 旧卡标识行 id，非空；来源：replace 入口 findByCardNo 读回行主键
     * @return 影响行数（1=旧卡退役成功；0=非 LOST 被并发处理或行不可达，调用方重读定性报错）
     */
    @Update("UPDATE patient.patient_identifier SET status = 'REPLACED' "
            + "WHERE id = #{id} AND status = 'LOST' AND deleted = 0")
    int casRetireReplaced(@Param("id") long id);

    /**
     * 解绑 CAS 条件更新（EX-25）：仅 ACTIVE 可解绑——与挂失/补卡并发交错时 0 行由调用方重读
     * 定性拒（ACTIVE→DISABLED 单次迁移）。DISABLED 与解绑时刻同语句原子落库（时刻取 DB now()，
     * 与审计列同源时钟）；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）。
     *
     * @param id 标识行 id，非空；来源：unbind 入口 findByCardNo 读回行主键
     * @return 影响行数（1=解绑抢锚成功；0=非 ACTIVE 被并发处理或行不可达，调用方重读定性报错）
     */
    @Update("UPDATE patient.patient_identifier SET status = 'DISABLED', unbound_at = now() "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND deleted = 0")
    int casDisable(@Param("id") long id);
}
