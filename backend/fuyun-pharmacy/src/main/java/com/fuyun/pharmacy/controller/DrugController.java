package com.fuyun.pharmacy.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.DrugSaveRequest;
import com.fuyun.pharmacy.dto.InsuranceMappingRequest;
import com.fuyun.pharmacy.service.IDrugService;
import com.fuyun.pharmacy.vo.DrugVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药品字典端点（/api/v1/pharmacy/drugs，Spec :168 节选）：建档/变更/详情/对照/选药检索。
 * 调用方：药学部维护台（本 PR workstation DrugDictView）与 M03/M04/M05/M18 选药场景。
 */
@Tag(name = "药品字典")
@RestController
@RequestMapping("/api/v1/pharmacy/drugs")
@RequiredArgsConstructor
public class DrugController {

    private final IDrugService drugService;

    /**
     * 药品建档。
     *
     * @param req 建档入参，非空
     * @return 新药品出参
     */
    @Operation(summary = "药品建档")
    @PostMapping
    @AuditLog(actionType = AuditActionType.WRITE)
    public DrugVO create(@Valid @RequestBody DrugSaveRequest req) {
        return drugService.create(req);
    }

    /**
     * 药品档案变更。
     *
     * @param id  药品 id（路径参数）
     * @param req 变更入参，非空
     * @return 变更后出参
     */
    @Operation(summary = "药品变更")
    @PutMapping("/{id}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public DrugVO update(@PathVariable long id, @Valid @RequestBody DrugSaveRequest req) {
        return drugService.update(id, req);
    }

    /**
     * 药品详情。
     *
     * @param id 药品 id（路径参数）
     * @return 出参
     */
    @Operation(summary = "药品详情")
    @GetMapping("/{id}")
    public DrugVO get(@PathVariable long id) {
        return drugService.get(id);
    }

    /**
     * 医保编码对照维护（变更类型 MAPPING 广播）。
     *
     * @param id  药品 id（路径参数）
     * @param req 对照入参，非空
     */
    @Operation(summary = "医保编码对照")
    @PostMapping("/{id}/insurance-mapping")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void mapInsurance(@PathVariable long id, @Valid @RequestBody InsuranceMappingRequest req) {
        drugService.mapInsurance(id, req);
    }

    /**
     * 选药检索（名称/拼音/医保码/基药/分级过滤；默认启用面）。
     *
     * @param keyword         关键词，可空
     * @param essential       基药过滤，可空
     * @param antibioClass    抗菌药分级过滤，可空
     * @param insuranceMapped 对照过滤，可空
     * @param page            0 基页码，缺省 0
     * @param size            页大小，缺省 20
     * @return 分页出参
     */
    @Operation(summary = "选药检索")
    @GetMapping("/search")
    public PageResult<DrugVO> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean essential,
            @RequestParam(required = false) String antibioClass,
            @RequestParam(required = false) Boolean insuranceMapped,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return drugService.search(keyword, essential, antibioClass, insuranceMapped, page, size);
    }
}
