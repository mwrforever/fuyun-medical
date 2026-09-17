package com.fuyun.system.controller;

import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.dto.DictTypeCreateRequest;
import com.fuyun.system.service.IDictTypeService;
import com.fuyun.system.service.IDictVersionService;
import com.fuyun.system.vo.DictTypeVO;
import com.fuyun.system.vo.DictVersionVO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字典类型与版本创建端点（POST /api/v1/system/dict-types[/**]，BRIEF-PR3-01 §3.2）。
 *
 * <p>权限点与 V303 种子 perm_code 对齐（/api/v1/system/dict-types、
 * /api/v1/system/dict-types/{typeCode}/versions）；受 401 认证拦截（API 权限强制 403 属 P1）。
 * 职责边界：仅 @Valid 校验 + 调用 service + 编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system/dict-types")
public class DictTypeController {

    private final IDictTypeService dictTypeService;

    private final IDictVersionService dictVersionService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dictTypeService    字典类型服务，非空；注入接口类型（B.2-2）
     * @param dictVersionService 字典版本服务，非空；版本创建用例
     */
    public DictTypeController(IDictTypeService dictTypeService, IDictVersionService dictVersionService) {
        this.dictTypeService = dictTypeService;
        this.dictVersionService = dictVersionService;
    }

    /**
     * 创建字典类型：typeCode 唯一（重复 409 SYS-1014）。
     *
     * @param request 创建请求，非空；typeCode/typeName 非空与编码格式由 JSR-303 校验
     * @return 类型出参（含雪花 ID，JSON 字符串输出）
     */
    @PostMapping
    @AuditLog(actionType = AuditActionType.WRITE)
    public DictTypeVO createType(@Valid @RequestBody DictTypeCreateRequest request) {
        return dictTypeService.createType(request);
    }

    /**
     * 创建字典草稿版本：版本号同类型内自增，默认 DRAFT。
     *
     * <p>请求无 body（版本元数据由服务端生成：版本号/状态/时间戳均为系统语义，无可入参字段）。
     *
     * @param typeCode 字典类型编码（路径参数），非空
     * @return 版本出参（条目为空清单）
     */
    @PostMapping("/{typeCode}/versions")
    @AuditLog(actionType = AuditActionType.WRITE)
    public DictVersionVO createVersion(@PathVariable("typeCode") String typeCode) {
        return dictVersionService.createVersion(typeCode);
    }
}
