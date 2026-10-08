<script setup lang="ts">
// 登录页（PR-3 B3.4）：用户名/口令表单 + Element Plus 声明式校验；
// 失败提示由响应拦截器统一弹出（web A.3-2），本组件不重复弹错；按钮与回车均可提交，
// 成功后按 redirect 回跳参数跳转（仅接受站内路径，防外站跳转）。
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useTemplateRef } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import type { LoginRequest } from '@/api/auth';

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
    <div class="login-center">
      <!-- 左区 · 封皮版面：排印直接坐在工作面纸上（无卡无投影，层级靠亮度差表达） -->
      <section class="login-cover" aria-label="系统门面">
        <h1 class="login-brand">富云</h1>
        <p class="login-cover-line">智慧服务 · 智慧医疗 · 智慧管理</p>
        <div class="login-cover-notes">
          <p class="login-cover-audience">面向医生、护士、药师、收费员、设备科</p>
          <p class="login-cover-note">身份确认后，翻开当日病案</p>
        </div>
      </section>

      <!-- 右区 · 封面登录表：卡面亮纸白 + 2px 墨规收底；表单字段/校验/提交通道零改动（只调视觉） -->
      <section class="login-panel" aria-label="登录表单">
        <header class="login-panel-head">
          <h2 class="login-panel-title">富云医院信息系统 · 请登录</h2>
          <!-- 描边墨印＝标记语法：登录即签认身份（墨印承标记；朱印只承危急，永不装饰） -->
          <span class="login-seal" aria-hidden="true">签</span>
        </header>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <el-form-item label="登录名" prop="loginName">
            <el-input
              v-model="form.loginName"
              size="large"
              placeholder="请输入登录名"
              autocomplete="username"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>
          <el-form-item label="口令" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              placeholder="请输入口令"
              autocomplete="current-password"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>
          <el-form-item class="login-submit-item">
            <el-button
              type="primary"
              class="login-submit"
              :loading="submitting"
              @click="handleSubmit"
            >
              <span class="login-submit-text">登录</span>
            </el-button>
          </el-form-item>
        </el-form>
        <p class="login-audit">登录行为纳入审计日志 · 留存不少于六个月</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：登录页「纸墨门面」（批次 2 册 1 契约 §2）——病案夹的封面页。
   纸面三级铺陈：整页工作面沉纸白为封皮，登录表单为搁在封皮上的封面纸（卡面亮纸白），
   输入域经 EP 映射层自动取表单域最亮纸面；层级靠亮度差与规线表达，零投影（唯一阴影只给
   弹层，本页无弹层）；零入场动画（无 stagger/无渐入编排），交互反馈过渡由 EP 通道与全站
   动效层承载。颜色只取 tokens.css 既有 --fuy-* 实名或 DESIGN.md 既有字面量，零新色值。
   表单逻辑零改动（模型/校验/提交/store 交互全保留），本块只承载视觉。 */
.login-view {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--fuy-surface-page); /* 整页工作面＝摊开的病案夹封皮 */
}

/* 左右非对称构图：左＝封皮排印（坐在纸上，无卡），右＝封面登录表；
   大 gap 呼吸分隔，两区之间不设竖线——亮度差即分层，规线不越级 */
.login-center {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: clamp(64px, 8vw, 112px);
  padding: var(--fuy-space-12) var(--fuy-space-10);
}

/* ---------- 左区 · 封皮版面 ---------- */
.login-cover {
  max-width: 520px;
}

/* 品牌字标：书脊深墨承字（DESIGN.md 品牌字标字色 brand-900），疏排字距拉出封皮排印的
   仪式感；padding-left 同距补偿尾随字距保证两字视觉居中；clamp 保 1440 满构图窄窗不破版 */
.login-brand {
  margin: 0;
  font-size: clamp(44px, 5vw, 60px);
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: 0.22em;
  padding-left: 0.22em;
  color: var(--fuy-palette-brand-900);
}

/* 定位副行：三位一体定位（PRODUCT.md），灰墨疏排 */
.login-cover-line {
  margin: var(--fuy-space-5) 0 0;
  font-size: var(--fuy-font-size-lg);
  font-weight: 500;
  letter-spacing: 2px;
  color: var(--fuy-color-text-secondary);
}

/* 封皮批注区：文书页边注语法——先使用者域后门面语；零伪数据（不落静态日期，无 JS 计算） */
.login-cover-notes {
  margin-top: var(--fuy-space-16);
}
.login-cover-audience {
  margin: 0;
  font-size: var(--fuy-font-size-md);
  font-weight: 500;
  color: var(--fuy-color-text-emphasis);
}
.login-cover-note {
  margin: var(--fuy-space-1) 0 0;
  font-size: var(--fuy-font-size-xs);
  color: #676d7b; /* 弱墨脚注（DESIGN.md ink-faint 既有字面量，tokens.css 无实名） */
}

/* ---------- 右区 · 封面登录表：卡面亮纸白 + 2px 墨规收底 ---------- */
.login-panel {
  width: 400px;
  background: var(--fuy-surface-card);
  border: var(--fuy-border-panel); /* 1px 石规围合（面板级规线） */
  /* 契约 §2：表单区 2px 墨规收底——页级压章线移植到门面，覆盖下缘石规 */
  border-bottom: 2px solid var(--fuy-color-text-emphasis);
  border-radius: var(--fuy-radius-lg);
  padding: 32px 32px 28px;
  /* 错误态朱收编（样稿 .login-input--error 同义）：EP 错误输入域描边/聚焦描边取
     --el-color-danger 默认珊瑚红 #f56c6c，属 off-world 杂色且与印泥朱双红并立；
     作用域内供 EP 消费 fuy 值（正向映射方向；scoped 就地下发为本页首例，
     全局承载方式的登记归批次 2 文档节点裁决），统一为印泥朱——朱承校验状态；
     全局 danger 映射属 element-plus.css 权限，本页不动 */
  --el-color-danger: var(--fuy-color-danger-text);
}

/* 卡头：副题与描边墨印同行收口；发丝线为卡内细缝（规线三级不越级） */
.login-panel-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  padding-bottom: 14px;
  border-bottom: var(--fuy-border-hairline);
}
.login-panel-title {
  margin: 0;
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
  line-height: 1.3;
  color: var(--fuy-color-text-emphasis);
}

/* 描边墨印＝标记语法：登录即签认身份，纯装饰不承信息 */
.login-seal {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  min-width: 22px;
  height: 22px;
  padding: 0 4px;
  border: 1.5px solid var(--fuy-color-text-emphasis);
  border-radius: var(--fuy-radius-sm);
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  line-height: 1;
}

/* 首字段与卡头发丝线拉开表单行距（与 EP 表单行 18px 节奏同档，样稿字段 margin-top 同值） */
.login-panel .el-form-item:first-of-type {
  margin-top: 18px;
}

/* 字段排印只调视觉（EP 校验通道不动）：label 灰墨 13px；错误提示与必填星号收编为印泥朱
   ——朱承必填/校验状态语义非装饰；EP 内部元素经 scoped :deep 定点收编（WardBoardView 先例，
   非全局裸改 .el-*） */
.login-panel :deep(.el-form-item__label) {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.login-panel :deep(.el-form-item__error) {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}
.login-panel :deep(.el-form-item.is-required:not(.is-no-asterisk) .el-form-item__label::before) {
  color: var(--fuy-color-danger-text);
}

/* 输入域描边过渡收编为全站 fast 档（DESIGN.md 动效语法：120ms 悬停/聚焦微反馈，
   样稿 border-color 过渡同值同曲线）：EP 默认 0.2s 慢半档；EP 以 inset box-shadow
   通道代 border（非投影立体感），故过渡属性为 box-shadow；reduced-motion 由
   motion.css 全局兜底直达终态 */
.login-panel :deep(.el-input__wrapper) {
  transition: box-shadow var(--fuy-motion-fast) var(--fuy-ease-standard);
}

/* 提交行：与口令域保持 26px 呼吸距（18px 表单行距 + 8px 增量，样稿同值），行底距交给审计注记 */
.login-submit-item {
  margin-top: var(--fuy-space-2);
  margin-bottom: 0;
}

/* 登录按钮＝「墨即操作」：EP primary 映射后即浓墨实底，悬停/按压档位沿映射层
   （light-3/dark-2 既有档，DESIGN.md components 表）；两字文案疏排 + padding 补偿视觉
   居中（内层 span 为自有元素，不触 EP 内部结构） */
.login-submit {
  width: 100%;
  /* 门面按钮 40px 与 large 输入域同高（样稿同值）：EP 默认档 32px 使墨块矮于双字段，
     对齐后主操作与字段行同构，门面右缘节奏收齐 */
  height: 40px;
  /* 悬停/按压底色反馈对齐全站 fast 档（120ms 标准缓动，样稿同值同曲线），
     替换 EP 默认 all .1s 的全家桶过渡 */
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}
.login-submit-text {
  letter-spacing: 4px;
  padding-left: 4px;
}

/* 键盘焦点环恢复即墨：EP 按钮自有 :focus-visible 描边（主色混白档，对卡面对比不足 3:1）
   特异性压过 tokens.css 全站即墨环——scoped 定点恢复「2px 墨 outline」全站语法 */
.login-submit:focus-visible {
  outline-color: var(--fuy-color-brand);
}

/* 合规注记：等保三级审计背景的真实性质提示（PRODUCT.md），弱墨居中不抢操作 */
.login-audit {
  margin: var(--fuy-space-4) 0 0;
  font-size: var(--fuy-font-size-xs);
  color: #676d7b; /* 弱墨脚注（DESIGN.md ink-faint 既有字面量） */
  text-align: center;
}

/* 窄窗保底（非响应式设计，workstation 无移动端断点——仅防破版）：封皮与封面表纵向堆叠 */
@media (max-width: 920px) {
  .login-center {
    flex-direction: column;
    align-items: stretch;
    gap: var(--fuy-space-10);
    width: 100%;
    max-width: 460px;
    margin: 0 auto;
  }
  .login-panel {
    width: auto;
  }
  .login-cover-notes {
    margin-top: var(--fuy-space-10);
  }
}
</style>
