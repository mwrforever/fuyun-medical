// 应用装配入口：Pinia（跨页面共享状态）+ Router（路由与权限点清单），挂载到 #app
import { createPinia } from 'pinia';
import { createApp } from 'vue';
import App from './App.vue';
import { router } from './router';

createApp(App).use(createPinia()).use(router).mount('#app');
