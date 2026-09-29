package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.utils.SensitiveMasker;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.constants.PrivacyConstants;
import com.fuyun.patient.dto.PrivacyMaskRuleUpdateRequest;
import com.fuyun.patient.entity.PrivacyMaskRule;
import com.fuyun.patient.mapper.PrivacyMaskRuleMapper;
import com.fuyun.patient.service.IPrivacyMaskService;
import com.fuyun.patient.vo.PatientVO;
import com.fuyun.patient.vo.PrivacyMaskRuleVO;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隐私脱敏引擎实现：规则行（enabled=TRUE）按 target_field 分派 SensitiveMasker/本地掩码；
 * 豁免判定 = 当前角色清单与规则 exempt_roles（逗号分隔）有交集。规则装载口径（OPT-11 消化
 * 原设计豁免注释）：每请求从库装载、同请求内复用、禁跨请求缓存——管理面变更下轮请求即生效
 * （5 行量级；全量 @Cacheable 属跨请求缓存，须另行评审，禁擅自引入）。
 * 规则维护（listRules/updateRule）同本类承载，落库后下轮请求加载即生效；updateRule 入口
 * 承载 SEC-01 ADMIN 门禁（非 ADMIN 一律 PAT-1024 403 前置拒绝，规则行零触达）。
 * 聚合型服务直用 mapper（A.4.3-20 末句），不设 IService。
 *
 * <p>展示侧口径（审查 I7，待计划审批确认）：applyAll 不做角色豁免——任何角色（含 ADMIN）经列表/详情
 * 恒见脱敏文本；角色豁免仅作用于明文查阅 isExempt（双留痕出口）。此为对 FU-M02-06「按角色豁免展示」
 * 的保守收窄，已在计划范围声明显式登记。
 */
@Slf4j
public class PrivacyMaskServiceImpl implements IPrivacyMaskService {

    /** 脱敏规则维护的管理员角色编码（与 V303 sys_role 种子 ADMIN 对齐，SEC-01 门禁判定依据） */
    private static final String MASK_RULE_ADMIN_ROLE = "ADMIN";

    private final PrivacyMaskRuleMapper ruleMapper;

    /** 全参构造器（装配归 PatientWebConfig @Import） */
    public PrivacyMaskServiceImpl(PrivacyMaskRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /**
     * 清单脱敏（只读）。
     *
     * @param patients 出参清单，非空
     * @return 同引用清单
     */
    @Override
    @Transactional(readOnly = true)
    public List<PatientVO> applyAll(List<PatientVO> patients) {
        List<PrivacyMaskRule> rules = ruleMapper.selectList(null);
        for (PatientVO vo : patients) {
            for (PrivacyMaskRule rule : rules) {
                if (Boolean.TRUE.equals(rule.getEnabled())) {
                    applyRule(vo, rule);
                }
            }
        }
        return patients;
    }

    /**
     * 豁免判定（只读）。
     *
     * @param roles       角色清单，非空
     * @param targetField 目标字段词，非空
     * @return true=豁免
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isExempt(List<String> roles, String targetField) {
        return isExemptAgainst(loadExemptProjection(), roles, targetField);
    }

    /**
     * 批量豁免判定（只读，明文查阅多字段场景）：规则单次装载内存复用，判定面规则
     * 查询数与请求字段数解耦（OPT-11）；逐字段判定口径与 {@link #isExempt} 一致。
     *
     * @param roles        角色清单，非空
     * @param targetFields 目标字段词清单，非空
     * @return 豁免字段词集合（判定为豁免的词去重；空集=无任何字段豁免）
     */
    @Override
    @Transactional(readOnly = true)
    public Set<String> exemptFields(List<String> roles, Collection<String> targetFields) {
        // 规则单次装载内存复用（OPT-11）：判定面查询数与请求字段数解耦，逐字段判定纯内存
        List<PrivacyMaskRule> rules = loadExemptProjection();
        return targetFields.stream()
                .filter(field -> isExemptAgainst(rules, roles, field))
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * 规则清单（只读，GET /privacy-mask-rules 数据源；exemptRoles 拆分清单输出）。
     *
     * @return 规则清单（种子固定 5 行量级），非空
     */
    @Override
    @Transactional(readOnly = true)
    public List<PrivacyMaskRuleVO> listRules() {
        return ruleMapper.selectList(null).stream().map(this::toRuleVO).toList();
    }

    /**
     * 规则维护（部分更新：非空字段覆盖库值；管理面变更经引擎每请求加载即时生效）。SEC-01 安全收口：
     * 仅 ADMIN 角色可维护——规则维护属管理面配置写操作，若任意登录用户可改写 exemptRoles，即可
     * 自授豁免再经 unmask 提权解密，故非 ADMIN 一律 PAT-1024 403 前置拒绝（规则行零触达；
     * 拒绝由审计切面 FAIL 行留痕，与 unmask 403 同模式）。
     *
     * @param ruleCode 规则编码（业务唯一），非空
     * @param request  维护请求（部分更新语义），非空
     * @return 维护后规则出参，非空
     * @throws BizException PAT-1024（403 非 ADMIN 角色，SEC-01 门禁）/ PAT-1021（404 规则编码无命中）
     */
    @Override
    @Transactional
    public PrivacyMaskRuleVO updateRule(String ruleCode, PrivacyMaskRuleUpdateRequest request) {
        // 权限校验：规则维护仅限 ADMIN（角色经认证拦截器注入 RoleContextHolder，V303 种入）；
        // 非 ADMIN 一律 403 前置拒绝，规则行读/写零触达（exemptRoles 不可被非管理员污染）
        if (!RoleContextHolder.get().contains(MASK_RULE_ADMIN_ROLE)) {
            log.warn("脱敏规则维护拒绝（非 ADMIN 角色）：operator={}，ruleCode={}", OperatorContextHolder.get(), ruleCode);
            throw new BizException(
                    PatientErrorCode.PRIVACY_RULE_MAINTENANCE_FORBIDDEN, HttpStatus.FORBIDDEN, "脱敏规则维护仅限系统管理员");
        }
        // 数据库读操作：业务键 rule_code 等值查行（uk 唯一，selectOne 无多行歧义）
        PrivacyMaskRule rule = ruleMapper.selectOne(
                new LambdaQueryWrapper<PrivacyMaskRule>().eq(PrivacyMaskRule::getRuleCode, ruleCode));
        if (rule == null) {
            throw new BizException(PatientErrorCode.PRIVACY_RULE_NOT_FOUND, HttpStatus.NOT_FOUND, "脱敏规则不存在");
        }
        // 部分更新语义：null=不变更；exemptRoles 空串=清空豁免（合法值非「不变更」）
        if (request.maskPattern() != null) {
            rule.setMaskPattern(request.maskPattern());
        }
        if (request.exemptRoles() != null) {
            rule.setExemptRoles(request.exemptRoles());
        }
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        // 数据库写操作：规则行更新（停用/豁免扩容即刻影响展示侧脱敏与明文查阅豁免判定）
        ruleMapper.updateById(rule);
        log.info(
                "脱敏规则维护：ruleCode={}，maskPattern={}，exemptRoles={}，enabled={}",
                ruleCode,
                rule.getMaskPattern(),
                rule.getExemptRoles(),
                rule.getEnabled());
        return toRuleVO(rule);
    }

    /**
     * 规则实体→出参（exemptRoles 逗号分隔库值拆分为结构化清单；空串=无人豁免输出空清单）。
     *
     * @param rule 规则行实体，非空
     * @return 规则出参，非空
     */
    private PrivacyMaskRuleVO toRuleVO(PrivacyMaskRule rule) {
        List<String> exemptRoles =
                rule.getExemptRoles() == null || rule.getExemptRoles().isBlank()
                        ? List.of()
                        : Arrays.stream(rule.getExemptRoles().split(","))
                                .map(String::trim)
                                .toList();
        return new PrivacyMaskRuleVO(
                rule.getRuleCode(), rule.getTargetField(), rule.getMaskPattern(), exemptRoles, rule.getEnabled());
    }

    /** 单行规则应用（target_field 与 vo 属性一一对应；未识别词表跳过并告警，禁静默改错字段） */
    private void applyRule(PatientVO vo, PrivacyMaskRule rule) {
        switch (rule.getTargetField()) {
            case PrivacyConstants.TARGET_NAME -> vo.setName(SensitiveMasker.maskName(vo.getName()));
            case PrivacyConstants.TARGET_ID_CARD_NO -> vo.setIdCardNo(SensitiveMasker.maskIdCard(vo.getIdCardNo()));
            case PrivacyConstants.TARGET_MOBILE -> vo.setMobile(SensitiveMasker.maskPhone(vo.getMobile()));
            case PrivacyConstants.TARGET_ADDRESS -> vo.setAddress(maskAddress(vo.getAddress()));
            case PrivacyConstants.TARGET_BIRTH_DATE -> vo.setBirthDate(maskBirthDate(vo.getBirthDate()));
            default ->
                log.warn("未识别的脱敏目标字段，规则跳过：ruleCode={}，targetField={}", rule.getRuleCode(), rule.getTargetField());
        }
    }

    /** 住址掩码：保留到首个「市/州/盟」止的省市级前缀，其后整段打星（无分隔符时全掩码） */
    private String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return address;
        }
        int cut = -1;
        for (char suffix : new char[] {'市', '州', '盟'}) {
            int idx = address.indexOf(suffix);
            if (idx > 0 && (cut == -1 || idx < cut)) {
                cut = idx;
            }
        }
        if (cut == -1) {
            return "******";
        }
        return address.substring(0, cut + 1) + "******";
    }

    /** 出生日期掩码：保留年份（退化为当年 1 月 1 日），null 透传 */
    private LocalDate maskBirthDate(LocalDate birthDate) {
        return birthDate == null ? null : LocalDate.of(birthDate.getYear(), 1, 1);
    }

    /**
     * 豁免判定规则投影装载：仅取判定实际消费 3 列（target_field/enabled/exempt_roles，
     * A.4.3-14 按需取列），每次调用一次查库、不跨请求缓存（类注释装载口径）。
     */
    private List<PrivacyMaskRule> loadExemptProjection() {
        return ruleMapper.selectList(new LambdaQueryWrapper<PrivacyMaskRule>()
                .select(PrivacyMaskRule::getTargetField, PrivacyMaskRule::getEnabled, PrivacyMaskRule::getExemptRoles));
    }

    /** 规则快照上逐字段豁免判定（词匹配 + 启用过滤 + 角色交集，与原 isExempt 判定体逐字一致） */
    private boolean isExemptAgainst(List<PrivacyMaskRule> rules, List<String> roles, String targetField) {
        return rules.stream()
                .filter(rule -> targetField.equals(rule.getTargetField()))
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .anyMatch(rule -> intersects(roles, rule.getExemptRoles()));
    }

    /** 角色清单与规则豁免集合是否有交集（exempt_roles 逗号分隔，空白安全） */
    private boolean intersects(List<String> roles, String exemptRoles) {
        if (exemptRoles == null || exemptRoles.isBlank() || roles.isEmpty()) {
            return false;
        }
        return Arrays.stream(exemptRoles.split(",")).map(String::trim).anyMatch(roles::contains);
    }
}
