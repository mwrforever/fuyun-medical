<script setup lang="ts">
// 转抄工作台占位页（/inpatient/transfer，病区/班次待转抄列表与双人核对）：
// Task 14 仅登记路由与菜单占位，完整实现由 Task 15 交付（计划文件结构锁定文件名）。
</script>

<template>
  <div class="fuy-page placeholder-view">
    <header class="placeholder-head">
      <h2 class="placeholder-title">转抄工作台</h2>
      <p class="placeholder-hint">待转抄列表 / 双人核对 / 临时单次计划建设中，随 Task 15 交付</p>
    </header>
    <el-empty description="建设中" />
  </div>
</template>

<style scoped>
/* 占位页仅页头 + 空态，无业务逻辑（Task 15 整页替换） */
.placeholder-view {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-6);
}

.placeholder-head {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
}

.placeholder-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.placeholder-hint {
  margin: 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
</style>
