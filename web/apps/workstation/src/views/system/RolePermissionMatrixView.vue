<script setup lang="ts">
/**
 * 权限管理台（PR-4F F8）：角色 × 权限矩阵编辑器。
 *
 * <p>双层 tab 结构——外层角色 tab（每角色一页，含启停开关旁置）× 内层类型 tab
 * （API/MENU/ELEMENT 三组，ELEMENT 组内按权限码首段域前缀细分组展示）。勾选态为
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
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listPermissions, listRoles, updateRolePermissions, updateRoleStatus } from '@/api/system';
import type { PermissionGroupVO, RoleAdminVO } from '@/api/system';

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
/** 首屏加载失败态（任一读端点失败即整页空态兜底 + 重试入口） */
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

/** 当前激活角色勾选态（角色信息条统计与保存载荷的读取面；越界兜底空态防渲染期闪烁） */
const activeSelection = computed<SelectionByType>(
  () => selectionByRole.value[activeRoleIndex.value] ?? { API: [], MENU: [], ELEMENT: [] },
);

/** 当前角色勾选总数（三类型并集，角色信息条「已选 x/y」统计口径） */
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
 * 首屏加载：角色清单 + 权限点分组并行读取；任一失败整页空态兜底（重试入口），
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
    // 读端点失败：拦截器已统一弹错，本地落空态渲染重试入口（不崩不白屏）
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

/**
 * 保存当前角色矩阵：三类型勾选并集 + 清单外码原样带回 → PUT 全量覆写（F4）。
 * 成功后以响应终态回写角色行与该角色勾选态并提示矩阵重载/重登录语义（F4③ 文案逐字契约）。
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
    ElMessage.success('已保存：各实例 403 矩阵已重载，相关用户需重新登录生效');
  } catch {
    // 覆写失败：拦截器已弹错（ProblemDetail.detail 优先），本地保持勾选态供改后重试
  } finally {
    saving.value = false;
  }
}

/**
 * 角色启停开关（旁置于角色信息条）：DISABLED 方向二次确认（防误停生产角色——
 * 停用即禁配新用户）；确认后调 PUT status 并以响应回写角色行，取消则维持原态
 * （开关为受控 :model-value 形态，无需回滚动作）。
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
        `停用后「${role.roleName}」将禁止配置新用户，存量会话不回溯撤销。确认停用？`,
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
  <div class="role-perm-matrix" v-loading="loading">
    <header class="matrix-header">
      <h2 class="matrix-title">权限管理</h2>
      <p class="matrix-desc">
        勾选保存即全量覆写该角色权限矩阵：各实例 403 拦截矩阵实时重载，相关用户需重新登录生效。
      </p>
    </header>

    <!-- 首屏读端点失败空态：重试兜底，不白屏不崩 -->
    <el-empty v-if="loadFailed" description="角色或权限清单加载失败，请重试">
      <el-button type="primary" @click="loadAll">重新加载</el-button>
    </el-empty>

    <template v-else>
      <el-tabs v-model="activeRoleCode" class="role-tabs">
        <el-tab-pane
          v-for="(role, roleIdx) in roles"
          :key="role.roleCode"
          :name="role.roleCode ?? ''"
        >
          <template #label>
            <span class="role-tab-label">{{ role.roleName ?? role.roleCode }}</span>
            <el-tag v-if="role.status === 'DISABLED'" size="small" type="danger">停用</el-tag>
          </template>

          <!-- 角色信息条：编码/数据范围 + 启停开关旁置 + 勾选统计 -->
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

          <!-- ADMIN 只读注记（D3）：运行期全放语义说明，矩阵区禁编 -->
          <el-alert
            v-if="role.roleCode === 'ADMIN'"
            class="admin-note"
            type="info"
            :closable="false"
            show-icon
            title="ADMIN 运行期全放：超管不种绑定行，403 拦截器对 ADMIN 会话恒放行，矩阵不可编辑"
          />

          <!-- 类型层 tabs：API/MENU/ELEMENT；card 型与外层角色 tab 区分双层视觉层级 -->
          <el-tabs v-model="activeType" class="type-tabs" type="card">
            <el-tab-pane v-for="tab in PERM_TYPE_TABS" :key="tab" :name="tab">
              <template #label> {{ tab }}（{{ (pointsByType.get(tab) ?? []).length }}） </template>

              <!-- ELEMENT：域分桶小节展示（信息密度收拢：40 码 8 域分组浏览） -->
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
                        <span class="perm-code">{{ point.permCode }}</span>
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
                    <span class="perm-code">{{ point.permCode }}</span>
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
  </div>
</template>

<style scoped>
/* 组件私有布局（全局 .fuy-* 体系承载通用组件态，web A.1-2）；管理台高密度矩阵形态 */
.role-perm-matrix {
  padding: 16px 20px;
}

.matrix-header {
  margin-bottom: 8px;
}

.matrix-title {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 600;
}

.matrix-desc {
  margin: 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-secondary);
}

/* 角色信息条：编码/统计/开关一行排布，密度优先 */
.role-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 12px;
  margin-bottom: 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.role-code {
  font-family: var(--fuy-font-family-mono, monospace);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
}

.role-scope,
.role-count {
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-secondary);
}

.role-switch {
  margin-left: auto;
}

/* ADMIN 只读注记 */
.admin-note {
  margin-bottom: 8px;
}

/* 类型层 tabs 收紧上下距（双层 tabs 视觉层级：外层大内层小） */
.type-tabs {
  min-height: 240px;
}

/* ELEMENT 域分桶：桶间分隔、桶内码紧凑换行排布 */
.bucket {
  margin-bottom: 12px;
}

.bucket-title {
  margin: 0 0 6px;
  padding-left: 8px;
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
  color: var(--el-text-color-regular);
  border-left: 3px solid var(--el-color-primary);
}

.bucket-points {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  padding-left: 11px;
}

/* 权限行双段文案：名称主体 + 码等宽辅注（审计形态：管理员需核对码本体） */
.perm-name {
  font-size: var(--fuy-font-size-sm);
}

.perm-code {
  margin-left: 6px;
  font-family: var(--fuy-font-family-mono, monospace);
  font-size: var(--fuy-font-size-xs, 12px);
  color: var(--el-text-color-secondary);
}

.matrix-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.footer-hint {
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-secondary);
}
</style>
