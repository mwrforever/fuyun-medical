package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.ClinicOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 申请单主单 mapper：单表操作（开单落库/单号定位/在途单据计数）经 BaseMapper 链式能力，另声明状态
 * CAS 注解 SQL——申请单状态机迁移的数据面（Task 8 消费：缴费回执 CREATED→PENDING_FEE、作废
 * CREATED/PENDING_FEE→CANCELLED；CHARGED 扇出随 Task 10 消费）。必须标注 {@code @Mapper}：
 * app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface ClinicOrderMapper extends BaseMapper<ClinicOrder> {

    /**
     * 申请单状态 CAS：影响行数 0=行不存在/并发已迁移/状态违例（调用方重读定性后幂等跳过或拒绝）。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.OrderStatus} code 同源（入参传
     * {@code OrderStatus.XXX.getCode()}）；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；
     * updated_by 固定 'system'（缴费回执为系统驱动迁移，操作者语义不适用于本表）。
     *
     * @param id         申请单主键（clinic_order 表 PK）；来源：按 order_no 定位后取得
     * @param fromStatus 期望迁出态 code（如 CREATED）
     * @param toStatus   目标态 code（如 PENDING_FEE/CANCELLED）
     * @return 影响行数：1=迁移成功；0=行不存在或状态违例/并发落败
     */
    @Update("UPDATE outpatient.clinic_order SET status = #{toStatus}, updated_by = 'system', updated_at = now() "
            + "WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int casStatus(@Param("id") long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /**
     * 缴费放行 CAS（PENDING_FEE→CHARGED + 结算单 id 回填单条原子，Task 10 settlement.completed
     * 消费侧）：按 order_no 定位（billing sourceRef 直取，单据精确——清单经 SettlementQueryPort
     * 反查，禁 visit 全量扫描）；仅待缴费行可放行（fee.created 已先行推进），0 行=重投幂等/并发
     * 已迁移/状态漂移，调用方重读定性。
     *
     * <p>status 字面量与 {@link com.fuyun.outpatient.enums.OrderStatus} code 同源
     * （PENDING_FEE/CHARGED）；deleted=0 显式补齐（注解 SQL 不继承 @TableLogic）；updated_by
     * 固定 'system'（结算回执为系统驱动迁移，操作者语义不适用）。
     *
     * @param orderNo      申请单业务号（结算费用行 source_ref）；来源：SettlementQueryPort 反查 orderRefs
     * @param settlementId 结算单 id（fee_settlement_id 回填锚）；来源：settlement.completed 载荷
     * @return 影响行数：1=放行成功（CHARGED+结算锚已回填）；0=非待缴费/行不存在/并发落败
     */
    @Update("UPDATE outpatient.clinic_order SET status = 'CHARGED', fee_settlement_id = #{settlementId}, "
            + "updated_by = 'system', updated_at = now() "
            + "WHERE order_no = #{orderNo} AND deleted = 0 AND status = 'PENDING_FEE'")
    int casCharge(@Param("orderNo") String orderNo, @Param("settlementId") long settlementId);

    /**
     * 处方引用行缴费 CAS（CREATED→CHARGED，Task 10 settlement.completed 消费侧）：药品费用行由
     * pharmacy.prescription.created 权威携带（不经 outpatient.order.created→fee.created 推进），
     * RX_REF 引用行因此停留 CREATED 态，结算完成后随结算单处方清单直迁 CHARGED（Spec :142/
     * FU-M03-08）。按 ext_ref（=M06 rxNo）精确定位，仅处方引用行（order_type=RX_REF），0 行=重投
     * 幂等/状态漂移，调用方重读定性。
     *
     * <p>status/order_type 字面量与 {@link com.fuyun.outpatient.enums.OrderStatus}/
     * {@link com.fuyun.outpatient.enums.OrderType} code 同源；deleted=0 显式补齐；updated_by
     * 固定 'system'（同 casCharge 系统定性口径）。
     *
     * @param rxNo 处方号（RX_REF 行 ext_ref）；来源：SettlementQueryPort 反查 rxRefs
     * @return 影响行数：1=引用行已迁 CHARGED；0=非 CREATED/行不存在/并发落败
     */
    @Update("UPDATE outpatient.clinic_order SET status = 'CHARGED', updated_by = 'system', updated_at = now() "
            + "WHERE ext_ref = #{rxNo} AND order_type = 'RX_REF' AND deleted = 0 AND status = 'CREATED'")
    int casRxRefCharged(@Param("rxNo") String rxNo);

    /**
     * 处方引用行作废 CAS（CREATED/PENDING_FEE/CHARGED 三态→CANCELLED，Task 10
     * pharmacy.prescription.cancelled 回流驱动）：M06 作废链发起后回流联动本域引用行收敛——
     * 结算完成前后作废的引用行分别处 CREATED/PENDING_FEE/CHARGED 态，三态皆可迁
     * （Spec :119 R2-10 回流驱动）。0 行=重投幂等（已 CANCELLED）/引用行缺失，调用方 info 跳过。
     *
     * <p>status/order_type 字面量与枚举 code 同源；deleted=0 显式补齐；updated_by 固定 'system'。
     *
     * @param rxNo 处方号（RX_REF 行 ext_ref）；来源：pharmacy.prescription.cancelled 载荷
     * @return 影响行数：1=引用行已作废；0=无在迁引用行（幂等达成）
     */
    @Update("UPDATE outpatient.clinic_order SET status = 'CANCELLED', updated_by = 'system', updated_at = now() "
            + "WHERE ext_ref = #{rxNo} AND order_type = 'RX_REF' AND deleted = 0 "
            + "AND status IN ('CREATED', 'PENDING_FEE', 'CHARGED')")
    int casCancelRxRef(@Param("rxNo") String rxNo);

    /**
     * 已发药镜像 CAS（dispense_status 空档→DISPENSED，Task 10 pharmacy.dispense.completed 回流）：
     * 「已发药」为派生展示面（Spec :142 M06 发药回执医生站/患者端可见），引用行状态机五值不变。
     * 仅空档可写（乱序投递下退药镜像先至时不被完成回执回退），0 行=重投幂等/退药镜像先至。
     *
     * <p>dispense_status 字面量与 V203 列注释词表同源（DISPENSED）；deleted=0 显式补齐；
     * updated_by 固定 'system'。
     *
     * @param rxNo 处方号（RX_REF 行 ext_ref）；来源：pharmacy.dispense.completed 载荷
     * @return 影响行数：1=镜像已写 DISPENSED；0=非空档（幂等达成/乱序）
     */
    @Update("UPDATE outpatient.clinic_order SET dispense_status = 'DISPENSED', updated_by = 'system', "
            + "updated_at = now() WHERE ext_ref = #{rxNo} AND order_type = 'RX_REF' AND deleted = 0 "
            + "AND dispense_status IS NULL")
    int casMirrorDispensed(@Param("rxNo") String rxNo);

    /**
     * 退药镜像 CAS（DISPENSED/PART_RETURNED 空档→PART_RETURNED/FULL_RETURNED，Task 10
     * pharmacy.dispense.returned 回流）：fullReturn 载荷组件决定镜像目标（整单=FULL_RETURNED/
     * 部分=PART_RETURNED）。谓词保证镜像单调不回退：FULL_RETURNED 后零回写（后续部分退药不覆盖
     * 整单终态），同值重投 0 行幂等（IS DISTINCT FROM 排除空转）。
     *
     * <p>dispense_status 字面量与 V203 列注释词表同源（PART_RETURNED/FULL_RETURNED）；deleted=0
     * 显式补齐；updated_by 固定 'system'。
     *
     * @param rxNo     处方号（RX_REF 行 ext_ref）；来源：pharmacy.dispense.returned 载荷
     * @param toStatus 镜像目标态（PART_RETURNED/FULL_RETURNED，由载荷 fullReturn 派生）
     * @return 影响行数：1=镜像已写目标态；0=整单终态后回写/同值重投（幂等达成）
     */
    @Update("UPDATE outpatient.clinic_order SET dispense_status = #{toStatus}, updated_by = 'system', "
            + "updated_at = now() WHERE ext_ref = #{rxNo} AND order_type = 'RX_REF' AND deleted = 0 "
            + "AND (dispense_status IS NULL OR dispense_status <> 'FULL_RETURNED') "
            + "AND dispense_status IS DISTINCT FROM #{toStatus}")
    int casMirrorReturned(@Param("rxNo") String rxNo, @Param("toStatus") String toStatus);
}
