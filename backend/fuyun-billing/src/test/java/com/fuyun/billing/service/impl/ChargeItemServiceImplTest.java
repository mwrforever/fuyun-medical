package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.ChargeItemCreateRequest;
import com.fuyun.billing.dto.ComboComponentRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.entity.ChargeItemComponent;
import com.fuyun.billing.enums.ItemClass;
import com.fuyun.billing.enums.ItemPriceFlag;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.mapper.ChargeItemComponentMapper;
import com.fuyun.billing.mapper.ChargeItemMapper;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 收费项目管理单测：编码唯一（uk 前置拒重）+ 按码取生效项守卫（不存在/停用）+ 组合构成落库。 */
@ExtendWith(MockitoExtension.class)
class ChargeItemServiceImplTest {

    @Mock
    private ChargeItemMapper chargeItemMapper;

    @Mock
    private ChargeItemComponentMapper componentMapper;

    private ChargeItemServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChargeItem.class);
        // 组合成员 wrapper 断言（combo_item_id 列解析）依赖本实体 TableInfo，与主表一并初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChargeItemComponent.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChargeItemServiceImpl(componentMapper);
        ReflectionTestUtils.setField(service, "baseMapper", chargeItemMapper);
        ReflectionTestUtils.setField(service, "entityClass", ChargeItem.class);
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表） */
    private LambdaQueryWrapper<ChargeItem> rendered(Wrapper<ChargeItem> captured) {
        LambdaQueryWrapper<ChargeItem> wrapper = (LambdaQueryWrapper<ChargeItem>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    private ChargeItemCreateRequest req(String code) {
        // DTO record 组件序：itemCode, itemName, itemClass, unit, execDeptId, comboFlag, feeCategory
        return new ChargeItemCreateRequest(code, "血常规", ItemClass.TREATMENT, "次", null, false, "LAB_FEE");
    }

    @Test
    @DisplayName("新建项目：编码未占用则落库 ACTIVE/SINGLE 默认并回填 id")
    void createChargeItemPersistsActiveSingleItem() {
        when(chargeItemMapper.selectOne(any())).thenReturn(null);
        // 模拟 MP ASSIGN_ID 回填（Task 11 引擎测试同款范式）：不桩则 save 后 item.getId() 为 null，
        //   `return item.getId()` long 拆箱 NPE——回填语义本身即本用例断言对象
        when(chargeItemMapper.insert(any(ChargeItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChargeItem.class).setId(1L);
            return 1;
        });

        long id = service.createChargeItem(req("C001"));
        assertThat(id).isEqualTo(1L);

        ArgumentCaptor<ChargeItem> captor = ArgumentCaptor.forClass(ChargeItem.class);
        verify(chargeItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(captor.getValue().getPriceFlag()).isEqualTo(ItemPriceFlag.SINGLE);
    }

    @Test
    @DisplayName("新建项目：编码重复前置拒 BILL-1002（不依赖唯一索引异常）")
    void createRejectsDuplicateCode() {
        when(chargeItemMapper.selectOne(any())).thenReturn(new ChargeItem());

        assertThatThrownBy(() -> service.createChargeItem(req("C001")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_ITEM_CODE_EXISTS));
        // 查重 SQL 守卫钉死：等值条件必须落在 item_code 列且携带本次编码（防查重条件被静默删除）
        ArgumentCaptor<Wrapper<ChargeItem>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chargeItemMapper).selectOne(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItem> wrapper = rendered(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("item_code");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("C001");
        verify(chargeItemMapper, never()).insert(any(ChargeItem.class));
    }

    @Test
    @DisplayName("新建项目：comboFlag=TRUE 时收费标记落 COMBO_ONLY（组合划价展开前提）")
    void createChargeItemMarksComboProjectAsComboOnly() {
        when(chargeItemMapper.selectOne(any())).thenReturn(null);
        when(chargeItemMapper.insert(any(ChargeItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChargeItem.class).setId(2L);
            return 1;
        });
        // DTO record 组件序：itemCode, itemName, itemClass, unit, execDeptId, comboFlag, feeCategory
        ChargeItemCreateRequest comboReq =
                new ChargeItemCreateRequest("C900", "静脉输液组", ItemClass.TREATMENT, "组", null, true, "TREAT_FEE");

        assertThat(service.createChargeItem(comboReq)).isEqualTo(2L);

        ArgumentCaptor<ChargeItem> captor = ArgumentCaptor.forClass(ChargeItem.class);
        verify(chargeItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getPriceFlag()).isEqualTo(ItemPriceFlag.COMBO_ONLY);
        assertThat(captor.getValue().getComboFlag()).isTrue();
    }

    @Test
    @DisplayName("按码取生效项：ACTIVE 项目原样返回（计价引擎取项成功路径）")
    void requireActiveByCodeReturnsActiveItem() {
        ChargeItem active = new ChargeItem();
        active.setId(7L);
        active.setItemCode("C001");
        active.setStatus(ItemStatus.ACTIVE);
        when(chargeItemMapper.selectOne(any())).thenReturn(active);

        assertThat(service.requireActiveByCode("C001")).isSameAs(active);

        // 取项 SQL 守卫钉死：必须按 item_code 等值查询（Task 11 引擎取项入口防静默改全表取首行）
        ArgumentCaptor<Wrapper<ChargeItem>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chargeItemMapper).selectOne(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItem> wrapper = rendered(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("item_code");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("C001");
    }

    @Test
    @DisplayName("按码取生效项：停用项目拒 BILL-1003、缺项拒 BILL-1001（两分支双断言，第 2 轮审查 P1-3 改正文补名实相符）")
    void requireActiveGuardsStateAndPresence() {
        ChargeItem inactive = new ChargeItem();
        inactive.setStatus(ItemStatus.INACTIVE);
        // 同一 stub 链变参依次命中：第一次返停用行→BILL-1003，第二次返 null→缺项 BILL-1001
        when(chargeItemMapper.selectOne(any())).thenReturn(inactive, (ChargeItem) null);
        assertThatThrownBy(() -> service.requireActiveByCode("C001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_ITEM_STATE_NOT_ALLOWED));
        assertThatThrownBy(() -> service.requireActiveByCode("C002"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_ITEM_NOT_FOUND));
        // 两次取项均按 item_code 等值过滤（INACTIVE/缺项守卫建立在按码命中的前提上）
        ArgumentCaptor<Wrapper<ChargeItem>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chargeItemMapper, times(2)).selectOne(wrapperCaptor.capture());
        for (Wrapper<ChargeItem> captured : wrapperCaptor.getAllValues()) {
            assertThat(rendered(captured).getSqlSegment()).contains("item_code");
        }
    }

    @Test
    @DisplayName("组合构成维护：非组合项目拒 BILL-1003 且成员表零写；组合项全量覆盖落成员（两分支一用例）")
    void saveComboPersistsMembersAndGuardsNonCombo() {
        ChargeItem nonCombo = new ChargeItem();
        nonCombo.setComboFlag(false);
        ChargeItem combo = new ChargeItem();
        combo.setComboFlag(true);
        // 同一 stub 链变参依次命中：第一次非组合→拒 BILL-1003，第二次组合→删旧插新落成员
        when(chargeItemMapper.selectById(9L)).thenReturn(nonCombo, combo);
        List<ComboComponentRequest> members = List.of(new ComboComponentRequest(12L, new BigDecimal("2.000")));

        assertThatThrownBy(() -> service.saveComboComponents(9L, members))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_ITEM_STATE_NOT_ALLOWED));
        verify(componentMapper, never()).delete(any());

        service.saveComboComponents(9L, members);

        // 全量覆盖式落成员：先逻辑删旧成员，再逐成员插入
        // 删旧 SQL 守卫钉死：delete 必须限定 combo_item_id=本次组合（防删旧条件被静默删除放大为全表逻辑删）
        ArgumentCaptor<Wrapper<ChargeItemComponent>> deleteCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(componentMapper).delete(deleteCaptor.capture());
        LambdaQueryWrapper<ChargeItemComponent> deleteWrapper =
                (LambdaQueryWrapper<ChargeItemComponent>) deleteCaptor.getValue();
        // 先渲染 SQL 片段：MP 条件参数在 getSqlSegment 惰性求值时才写入 paramNameValuePairs（模块内既有同款）
        assertThat(deleteWrapper.getSqlSegment()).contains("combo_item_id");
        assertThat(deleteWrapper.getParamNameValuePairs().values()).contains(9L);
        ArgumentCaptor<ChargeItemComponent> captor = ArgumentCaptor.forClass(ChargeItemComponent.class);
        verify(componentMapper).insert(captor.capture());
        assertThat(captor.getValue().getComboItemId()).isEqualTo(9L);
        assertThat(captor.getValue().getComponentItemId()).isEqualTo(12L);
        assertThat(captor.getValue().getDefaultQuantity()).isEqualByComparingTo(new BigDecimal("2.000"));
    }

    @Test
    @DisplayName("组合成员查询：按 comboItemId 返回成员清单（划价展开消费入口）")
    void listComponentsReturnsMembers() {
        ChargeItemComponent first = new ChargeItemComponent();
        first.setComboItemId(9L);
        first.setComponentItemId(12L);
        ChargeItemComponent second = new ChargeItemComponent();
        second.setComboItemId(9L);
        second.setComponentItemId(13L);
        when(componentMapper.selectList(any())).thenReturn(List.of(first, second));

        assertThat(service.listComponents(9L)).containsExactly(first, second);

        // 展开查询 SQL 守卫钉死：必须限定 combo_item_id=本次组合（防全量拉取误当成员清单）
        ArgumentCaptor<Wrapper<ChargeItemComponent>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(componentMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItemComponent> wrapper =
                (LambdaQueryWrapper<ChargeItemComponent>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("combo_item_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(9L);
    }
}
