// 模板单元素根全量护栏（册 0 P-GBL-01，总纲 §7.2-3 自此机器化强制）：dev 编译器对
// 「template 顶层 >1 个节点（含注释）」生成 Fragment 多节点根（patchFlag 2112 =
// STABLE_FRAGMENT|DEV_ROOT_FRAGMENT），与 MainLayout 路由出口 <Transition mode="out-in">
// 组合触发 afterLeave 永不触发的白屏死锁（research/00-白屏bug定案报告.md §2 代码级定案）。
// 本护栏经 vue/compiler-sfc——与 dev 编译同一套编译器，确定性等价——parse 全部视图源码，
// 断言 template 顶层（滤除空白文本后）恰好 1 个元素节点：注释（type=3）计入节点数，故顶层
// 注释页必红。收集面 = src/views 递归全部 .vue、仅排除 layout/（MainLayout/AppHeader/
// AppSidebar 渲染在 App.vue 的 RouterView 与布局槽位内，非 fuy-page Transition 直挂面，
// 不构成白屏触发面；AppSidebar 首行顶层注释属其设计留痕）——未来新页自动纳入防护。
// 另设覆盖面用例：硬编码 12 个已修复触发页路径断言在收集清单内，防未来扩大排除规则把
// 触发页漏出防护面（护栏自身漂移自保护）。
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parse } from 'vue/compiler-sfc';

/** AST 节点类型字面量（来源 @vue/compiler-core NodeTypes：ELEMENT=1 / TEXT=2 / COMMENT=3；
 * pnpm 严格依赖下不深引未声明包，数值与枚举同源稳定，故以字面量+注释承载） */
const NODE_ELEMENT = 1;
const NODE_TEXT = 2;

/** 白屏触发页修复清单（册 0 内移的 12 页，相对 src/views 的 POSIX 路径）：覆盖面自保护锚 */
const TRIGGER_PAGES = [
  'billing/PricingSettleView.vue',
  'billing/RefundApprovalView.vue',
  'pharmacy/DrugDictView.vue',
  'pharmacy/DispenseWorkbenchView.vue',
  'pharmacy/DispenseReturnView.vue',
  'nursing/AdverseEventView.vue',
  'inpatient/DischargeManageView.vue',
  'pharmacy/ReviewTaskView.vue',
  'iot/BindingManageView.vue',
  'iot/CommandCenterView.vue',
  'ward/InfusionBoardView.vue',
  'system/RolePermissionMatrixView.vue',
];

/** 视图目录根（本 spec 与 src/views 同目录，import.meta.dirname 即其绝对路径） */
const viewsRoot = import.meta.dirname;

/** template 子节点的结构化最小面：type 在 compiler-core 中为 NodeTypes 枚举，此处收窄为 number
 * 承载——既规避深引未声明包取枚举本体（pnpm 严格依赖），又使与字面量的比较不落入
 * no-unsafe-enum-comparison 门禁；content 仅 Text/Comment 节点存在，故可选 */
interface TemplateChildLikeNode {
  type: number;
  content?: string;
}

/** 递归收集 src/views 全部 .vue 的 POSIX 相对路径（excludeDirs 按目录名整枝剪除） */
function collectViewPaths(dir: string, excludeDirs: string[], acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (excludeDirs.includes(name)) {
        continue;
      }
      collectViewPaths(full, excludeDirs, acc);
    } else if (name.endsWith('.vue')) {
      acc.push(relative(viewsRoot, full).split(sep).join('/'));
    }
  }
  return acc;
}

/** 单页判定：template 顶层滤除空白文本后恰好 1 个节点且为元素（注释 type=3 计入节点数） */
function hasSingleElementRoot(relativePath: string): boolean {
  const source = readFileSync(join(viewsRoot, relativePath), 'utf-8');
  const { descriptor } = parse(source, { filename: relativePath });
  // 枚举 type 经结构化收窄为 number 后再与字面量比较（判定语义等价，见类型注释）
  const nodes = ((descriptor.template?.ast?.children ?? []) as TemplateChildLikeNode[]).filter(
    (node) => !(node.type === NODE_TEXT && (node.content ?? '').trim() === ''),
  );
  return nodes.length === 1 && nodes[0].type === NODE_ELEMENT;
}

const views = collectViewPaths(viewsRoot, ['layout']).map((path) => ({
  path,
  singleRoot: hasSingleElementRoot(path),
}));

describe('模板单元素根全量护栏（out-in Transition 白屏死锁回归防线）', () => {
  it('全部视图组件 template 顶层为单元素根（out-in Transition 白屏死锁回归护栏）', () => {
    const offenders = views.filter((view) => !view.singleRoot).map((view) => view.path);
    // 失败清单逐行列出违例文件，定位到页。修复方式唯一：把 template 顶层根元素之前的
    // 注释等节点内移为根元素内首行（批注逐字保留、缩进加 2 空格），禁止删除业务批注
    expect(
      offenders,
      `以下 ${offenders.length} 个视图 template 顶层非单元素根（顶层注释/多根均触发 out-in 白屏死锁）：\n${offenders.join('\n')}`,
    ).toEqual([]);
  });

  it('护栏收集面覆盖 12 个白屏触发页（排除规则漂移自保护）', () => {
    const collected = views.map((view) => view.path);
    for (const page of TRIGGER_PAGES) {
      expect(collected, `护栏收集面漏掉触发页 ${page}——排除规则漂移将使其逃出防护`).toContain(page);
    }
  });
});
