// W-70②：用例失败中止于 unmount 前时防泄漏级联（Task 17 修复环实证 1→5 连锁）。
// enableAutoUnmount 挂 vitest afterEach：每个用例收尾（含断言失败提前中止）由库侧对
// 全部挂载实例统一 wrapper.unmount()，组件 onUnmounted 清理链（订阅退订/定时器停摆）
// 得以执行——防单点失败滞留副作用级联放大为多例红。
// 注：@vue/test-utils 2.5 契约为「传入钩子注册函数」，卸载动作由库侧完成（非旧版
// 自写 (wrapper) => wrapper.unmount() 回调）。经 vitest.config.ts test.setupFiles 挂载，
// 对 bigscreen 全部 spec 生效。
import { enableAutoUnmount } from '@vue/test-utils';
import { afterEach } from 'vitest';

// 注册用例级收尾钩子：库侧对每个挂载实例统一 wrapper.unmount()
// 注：用例体内已手动 unmount 的实例经 Vue isMounted 守卫幂等吸收（VTU unmount 无守卫、
// Vue app.unmount 有守卫——版本升级时双卸载行为列入回归观察）
enableAutoUnmount(afterEach);
