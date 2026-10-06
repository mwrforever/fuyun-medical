// 应用装配入口：Pinia（跨页面共享状态）+ Router（路由与权限点清单）+ v-perm 元素权限指令，挂载到 #app
import { createPinia } from 'pinia';
import { createApp } from 'vue';
import App from './App.vue';
import { permDirective } from './directives/perm';
import { router } from './router';
// 全局设计系统地基（设计 token + EP 主色映射 + 动效），样式入口置于 createApp 之前（§7.4）
import './styles/index.css';

const app = createApp(App);
app.use(createPinia()).use(router);
// 全局注册 v-perm：视图免逐组件引入即可按元素权限码隐藏无权限 DOM（PR-4F F8 双入口之二）
app.directive('perm', permDirective);
app.mount('#app');
