// 应用装配入口单测（D-31 回归锚，2026-10-12）：EP locale 应用级全局注入——ElMessage/
// ElMessageBox/ElNotification 等命令式弹层渲染在 App 组件树之外，不吃 App.vue 页内
// ElConfigProvider 的 provide 链，只吃 main.ts provideGlobalConfig(global=true) 写入的
// EP 模块级全局配置；本 spec 真实执行 main.ts 装配链后断言该通道就位 zh-cn（弹层按钮
// 「确定/取消」的唯一来源——修复前恒为英文 OK/Cancel）。
// App.vue 桩化为空模板：本 spec 锚定全局注入通道而非页面渲染（App 页内通道由 App.spec
// 承载），避免整应用挂载（LoginView 1700 行）拖慢单测；路由懒加载随空模板零触发。
// 装配在文件加载期完成（top-level await）：main.ts→router→http 链引入 EP 内联变换耗时
// 归收集阶段，不占单测超时预算；main.ts 挂载到 #app 且无卸载出口，本文件单用例自居
// （vitest 文件级环境隔离），不与其他 spec 共享 jsdom 状态。
import { describe, expect, it, vi } from 'vitest';

// 模块级先行桩化（装配 import 前生效）
vi.mock('./App.vue', () => ({ default: { name: 'AppStub', template: '<div>app-stub</div>' } }));

// 挂载宿主先行就位，再触发真实装配链（createApp → provideGlobalConfig → mount）
const appHost = document.createElement('div');
appHost.id = 'app';
document.body.appendChild(appHost);
await import('./main');

describe('应用装配入口 main.ts', () => {
  it('EP locale 全局注入（D-31）：命令式弹层通道读到 zh-cn（弹层按钮=确定/取消）', async () => {
    // 桩应用挂载成功证据：装配链跑通无异常（守卫重定向登录页不影响空模板渲染）
    expect(appHost.innerHTML).toContain('app-stub');

    // useGlobalConfig 同走 hooks 深路径（禁 root 全量 barrel——vitest 内联变换全量 EP 超
    // 单测预算；读取形态=无组件实例上下文回落模块级全局配置，正是命令式弹层的读取通道）
    const { useGlobalConfig } =
      await import('element-plus/es/components/config-provider/src/hooks/use-global-config');
    const locale = useGlobalConfig('locale').value;
    // EP locale 类型将 messagebox 声明为联合形态（string|string[]|TranslatePair），zh-cn
    // 实际为键值对象——按运行时形态收窄断言（断言目标=弹层按钮文案的业务结果）
    const messagebox = locale?.el?.messagebox as { confirm: string; cancel: string } | undefined;
    expect(messagebox?.confirm).toBe('确定');
    expect(messagebox?.cancel).toBe('取消');
  });
});
