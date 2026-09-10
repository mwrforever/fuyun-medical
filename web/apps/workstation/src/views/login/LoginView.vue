<script setup lang="ts">
// 登录页（PR-3 B3.4）：用户名/口令表单 + Element Plus 声明式校验；
// 失败提示由响应拦截器统一弹出（web A.3-2），本组件不重复弹错；按钮与回车均可提交，
// 成功后按 redirect 回跳参数跳转（仅接受站内路径，防外站跳转）。
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useTemplateRef } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import type { LoginRequest } from '@/types/auth';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

/** 表单模型（字段与后端 LoginRequest 契约对齐；reactive 用于相关状态分组，web A.1-5） */
const form = reactive<LoginRequest>({ loginName: '', password: '' });

/** 校验规则：必填 + 长度 4-64（与后端 sys_user.login_name/password 口径匹配） */
const rules: FormRules<LoginRequest> = {
  loginName: [
    { required: true, message: '请输入登录名', trigger: 'blur' },
    { min: 4, max: 64, message: '长度须为 4-64 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入口令', trigger: 'blur' },
    { min: 4, max: 64, message: '长度须为 4-64 个字符', trigger: 'blur' },
  ],
};

/** 表单实例引用（3.5 useTemplateRef 基线，web A.1-7） */
const formRef = useTemplateRef<FormInstance>('formRef');
/** 提交中状态：驱动按钮 loading，防重复提交 */
const submitting = ref(false);

/** 提交登录：校验 → store.login → 按回跳地址跳转；失败仅终止流程（弹错归拦截器） */
async function handleSubmit(): Promise<void> {
  if (submitting.value) {
    return;
  }
  // validate() 拒绝时携带字段错误对象：统一转布尔，避免未处理拒绝
  const valid = await formRef.value?.validate().then(
    () => true,
    () => false,
  );
  if (valid !== true) {
    return;
  }
  submitting.value = true;
  try {
    await authStore.login({ ...form });
    // 回跳地址仅接受站内根相对路径（排除协议相对路径 // 与外站绝对地址），纵深防御 open redirect
    const raw = route.query['redirect'];
    const redirect =
      typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//') ? raw : '/';
    await router.push(redirect);
  } catch {
    // 登录失败（凭据错误/锁定/停用）提示已由响应拦截器统一弹出，此处仅终止跳转
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="login-view">
    <el-card class="login-card">
      <h1 class="login-title">医护工作站</h1>
      <p class="login-subtitle">富云医院信息系统 · 请登录</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="登录名" prop="loginName">
          <el-input
            v-model="form.loginName"
            placeholder="请输入登录名"
            autocomplete="username"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-form-item label="口令" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入口令"
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            class="login-submit"
            :loading="submitting"
            @click="handleSubmit"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：登录卡片居中，视觉细化随设计稿演进 */
.login-view {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
}

.login-card {
  width: 360px;
}

.login-title {
  margin: 0 0 4px;
  font-size: 22px;
  text-align: center;
}

.login-subtitle {
  margin: 0 0 16px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.login-submit {
  width: 100%;
}
</style>
