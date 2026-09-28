package com.fuyun.ward.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import com.fuyun.ward.dto.RegisterColdChainRecordRequest;
import com.fuyun.ward.dto.SaveColdChainArchiveRequest;
import com.fuyun.ward.enums.ColdChainPurpose;
import com.fuyun.ward.service.IColdChainService;
import com.fuyun.ward.vo.ColdChainArchiveVO;
import com.fuyun.ward.vo.ColdChainRecordVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 冷链端点（/api/v1/ward/cold-chain/archives 七端点，FU-M16 冷链合规台账面）：档案 CRUD、
 * 三类型记录登记（巡检/告警处置/偏差共用入口，ALARM_HANDLE 处置完成发布归档事件）、记录列表。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用冷链服务 + 编排响应；ALARM_HANDLE 双人
 * 校验与事件发布归服务层；写操作挂 WRITE 审计；overdue 注记随读路径惰性判定。装配归
 * fuyun-app WardConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/ward/cold-chain/archives")
public class ColdChainController {

    /** 冷链服务：七端点唯一业务出口 */
    private final IColdChainService coldChainService;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param coldChainService 冷链服务，非空
     */
    public ColdChainController(IColdChainService coldChainService) {
        this.coldChainService = coldChainService;
    }

    /**
     * 建档（POST /api/v1/ward/cold-chain/archives；WRITE 审计）。
     *
     * @param request 保存请求体（@Valid），非空
     * @return 建档后视图；200
     */
    @PostMapping
    @AuditLog(actionType = AuditActionType.WRITE)
    public ColdChainArchiveVO create(@Valid @RequestBody SaveColdChainArchiveRequest request) {
        return coldChainService.createArchive(request);
    }

    /**
     * 档案分页（GET /api/v1/ward/cold-chain/archives；用途过滤，overdue 注记随行）。
     *
     * @param purpose 用途过滤（可空=不过滤）
     * @param page    页码（0 基），缺省 0
     * @param size    单页条数（1-200），缺省 20
     * @return 分页出参；200
     */
    @GetMapping
    public PageResult<ColdChainArchiveVO> page(
            @RequestParam(required = false) ColdChainPurpose purpose,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page 不能为负") int page,
            @RequestParam(defaultValue = "20")
                    @Min(value = 1, message = "size 最小为 1")
                    @Max(value = 200, message = "size 最大为 200")
                    int size) {
        return coldChainService.pageArchives(purpose, page, size);
    }

    /**
     * 档案详情（GET /api/v1/ward/cold-chain/archives/{archiveNo}；overdue 注记随行）。
     *
     * @param archiveNo 档案业务号（路径变量）
     * @return 档案视图；200
     * @throws com.fuyun.common.exception.BizException WD-1004（404）
     */
    @GetMapping("/{archiveNo}")
    public ColdChainArchiveVO get(@PathVariable String archiveNo) {
        return coldChainService.getArchive(archiveNo);
    }

    /**
     * 更新档案（PUT /api/v1/ward/cold-chain/archives/{archiveNo}；WRITE 审计）。
     *
     * @param archiveNo 档案业务号（路径变量）
     * @param request   保存请求体（@Valid），非空
     * @return 更新后视图；200
     * @throws com.fuyun.common.exception.BizException WD-1004（404）
     */
    @PutMapping("/{archiveNo}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ColdChainArchiveVO update(
            @PathVariable String archiveNo, @Valid @RequestBody SaveColdChainArchiveRequest request) {
        return coldChainService.updateArchive(archiveNo, request);
    }

    /**
     * 删除档案（DELETE /api/v1/ward/cold-chain/archives/{archiveNo}；逻辑删；WRITE 审计）。
     *
     * @param archiveNo 档案业务号（路径变量）
     * @throws com.fuyun.common.exception.BizException WD-1004（404）
     */
    @DeleteMapping("/{archiveNo}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void delete(@PathVariable String archiveNo) {
        coldChainService.deleteArchive(archiveNo);
    }

    /**
     * 记录登记（POST /api/v1/ward/cold-chain/archives/{archiveNo}/records；WRITE 审计）：
     * INSPECTION 巡检登记 / ALARM_HANDLE 告警处置（必填 alarm_ref+second_operator，登记完成
     * 发布 ward.cold-chain.alert-archived）/ DEVIATION 偏差登记共用入口。
     *
     * @param archiveNo 档案业务号（路径变量）
     * @param request   登记请求体（@Valid），非空
     * @return 登记后记录视图；200
     * @throws com.fuyun.common.exception.BizException WD-1004（404）或 WD-1005（400）
     */
    @PostMapping("/{archiveNo}/records")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ColdChainRecordVO registerRecord(
            @PathVariable String archiveNo, @Valid @RequestBody RegisterColdChainRecordRequest request) {
        return coldChainService.registerRecord(archiveNo, request);
    }

    /**
     * 记录列表（GET /api/v1/ward/cold-chain/archives/{archiveNo}/records；登记时刻倒序）。
     *
     * @param archiveNo 档案业务号（路径变量）
     * @return 记录视图清单；200
     * @throws com.fuyun.common.exception.BizException WD-1004（404）
     */
    @GetMapping("/{archiveNo}/records")
    public List<ColdChainRecordVO> records(@PathVariable String archiveNo) {
        return coldChainService.listRecords(archiveNo);
    }
}
