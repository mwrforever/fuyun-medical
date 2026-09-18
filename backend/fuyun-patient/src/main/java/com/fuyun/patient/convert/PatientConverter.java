package com.fuyun.patient.convert;

import com.fuyun.patient.entity.CardAccount;
import com.fuyun.patient.entity.CardTxn;
import com.fuyun.patient.entity.HealthItem;
import com.fuyun.patient.entity.HealthSummary;
import com.fuyun.patient.entity.MergeRecord;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.entity.PossibleDuplicate;
import com.fuyun.patient.entity.PrivacyAccessLog;
import com.fuyun.patient.service.impl.PossibleDuplicateServiceImpl;
import com.fuyun.patient.vo.CardAccountVO;
import com.fuyun.patient.vo.CardTxnVO;
import com.fuyun.patient.vo.CardVO;
import com.fuyun.patient.vo.HealthItemVO;
import com.fuyun.patient.vo.HealthSummaryVO;
import com.fuyun.patient.vo.IdentifierVO;
import com.fuyun.patient.vo.MergeRecordVO;
import com.fuyun.patient.vo.PatientVO;
import com.fuyun.patient.vo.PossibleDuplicateVO;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 患者域 MapStruct 转换器（A.7-4）：实体→出参 VO 映射集中点；Task 6 起按需追加映射方法
 * （金额/状态等关键业务字段映射必须手写或单测全覆盖——本域出参均为直映字段）。
 */
@Mapper
public interface PatientConverter {

    /** 实体→档案出参直映（敏感三列不映射——明文只在 service 内解密后经脱敏引擎回填） */
    PatientVO toVO(Patient entity);

    /** 实体清单→出参清单 */
    List<PatientVO> toVOList(List<Patient> entities);

    /** 标识实体→出参（identifierValue 密文/盲索引两列不映射——值禁出接口层） */
    IdentifierVO toVO(PatientIdentifier entity);

    /**
     * 标识实体→就诊卡出参（FU-M02-04；标识值密文/盲索引两列不映射——值禁出接口层）。
     *
     * <p>与 {@link #toVO(PatientIdentifier)} 同源实体双出参，按方法名区分（禁同名重载混淆）。
     *
     * @param entity 标识行实体，非空
     * @return 就诊卡出参（id 映 identifierId）
     */
    @Mapping(source = "id", target = "identifierId")
    CardVO toCardVO(PatientIdentifier entity);

    /** 疑似重复实体→出参直映（审核人/时刻/备注直映，双 id 对无敏感列） */
    PossibleDuplicateVO toVO(PossibleDuplicate entity);

    /**
     * matched_rules 库值→命中规则名清单（终审 Minor 口径统一：MapStruct 属性级映射方法，
     * String→List 无内建转换由此承载；委托 {@link PossibleDuplicateServiceImpl#parseMatchedRules}
     * 单一实现，读侧归一兼容历史 toString 形态与 JSON 数组形态）。
     *
     * @param dbText 库值原文，可空
     * @return 规则名清单，非空
     */
    default List<String> parseMatchedRules(String dbText) {
        return PossibleDuplicateServiceImpl.parseMatchedRules(dbText);
    }

    /** 合并记录实体→出参（preSnapshot 快照全文不映射——审计经库内查询，禁出接口层） */
    MergeRecordVO toVO(MergeRecord entity);

    /** 一卡通账户实体→出参直映（balance 分值直出，审计五列不映射） */
    CardAccountVO toVO(CardAccount entity);

    /** 一卡通流水实体→出参直映（balance_after 对账锚点直出，createdAt 不映射） */
    CardTxnVO toVO(CardTxn entity);

    /** 健康档案聚合实体→摘要出参直映（items 清单调用方回填——record 不可变） */
    HealthSummaryVO toVO(HealthSummary entity);

    /** 健康档案明细实体→出参直映（纠错链 correctOfItemId 直出，留痕展示依据） */
    HealthItemVO toVO(HealthItem entity);

    /** 敏感查阅留痕实体→台账出参直映（createdAt 落库时刻不映射——业务时刻以 occurredAt 为准） */
    PrivacyAccessLogVO toVO(PrivacyAccessLog entity);
}
