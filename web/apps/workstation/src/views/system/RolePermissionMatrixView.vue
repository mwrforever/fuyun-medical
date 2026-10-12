<script setup lang="ts">
/**
 * 权限管理台（PR-4F F8）：角色 × 权限矩阵编辑器（暖纸卷宗换脸重排 戊-1，「换脸不换业务」）。
 *
 * <p>暖纸收编（蓝图戊-1）：本页为全站唯一裸页，本次补挂 .fuy-page（纵向单列）+ 门牌页首
 * （衬线标题/英文小注/批注行——原 desc 一句升批注，原文案锚点逐字保留）+「已选 x/y」墨底
 * 徽标状态位；双层 tabs 挂 .fuy-tabs 卷宗签语法（激活签朱砂签线为装饰走线，内层 card 型
 * 保留但色承全局 token 映射）；角色信息条=纸面卡；首屏失败 el-empty 兜底替换为 .fuy-empty
 * 错误态变体（说问题给恢复+重新加载墨实底钮，错误不得伪装空态）；ADMIN 只读注记 el-alert
 * 替换为金洗底批注条（--fuy-palette-brand-100 金线装订同源洗底+浓墨字，info 语义洗底非
 * 状态色）；保存成功=钤印「已保存」（v-if 挂载制，契约 ⑧.5）+ F4③ message 批注条文案
 * 逐字冻结。矩阵区勾选 grid 排布，勾选 cell 双行化（名称行+码行纵排，EP checkbox
 * nowrap 基样式在 240px 列内会横向溢出压邻列——琢段修复），权限码辅注灰墨 .fuy-num
 * （审计核对码本体）。
 *
 * <p>业务语义零变动：双层 tab 结构——外层角色 tab（每角色一页，含启停开关旁置）× 内层
 * 类型 tab（API/MENU/ELEMENT 三组，ELEMENT 组内按权限码首段域前缀细分组展示）。勾选态为
 * 各类型独立 checkbox-group 本地编辑态，保存即调 PUT 全量覆写（F4 语义：载荷=该角色
 * 目标态全部权限码，非增量 diff），成功后提示各实例 403 矩阵已重载、相关用户需重新
 * 登录生效（后端事务内广播 system.permission.changed 触发各实例 Registry 重载）。
 *
 * <p>ADMIN 角色特殊渲染（D3）：permCodes 恒空清单（无绑定行——运行期全放由 403
 * 拦截器超管短路承载），矩阵区只读 + 注记说明，禁用启停开关（停用超管将锁死管理面）。
 *
 * <p>清单外码防御：角色既有绑定码若不在权限点分组清单内（历史码/清单漂移），保存时
 * 原样带回载荷，防全量覆写静默丢弃存量绑定（覆写语义下前端无权默删后端数据）。
 *
 * <p>页面访问由路由 meta.permission='system:permission:manage' 守卫（Task 8 通道），
 * 页内不重复做权限判定；数据经 api/system 出网（http 单例，A.3-1）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listPermissions, listRoles, updateRolePermissions, updateRoleStatus } from '@/api/system';
import type { PermissionGroupVO, RoleAdminVO } from '@/api/system';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();

/* ---------- 门牌批注行：签认人（会话身份）· 时刻（本地时钟零出网，出院管理页同构先例） ---------- */
/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 当日批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ==================== 钤印确认反馈（契约 ⑧.5） ==================== */
/** 钤印文案（动词结果「已保存」；失败/业务拒绝走 message 批注条不盖钤印） */
const stampText = ref('');
/** 钤印可见态（v-if 挂载制：常驻形态会被级联/过渡误伤为常显空朱块，出院管理页同款口径） */
const stampVisible = ref(false);
/** 钤印重挂序号：递增强制重挂节点重放 fuy-stamp-in 落章动画 */
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印并 ≤1200ms 收场（动画收尾后 v-if 卸载，浮层不驻留遮挡矩阵工作面） */
function showStamp(text: string): void {
  stampText.value = text;
  stampSeq.value += 1;
  stampVisible.value = true;
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
  stampTimer = setTimeout(() => {
    stampVisible.value = false;
    stampTimer = null;
  }, 1200);
}

onUnmounted(() => {
  // 收场定时器仅翻转内部展示 ref（零 DOM/出网副作用），卸载清理防迟触发
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
    stampTimer = null;
  }
});

/** 权限类型 tab 序（展示序即数组序；permType 与后端 PermissionType code 同名） */
const PERM_TYPE_TABS = ['API', 'MENU', 'ELEMENT'] as const;
type PermType = (typeof PERM_TYPE_TABS)[number];

/** 权限点明细（生成物嵌套结构别名：分组内一行=一权限码） */
interface PermPoint {
  permCode?: string;
  permName?: string;
}

/** 域细分组视图行（ELEMENT 类型专用：码首段 split(':')[0] 分桶） */
interface DomainBucket {
  domain: string;
  points: PermPoint[];
}

/** 各类型本地勾选态（checkbox-group 逐类型独立 model：不渲染的码不被组更新丢弃） */
interface SelectionByType {
  API: string[];
  MENU: string[];
  ELEMENT: string[];
}

/** 角色清单（含各角色绑定码集；ADMIN 行 permCodes=空清单属正常语义） */
const roles = ref<RoleAdminVO[]>([]);
/** 权限点分组清单（矩阵列全集） */
const groups = ref<PermissionGroupVO[]>([]);
/** 首屏加载失败态（任一读端点失败即整页错误态兜底 + 恢复入口） */
const loadFailed = ref(false);
const loading = ref(false);
/** 外层角色 tab 激活码；'' = 未激活（加载前） */
const activeRoleCode = ref('');
/** 内层类型 tab 激活态（跨角色切换保持，管理员的浏览习惯连续） */
const activeType = ref<PermType>('API');
/** 各角色勾选态（数组序与 roles 对齐；角色间编辑态相互独立——EP tab-pane 全量渲染
 * DOM，共享单一状态会让非激活 pane 的勾选态互相污染） */
const selectionByRole = ref<SelectionByType[]>([]);
/** 保存出网在途标志（防双击双 PUT） */
const saving = ref(false);

/** 全量权限点按类型分桶（组内点序=后端返回序，即种子登记序） */
const pointsByType = computed<Map<PermType, PermPoint[]>>(() => {
  const map = new Map<PermType, PermPoint[]>();
  for (const tab of PERM_TYPE_TABS) {
    const group = groups.value.find((item) => item.permType === tab);
    map.set(tab, group?.points ?? []);
  }
  return map;
});

/** 全量权限码集合（交集初始化与清单外码识别共用） */
const allPermCodes = computed<Set<string>>(() => {
  const codes = new Set<string>();
  for (const points of pointsByType.value.values()) {
    for (const point of points) {
      if (point.permCode !== undefined) {
        codes.add(point.permCode);
      }
    }
  }
  return codes;
});

/** ELEMENT 类型的域细分组（码首段分桶，桶序=码首次出现序；其余类型平铺不走此结构） */
const elementBuckets = computed<DomainBucket[]>(() => {
  const bucketMap = new Map<string, PermPoint[]>();
  for (const point of pointsByType.value.get('ELEMENT') ?? []) {
    if (point.permCode === undefined) {
      continue;
    }
    const domain = point.permCode.split(':')[0] ?? '';
    const bucket = bucketMap.get(domain);
    if (bucket !== undefined) {
      bucket.push(point);
    } else {
      bucketMap.set(domain, [point]);
    }
  }
  return [...bucketMap.entries()].map(([domain, points]) => ({ domain, points }));
});

/** 当前激活角色在 roles 中的序（selectionByRole 同序对齐的索引桥） */
const activeRoleIndex = computed<number>(() =>
  roles.value.findIndex((role) => role.roleCode === activeRoleCode.value),
);

/** 当前激活角色勾选态（门牌徽标统计与保存载荷的读取面；越界兜底空态防渲染期闪烁） */
const activeSelection = computed<SelectionByType>(
  () => selectionByRole.value[activeRoleIndex.value] ?? { API: [], MENU: [], ELEMENT: [] },
);

/** 当前角色勾选总数（三类型并集，门牌「已选 x/y」徽标与角色信息条统计口径） */
const selectedCount = computed<number>(
  () =>
    activeSelection.value.API.length +
    activeSelection.value.MENU.length +
    activeSelection.value.ELEMENT.length,
);

/**
 * 构造角色勾选态：既有绑定码（permCodes）与各类型组码集求交集分桶——
 * 不在任何组内的码（清单外历史绑定）不进勾选态，保存时由清单外码补带回。
 *
 * @param role 目标角色，非空
 * @return 该角色三类型勾选态
 */
function buildSelection(role: RoleAdminVO): SelectionByType {
  const bound = role.permCodes ?? [];
  const apiCodes = new Set((pointsByType.value.get('API') ?? []).map((p) => p.permCode));
  const menuCodes = new Set((pointsByType.value.get('MENU') ?? []).map((p) => p.permCode));
  const elementCodes = new Set((pointsByType.value.get('ELEMENT') ?? []).map((p) => p.permCode));
  return {
    API: bound.filter((code) => apiCodes.has(code)),
    MENU: bound.filter((code) => menuCodes.has(code)),
    ELEMENT: bound.filter((code) => elementCodes.has(code)),
  };
}

/**
 * 首屏加载：角色清单 + 权限点分组并行读取；任一失败整页错误态兜底（恢复入口），
 * 成功后逐角色初始化独立勾选态（与 roles 同序对齐）并激活首个角色 tab。
 */
async function loadAll(): Promise<void> {
  loading.value = true;
  loadFailed.value = false;
  try {
    const [roleList, permissionGroups] = await Promise.all([listRoles(), listPermissions()]);
    roles.value = roleList;
    groups.value = permissionGroups;
    // 各角色勾选态独立初始化（切角色不重置：多角色对比编辑的未保存勾选得以保留）
    selectionByRole.value = roleList.map((role) => buildSelection(role));
    activeRoleCode.value = roleList[0]?.roleCode ?? '';
  } catch {
    // 读端点失败：拦截器已统一弹错，本地落错误态渲染恢复入口（不崩不白屏）
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

/**
 * 保存当前角色矩阵：三类型勾选并集 + 清单外码原样带回 → PUT 全量覆写（F4）。
 * 成功后以响应终态回写角色行与该角色勾选态，盖钤印「已保存」（动词结果确认）并以
 * message 批注条提示矩阵重载/重登录语义（F4③ 文案逐字契约）。
 */
async function saveMatrix(): Promise<void> {
  const index = activeRoleIndex.value;
  const role = roles.value[index];
  // ADMIN 由后端拒绝覆写，前端只读区已禁用入口，此处兜底不发空请求
  if (role === undefined || role.roleCode === 'ADMIN' || saving.value) {
    return;
  }
  saving.value = true;
  try {
    const sel = selectionByRole.value[index] ?? { API: [], MENU: [], ELEMENT: [] };
    const payload = [
      ...sel.API,
      ...sel.MENU,
      ...sel.ELEMENT,
      // 清单外码带回：不在分组清单内的存量绑定（历史码/清单漂移）原样保留，防静默丢
      ...(role.permCodes ?? []).filter((code) => !allPermCodes.value.has(code)),
    ];
    const updated = await updateRolePermissions(role.roleCode ?? '', payload);
    // 以响应终态回写（permCodes=去重保序落库态），管理面即时反映服务端权威数据
    roles.value[index] = updated;
    selectionByRole.value[index] = buildSelection(updated);
    // 成功反馈双通道：钤印承动词结果，message 批注条承矩阵重载/重登录长句（逐字冻结）
    showStamp('已保存');
    ElMessage.success('各实例 403 矩阵已重载，相关用户需重新登录生效');
  } catch {
    // 覆写失败：拦截器已弹错（ProblemDetail.detail 优先），本地保持勾选态供改后重试
  } finally {
    saving.value = false;
  }
}

/**
 * 角色启停开关（旁置于角色信息条）：DISABLED 方向二次确认（防误停生产角色——停用即
 * 禁配新用户且该角色全部在线会话立即登出，确认弹窗明示踢出后果）；确认后调 PUT status
 * 并以响应回写角色行，取消则维持原态（开关为受控 :model-value 形态，无需回滚动作）。
 *
 * @param role 目标角色，非空
 * @param next 开关新值（change 载荷）：true=ACTIVE / false=DISABLED
 */
async function onStatusSwitch(role: RoleAdminVO, next: boolean): Promise<void> {
  // ADMIN 禁停（停用超管将锁死管理面）；模板层开关已禁用，此处兜底
  if (role.roleCode === 'ADMIN') {
    return;
  }
  const target = next ? 'ACTIVE' : 'DISABLED';
  if (target === 'DISABLED') {
    try {
      await ElMessageBox.confirm(
        `停用后「${role.roleName}」将禁止配置新用户，且该角色全部在线会话立即登出（需重新登录）。确认停用？`,
        '停用角色确认',
        { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' },
      );
    } catch {
      // 取消确认：受控开关由 :model-value 维持原态，直接返回不出网
      return;
    }
  }
  try {
    const updated = await updateRoleStatus(role.roleCode ?? '', target);
    const index = roles.value.findIndex((item) => item.roleCode === role.roleCode);
    if (index >= 0) {
      roles.value[index] = updated;
    }
    ElMessage.success(`角色「${role.roleName}」已${target === 'ACTIVE' ? '启用' : '停用'}`);
  } catch {
    // 启停失败：拦截器已弹错，角色行 status 未回写即维持原态
  }
}

// 首屏并行加载两读端点（入口记录：管理台无写操作副作用，info 级日志省略——
// 出网与错误日志归 http 拦截器/后端审计侧承载，前端不重复落日志）
onMounted(() => {
  void loadAll();
});
</script>

<template>
  <div class="fuy-page perm-matrix" v-loading="loading">
    <!-- 裸页收编（蓝图戊-1）：根挂 .fuy-page 纵向单列（页根禁挂 .fuy-stagger，契约 ⑦.4） -->
    <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+「已选 x/y」墨底徽标状态位 +
         批注行（原 desc 一句升批注，「谁·时刻」齐备，原文案锚点逐字保留）+ 2px 墨规收底 -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">权限管理</h1>
        <span class="fuy-page-en" aria-hidden="true">PERMISSION MATRIX</span>
      </div>
      <div class="fuy-page-status">
        <span class="fuy-status-pill perm-count-pill">
          已选 {{ selectedCount }} / {{ allPermCodes.size }}
        </span>
      </div>
      <p class="fuy-page-note">
        签认人 {{ signerName }} · <time>{{ todayLabel }}</time> ·
        <!-- 冻结句整段收进行内 span：Prettier 按元素边界断行，span 内文本节点保持逐字连续 -->
        <span>勾选保存即全量覆写：各实例 403 矩阵实时重载，相关用户需重新登录生效</span>
      </p>
    </header>

    <!-- 首屏读端点失败：.fuy-empty 错误态变体（说问题给恢复+墨实底恢复钮，不白屏不崩） -->
    <div v-if="loadFailed" class="fuy-empty" role="alert">
      <p class="fuy-empty-title">角色或权限清单加载失败</p>
      <p class="fuy-empty-hint">
        网络中断或服务暂不可用导致清单读取失败，请检查连接后点击「重新加载」重试。
      </p>
      <el-button type="primary" @click="loadAll">重新加载</el-button>
    </div>

    <template v-else>
      <!-- 外层角色签（蓝图戊-1②）：.fuy-tabs 卷宗签语法，激活签顶部 2px 朱砂签线为装饰走线 -->
      <el-tabs v-model="activeRoleCode" class="role-tabs fuy-tabs">
        <el-tab-pane
          v-for="(role, roleIdx) in roles"
          :key="role.roleCode"
          :name="role.roleCode ?? ''"
        >
          <template #label>
            <span class="role-tab-label">{{ role.roleName ?? role.roleCode }}</span>
            <!-- DISABLED 角色签灰徽标（蓝图戊-1②）：info 语义洗底胶囊，非状态朱 -->
            <span v-if="role.status === 'DISABLED'" class="fuy-status-pill fuy-status-pill--info"
              >停用</span
            >
          </template>

          <!-- 角色信息条=纸面卡（蓝图戊-1③）：编码/数据范围/统计 + 启停开关旁置 -->
          <div class="role-bar">
            <span class="role-code">{{ role.roleCode }}</span>
            <span class="role-scope">数据范围：{{ role.dataScopeType }}</span>
            <span class="role-count">已选 {{ selectedCount }} / {{ allPermCodes.size }}</span>
            <el-switch
              class="role-switch"
              :model-value="role.status === 'ACTIVE'"
              :disabled="role.roleCode === 'ADMIN'"
              active-text="启用"
              inactive-text="停用"
              @change="(value: string | number | boolean) => onStatusSwitch(role, Boolean(value))"
            />
          </div>

          <!-- ADMIN 只读注记（D3）：金洗底批注条（蓝图戊-1④，金线装订同源洗底，非状态色） -->
          <div v-if="role.roleCode === 'ADMIN'" class="admin-note" role="note">
            ADMIN 运行期全放：超管不种绑定行，403 拦截器对 ADMIN 会话恒放行，矩阵不可编辑
          </div>

          <!-- 类型层 tabs：API/MENU/ELEMENT；card 型保留，色承全局 token 映射（蓝图戊-1④） -->
          <el-tabs v-model="activeType" class="type-tabs fuy-tabs" type="card">
            <el-tab-pane v-for="tab in PERM_TYPE_TABS" :key="tab" :name="tab">
              <template #label> {{ tab }}（{{ (pointsByType.get(tab) ?? []).length }}） </template>

              <!-- ELEMENT：域分桶小节展示（信息密度收拢：40 码 8 域分组浏览，既有结构冻结） -->
              <div v-if="tab === 'ELEMENT'" class="element-buckets">
                <el-checkbox-group
                  v-model="selectionByRole[roleIdx].ELEMENT"
                  :disabled="role.roleCode === 'ADMIN'"
                >
                  <section
                    v-for="bucket in elementBuckets"
                    :key="bucket.domain"
                    class="bucket"
                    :aria-label="`${bucket.domain} 域元素权限`"
                  >
                    <h4 class="bucket-title">{{ bucket.domain }}（{{ bucket.points.length }}）</h4>
                    <div class="bucket-points">
                      <el-checkbox
                        v-for="point in bucket.points"
                        :key="point.permCode"
                        :value="point.permCode"
                        size="small"
                      >
                        <span class="perm-name">{{ point.permName }}</span>
                        <span class="perm-code fuy-num">{{ point.permCode }}</span>
                      </el-checkbox>
                    </div>
                  </section>
                </el-checkbox-group>
              </div>

              <!-- API/MENU：平铺展示（分组列即权限码清单） -->
              <el-checkbox-group
                v-else
                v-model="selectionByRole[roleIdx][tab]"
                :disabled="role.roleCode === 'ADMIN'"
              >
                <div class="bucket-points">
                  <el-checkbox
                    v-for="point in pointsByType.get(tab) ?? []"
                    :key="point.permCode"
                    :value="point.permCode"
                    size="small"
                  >
                    <span class="perm-name">{{ point.permName }}</span>
                    <span class="perm-code fuy-num">{{ point.permCode }}</span>
                  </el-checkbox>
                </div>
              </el-checkbox-group>
            </el-tab-pane>
          </el-tabs>

          <!-- 保存操作条：全量覆写出口（ADMIN 只读区不渲染） -->
          <footer v-if="role.roleCode !== 'ADMIN'" class="matrix-footer">
            <el-button type="primary" :loading="saving" :disabled="saving" @click="saveMatrix">
              保存矩阵
            </el-button>
            <span class="footer-hint">保存将全量覆写「{{ role.roleName }}」的权限绑定</span>
          </footer>
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 钤印确认浮层（契约 ⑧.5）：只承成功确认「已保存」；v-if 挂载制=出院管理页先例同构，
         挂载即 is-show，seq 递增重挂重放落章动画，≤1200ms 收场 -->
    <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
      {{ stampText }}
    </div>
  </div>
</template>

<style scoped>
/* 组件私有布局（全局 .fuy-* 体系承载通用组件态，web A.1-2）；管理台高密度矩阵形态。
   根级纵排与间距由 .fuy-page 全局类承载（flex column + gap），页内不另设级联容器 */

/* 门牌状态位：「已选 x/y」墨底徽标（蓝图戊-1①：墨底纸字，形状复用全局胶囊，
   scoped 同名属性覆盖洗底色——特异性含 data-v 属性恒压全局单类） */
.perm-count-pill {
  background: var(--fuy-color-text-emphasis);
  color: var(--fuy-paper);
}

/* 角色信息条：编码/统计/开关一行排布的纸面卡（蓝图戊-1③：卡面亮纸+发丝描边，版面内零投影）；
   内距取 8/12 token 档（与开关核高恰成 48px 工具条节奏） */
.role-bar {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-4);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  margin-bottom: var(--fuy-space-2);
  background: var(--fuy-surface-card);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
}

.role-code {
  font-family: var(--fuy-font-family-mono, monospace);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
}

.role-scope,
.role-count {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-info-text);
}

.role-switch {
  margin-left: auto;
}

/* ADMIN 只读注记：金洗底签批注条（蓝图戊-1④——info 语义洗底取金线装订同源值，
   非状态色；浓墨字承载语义，左缘金规线为批注条家族同构走线） */
.admin-note {
  margin-bottom: var(--fuy-space-2);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  background: var(--fuy-palette-brand-100);
  border-left: 3px solid var(--fuy-gold);
  border-radius: var(--fuy-radius-sm);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

/* 类型层 tabs 收紧上下距（双层 tabs 视觉层级：外层大内层小）；min-height 320px 锁 CLS
   （蓝图戊-1⑥骨架档） */
.type-tabs {
  min-height: 320px;
}

/* ELEMENT 域分桶：桶间分隔、桶内码紧凑排布 */
.bucket {
  margin-bottom: var(--fuy-space-3);
}

/* 桶标题：13px/600 + 墨色左规线（域小节标）。行高必须显式回填——EP 基样式
   .el-checkbox-group { font-size: 0; line-height: 0 } 会沿 DOM 继承进挂在其内的
   桶标题（h4），缺省继承值 0 会把标题文字裁成半行 */
.bucket-title {
  margin: 0 0 var(--fuy-space-2);
  padding-left: var(--fuy-space-2);
  line-height: 20px;
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
  color: var(--el-text-color-regular);
  border-left: 3px solid var(--el-color-primary);
}

/* 矩阵区勾选 grid（蓝图戊-1③：勾选项自适应多列网格，宽屏双列起，密排扫读）；
   行距取 8px 档——cell 双行化后 4px 过密 */
.bucket-points {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--fuy-space-2) var(--fuy-space-4);
  padding-left: 11px; /* 对齐桶标题文字基线（3px 规线 + 8px 内距），非节奏档 */
}

/* 勾选 cell 双行化：EP .el-checkbox 基样式（nowrap + 定高 + 30px 右距）会把
   「名称+码」横向溢出 240px 列宽压邻列——名称行/码行纵排两段；scoped 组合
   选择器页面内收口（.fuy-dense 挂类前缀同款机理，非裸改全局 .el-*） */
.bucket-points .el-checkbox {
  height: auto;
  align-items: flex-start;
  margin-right: 0;
  white-space: normal;
}

/* 权限行双段文案：名称主体 + 码等宽辅注独立成行（审计形态：管理员需核对码本体；
   码辅注灰墨 .fuy-num 等宽数字——蓝图戊-1③）。码行 break-all 折断，超长码在
   自身行内换行，禁横向溢出回归 */
.perm-name {
  font-size: var(--fuy-font-size-sm);
}

.perm-code {
  display: block;
  font-family: var(--fuy-font-family-mono, monospace);
  font-size: var(--fuy-font-size-xs, 12px);
  color: var(--fuy-color-info-text);
  word-break: break-all;
}

/* 保存操作条：上缘发丝收底（规线不越级，卡内分界走发丝） */
.matrix-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 8px;
  border-top: var(--fuy-border-hairline);
}

.footer-hint {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-info-text);
}
</style>
