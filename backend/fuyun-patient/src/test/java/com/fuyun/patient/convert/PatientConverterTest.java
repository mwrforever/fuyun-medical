package com.fuyun.patient.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.patient.entity.CardAccount;
import com.fuyun.patient.entity.CardTxn;
import com.fuyun.patient.vo.CardAccountVO;
import com.fuyun.patient.vo.CardTxnVO;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * 患者域转换器金额映射单测（A.7-4）：一卡通账户/流水出参的金额字段（balance/amount/balanceAfter）
 * 字段级断言——金额恒 BIGINT 分值制（A.4.2-8 禁浮点），实体/VO 字段名一旦漂移 MapStruct 会静默丢值，
 * 本测试锁定直映契约；BUG-11 补齐（此前 controller 测试经 mock 构造 VO，转换器未被真实行使）。
 */
class PatientConverterTest {

    /** 转换器实例：与 PatientWebConfig 装配同源（Mappers.getMapper 取 MapStruct 生成实现） */
    private final PatientConverter converter = Mappers.getMapper(PatientConverter.class);

    @Test
    @DisplayName("账户出参金额映射：balance 分值原样直出，身份/状态/时刻字段全量等价")
    void toCardAccountVoCarriesBalanceInCents() {
        CardAccount entity = new CardAccount();
        entity.setId(9001L);
        entity.setPatientId(7L);
        entity.setBalance(1234567L);
        entity.setStatus("ACTIVE");
        entity.setOpenedAt(OffsetDateTime.parse("2026-09-01T08:00:00+08:00"));

        CardAccountVO vo = converter.toVO(entity);

        assertThat(vo.getId()).isEqualTo(9001L);
        assertThat(vo.getPatientId()).isEqualTo(7L);
        // 金额契约：余额 1234567 分（=12345.67 元）原样直出，禁换算/截断/默认值
        assertThat(vo.getBalance()).isEqualTo(1234567L);
        assertThat(vo.getStatus()).isEqualTo("ACTIVE");
        assertThat(vo.getOpenedAt()).isEqualTo(OffsetDateTime.parse("2026-09-01T08:00:00+08:00"));
        // 在营账户无销户时刻：null 直传
        assertThat(vo.getClosedAt()).isNull();
    }

    @Test
    @DisplayName("账户余额 null 传播：未入账账户余额保持 null，禁映射为默认 0 分")
    void toCardAccountVoPropagatesNullBalance() {
        CardAccount entity = new CardAccount();
        entity.setId(9002L);
        entity.setBalance(null);

        CardAccountVO vo = converter.toVO(entity);

        // null 余额（未初始化/未知）与 0 分余额（确无资金）是不同业务语义：直传保持 null
        assertThat(vo.getBalance()).isNull();
    }

    @Test
    @DisplayName("流水出参金额映射：amount/balanceAfter 分值原样直出，对账锚点与业务时刻等值")
    void toCardTxnVoCarriesAmountAndBalanceAfterInCents() {
        CardTxn entity = new CardTxn();
        entity.setId(88L);
        entity.setTxnType("RECHARGE");
        entity.setAmount(5000L);
        entity.setBalanceAfter(5000L);
        entity.setBizRef("BILL-1");
        entity.setOccurredAt(OffsetDateTime.parse("2026-09-28T10:15:30+08:00"));

        CardTxnVO vo = converter.toVO(entity);

        assertThat(vo.getId()).isEqualTo(88L);
        assertThat(vo.getTxnType()).isEqualTo("RECHARGE");
        // 金额契约：amount 5000 分（=50.00 元）恒正数，方向由 txn_type 表达
        assertThat(vo.getAmount()).isEqualTo(5000L);
        // balance_after 为记账单语句 RETURNING 原子回读的对账核对锚点：等值直出
        assertThat(vo.getBalanceAfter()).isEqualTo(5000L);
        assertThat(vo.getBizRef()).isEqualTo("BILL-1");
        assertThat(vo.getOccurredAt()).isEqualTo(OffsetDateTime.parse("2026-09-28T10:15:30+08:00"));
    }

    @Test
    @DisplayName("流水大额分值精度：逼近 BIGINT 64 位上限的金额直传无截断")
    void toCardTxnVoPreservesLargeCentAmounts() {
        CardTxn entity = new CardTxn();
        entity.setAmount(Long.MAX_VALUE);
        entity.setBalanceAfter(Long.MAX_VALUE - 1);

        CardTxnVO vo = converter.toVO(entity);

        // Long 直传字段级等价：上限附近分值也必须逐位一致（金额无浮点/无中途窄化）
        assertThat(vo.getAmount()).isEqualTo(Long.MAX_VALUE);
        assertThat(vo.getBalanceAfter()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    @DisplayName("流水金额 null 传播：金额与对账锚点 null 直传，bizRef 可空场景不误造默认值")
    void toCardTxnVoPropagatesNullAmounts() {
        CardTxn entity = new CardTxn();
        entity.setId(90L);
        entity.setAmount(null);
        entity.setBalanceAfter(null);
        entity.setBizRef(null);

        CardTxnVO vo = converter.toVO(entity);

        assertThat(vo.getId()).isEqualTo(90L);
        assertThat(vo.getAmount()).isNull();
        assertThat(vo.getBalanceAfter()).isNull();
        assertThat(vo.getBizRef()).isNull();
    }

    @Test
    @DisplayName("null 实体入参：两映射方法按 MapStruct 约定返回 null 出参（判空责任在调用方）")
    void toVoReturnsNullForNullEntity() {
        assertThat(converter.toVO((CardAccount) null)).isNull();
        assertThat(converter.toVO((CardTxn) null)).isNull();
    }
}
