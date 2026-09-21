// 应用装配入口：Pinia（跨页面共享状态）+ Router（路由与权限点清单），挂载到 #app
import { createPinia } from 'pinia';
import { createApp } from 'vue';
import App from './App.vue';
import { router } from './router';
// 全局设计系统地基（设计 token + 动效），样式入口置于 createApp 之前（§7.4）
import './styles/index.css';

createApp(App).use(createPinia()).use(router).mount('#app');
