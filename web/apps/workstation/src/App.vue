<script setup lang="ts">
// 应用根组件：路由出口 + Element Plus 全局配置（zh-cn locale 注入——分页「共 N 条」与
// 日期面板中文化，F-2；locale 按需路径 import 禁全量入口，§9.2.3）。站点中文名为冒烟测试锚点
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { RouterView } from 'vue-router';
</script>

<template>
  <el-config-provider :locale="zhCn">
    <div class="app-shell">
      <RouterView />
    </div>
  </el-config-provider>
</template>
