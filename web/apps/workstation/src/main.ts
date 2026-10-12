// 应用装配入口：Pinia（跨页面共享状态）+ Router（路由与权限点清单）+ v-perm 元素权限指令，挂载到 #app
// provideGlobalConfig 走 config-provider 组件模块内 hooks 深路径：EP exports 映射 ./es/* 的
// import 目标仅 .mjs 单文件形态（目录 index 无回退），组件目录级路径不可解析
import { provideGlobalConfig } from 'element-plus/es/components/config-provider/src/hooks/use-global-config';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { createPinia } from 'pinia';
import { createApp } from 'vue';
import App from './App.vue';
import { permDirective } from './directives/perm';
import { router } from './router';
import { installGlobalErrorHandler } from './utils/errorHandler';
// 全局设计系统地基（设计 token + EP 主色映射 + 动效），样式入口置于 createApp 之前（§7.4）
import './styles/index.css';
// ElMessageBox 函数式弹层样式按需注入（D-31 收尾①，2026-10-12）：函数式组件（ElMessage/
// ElMessageBox/ElNotification）经手动 import 消费时不触发 unplugin resolver 的样式联动，
// 既有口径靠各视图自配 style/css（32 处 message/14 处 message-box 散装 import）——23 个
// ElMessageBox 消费文件中 9 个漏注入（含 P05 过号/P06 接诊确认弹窗），弹窗裸布局（不居中/
// 无遮罩，document.styleSheets 实测零 .el-message-box 规则）。此处应用级单点收口补齐；
// message 样式勿重复（api/http.ts:12 全局注入已覆盖）；notification 业务源零消费不引入
// （禁推测性设计）；v-loading 指令样式经 Components 插件 directives 默认通道逐视图自动注入
// （ElementPlusResolver resolveDirective 实证），均不属本行收口面
import 'element-plus/es/components/message-box/style/css';

const app = createApp(App);
app.use(createPinia()).use(router);
// 全局兜底 errorHandler（册 0 P-GBL-03）：装配先于 mount——首屏渲染期抛错即有统一出口
// （logger 全量留痕 + 3s 冷却窗 ElMessage 去抖 + AxiosError 防双弹，详见 utils/errorHandler.ts）
installGlobalErrorHandler(app);
// 全局注册 v-perm：视图免逐组件引入即可按元素权限码隐藏无权限 DOM（PR-4F F8 双入口之二）
app.directive('perm', permDirective);
// EP locale 应用级全局注入（D-31 修正，2026-10-12）：ElMessage/ElMessageBox/ElNotification
// 函数式弹层渲染在 App 组件树之外，不吃 App.vue 页内 ElConfigProvider 的 provide 链——
// 未走本通道时命令式弹层按钮恒为 EP 默认英文 OK/Cancel（P05 过号/P06 接诊确认弹窗实证）。
// 此处经 provideGlobalConfig 第三参 global=true 把 zh-cn 写入 EP 模块级全局配置，命令式
// 弹层即取中文默认文案（确定/取消）；页内 ElConfigProvider 保留不动（F-2 既有记录，页内
// 组件与全局弹层双通道双保险）。import 走按需子路径（config-provider 组件模块），禁全量
// 入口（§9.2.3 口径，locale 同走 es/locale/lang 按需路径）
provideGlobalConfig({ locale: zhCn }, app, true);
app.mount('#app');
