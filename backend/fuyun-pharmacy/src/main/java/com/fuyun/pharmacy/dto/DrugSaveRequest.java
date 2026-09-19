package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 药品建档/变更入参（POST/PUT /drugs；医保对照字段不入本请求——对照走
 * POST /drugs/{id}/insurance-mapping 独立端点留痕变更类型 MAPPING）。
 *
 * @param drugCode      院内码，必填；来源：药学部维护
 * @param genericName   通用名，必填
 * @param tradeName     商品名，可空
 * @param pinyinCode    拼音码，可空
 * @param dosageForm    剂型，可空
 * @param specification 规格，可空
 * @param manufacturer  生产厂家，可空
 * @param routeCodes    给药途径 code 集（M01 dict），可空集；来源：字典选择
 * @param unit          单位，可空
 * @param splitRatio    拆零换算，可空（string 承载 DECIMAL，D-18 同源）
 * @param essentialFlag 基药标识，必填（false 缺省语义由请求方显式给出）
 * @param antibioClass  抗菌药分级 code（AntibacterialClass），必填
 * @param hazardLevel   高警示等级 code（HazardLevel），必填
 * @param skinTestFlag  皮试标识，必填
 * @param narcoticClass 毒麻类别 code（NarcoticClass），必填
 * @param itemCode      关联 M13 收费项目 code，可空（NULL=不可计费，开方即拒 PH-1006）
 * @param traceCodeType 追溯码类型，可空
 * @param indication    适应证，可空
 * @param maxDose       最大剂量，可空
 * @param contraindication 禁忌，可空
 * @param storageCondition 贮存条件，可空
 */
public record DrugSaveRequest(
        @NotBlank String drugCode,
        @NotBlank String genericName,
        String tradeName,
        String pinyinCode,
        String dosageForm,
        String specification,
        String manufacturer,
        List<String> routeCodes,
        String unit,
        String splitRatio,
        boolean essentialFlag,
        @NotBlank String antibioClass,
        @NotBlank String hazardLevel,
        boolean skinTestFlag,
        @NotBlank String narcoticClass,
        String itemCode,
        String traceCodeType,
        String indication,
        String maxDose,
        String contraindication,
        String storageCondition) {}
