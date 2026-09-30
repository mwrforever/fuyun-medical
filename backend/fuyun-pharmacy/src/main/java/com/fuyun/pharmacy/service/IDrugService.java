package com.fuyun.pharmacy.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.DrugSaveRequest;
import com.fuyun.pharmacy.dto.InsuranceMappingRequest;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.vo.DrugVO;

/**
 * 药品字典服务（FU-M06-01，P0*）：建档/变更/对照维护/选药检索。
 * 变更留痕广播 pharmacy.drug.changed 的发布接线归 Task 4（PharmacyEventPublisher 在位后回挂）。
 * 配对纪律（宪法 A.4.3-20）：本服务以 drug 为主表（实现侧已 extends ServiceImpl&lt;DrugMapper,
 * Drug&gt;），接口侧对应 extends IService&lt;Drug&gt;——主表通用能力（分页/批量/链式查询等默认
 * 方法集）复用 IService 契约面，属契约面扩展。本接口自有方法（create/update/get/search/
 * mapInsurance）与 IService 默认方法无同名同参冲突（update(long, DrugSaveRequest) 为
 * update(Wrapper) 重载，非覆写）；字典为单表 CRUD 域——status 建档恒 ENABLED 无状态迁移面，
 * 停启用通道未开（P3 药房管理承载），IService 通用写面不承载字典状态语义。
 */
public interface IDrugService extends IService<Drug> {

    /**
     * 药品建档。
     *
     * @param req 建档入参，非空（bean validation 已保必填面）
     * @return 新药品出参（insuredSettleable 恒 false——对照经 mapInsurance 后转 true）
     * @throws BizException PH-1002（409 drug_code 重复）
     */
    DrugVO create(DrugSaveRequest req);

    /**
     * 药品档案变更（全量覆盖语义，对照字段不被本入口覆盖）。
     *
     * @param id  药品 id；来源：字典维护页选行
     * @param req 变更入参，非空
     * @return 变更后出参
     * @throws BizException PH-1001（404 缺行）
     */
    DrugVO update(long id, DrugSaveRequest req);

    /**
     * 药品详情。
     *
     * @param id 药品 id
     * @return 出参
     * @throws BizException PH-1001（404 缺行）
     */
    DrugVO get(long id);

    /**
     * 医保编码对照维护（变更类型 MAPPING，广播 changeType=MAPPING）。
     *
     * @param id  药品 id
     * @param req 对照入参，非空
     * @throws BizException PH-1001（404 缺行）
     */
    void mapInsurance(long id, InsuranceMappingRequest req);

    /**
     * 选药检索（名称/拼音/医保码 keyword 前缀 + 基药/分级/对照过滤，Spec :168；默认仅启用面）。
     *
     * @param keyword         关键词（通用名/商品名/拼音/医保码），可空=不过滤
     * @param essential       基药过滤，可空=不过滤
     * @param antibioClass    抗菌药分级 code 过滤，可空=不过滤
     * @param insuranceMapped 对照过滤（true=已对照），可空=不过滤
     * @param page            0 基页码
     * @param size            页大小
     * @return 分页出参（id 升序唯一序，A.4.3-17）
     */
    PageResult<DrugVO> search(
            String keyword, Boolean essential, String antibioClass, Boolean insuranceMapped, int page, int size);
}
