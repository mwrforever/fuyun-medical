<script setup lang="ts">
// 登录页（PR-3 B3.4）：用户名/口令表单 + Element Plus 声明式校验；
// 失败提示由响应拦截器统一弹出（web A.3-2），本组件不重复弹错；按钮与回车均可提交，
// 成功后按 redirect 回跳参数跳转（仅接受站内路径，防外站跳转）。
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useTemplateRef } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { User, Lock } from '@element-plus/icons-vue';
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
  <div class="login-split">
    <!-- 左栏 · 世界叙事面：病历夹墨脊封皮（品牌 → 叙事 → 启封印批注 → 标签架纸层） -->
    <aside class="login-spine" aria-label="富云医院信息系统">
      <header class="spine-brand login-rise" style="--fuy-rise-delay: 60ms">
        <span class="spine-brand-mark">富云</span>
      </header>

      <div class="spine-body">
        <div class="spine-content">
          <h1 class="spine-sysname login-rise" style="--fuy-rise-delay: 100ms">富云医院信息系统</h1>
          <!-- 世界叙事文案：真实产品语义（全院业务闭环 + 留痕归档），禁 lorem 禁设计术语 -->
          <p class="spine-narrative login-rise" style="--fuy-rise-delay: 140ms">
            门诊、住院、药房与护理的临床文书，在同一份病案上闭环流转——写下即留痕，签认即归档。
          </p>

          <div class="spine-seal-row login-rise" style="--fuy-rise-delay: 180ms">
            <!-- 描边墨印「启」：翻开当日病案的开卷标记（墨印承标记，朱印只承危急永不装饰） -->
            <span class="spine-seal" aria-hidden="true">启</span>
            <div>
              <p class="spine-seal-label">当日病案 · 待启封</p>
              <!-- 「谁·何时」批注语法：签认人登录前身份未成立以 — 占位（零伪数据）；
                   开卷时刻为运行时真实系统日期（模板表达式取本地时区，禁落静态日期） -->
              <p class="spine-note">
                启封批注 · 签认人 <span class="spine-placeholder">—</span> · 开卷时刻
                <span class="spine-note-time"
                  >{{ new Date().toLocaleDateString('en-CA') }} 周{{
                    '日一二三四五六'[new Date().getDay()]
                  }}</span
                >
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- 病案标签架 + 纸层意象：五业务域签牌（门诊/住院/药房/护理/收费，真实产品域）
           + 封皮内页块顶缘三级层进透视（纯 CSS 几何，亮度差分层零投影），整组纯装饰 -->
      <div class="spine-chart" aria-hidden="true">
        <div class="chart-tabs">
          <span class="chart-tab login-tab-rise" style="--fuy-rise-delay: 260ms">门诊</span>
          <span class="chart-tab login-tab-rise" style="--fuy-rise-delay: 300ms">住院</span>
          <span class="chart-tab login-tab-rise" style="--fuy-rise-delay: 340ms">药房</span>
          <span class="chart-tab login-tab-rise" style="--fuy-rise-delay: 380ms">护理</span>
          <span class="chart-tab login-tab-rise" style="--fuy-rise-delay: 420ms">收费</span>
        </div>
        <div class="chart-paper login-rise" style="--fuy-rise-delay: 220ms">
          <div class="chart-paper-layer chart-paper-layer--1"></div>
          <div class="chart-paper-layer chart-paper-layer--2"></div>
          <div class="chart-paper-layer chart-paper-layer--3"></div>
        </div>
      </div>
    </aside>

    <!-- 右栏 · 表单工作面：纸面三级铺陈 + 登录表（字段/校验/提交通道零改动，只动视觉） -->
    <main class="login-face">
      <section class="login-panel login-rise" style="--fuy-rise-delay: 300ms" aria-label="登录表单">
        <header class="login-panel-head login-rise" style="--fuy-rise-delay: 360ms">
          <h2 class="login-panel-title">富云医院信息系统 · 请登录</h2>
          <!-- 描边墨印「签」：登录即签认身份（墨印承标记；朱印只承危急，永不装饰） -->
          <span class="login-seal" aria-hidden="true">签</span>
        </header>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <el-form-item
            class="login-rise"
            style="--fuy-rise-delay: 400ms"
            label="登录名"
            prop="loginName"
          >
            <el-input
              v-model="form.loginName"
              size="large"
              placeholder="请输入登录名"
              autocomplete="username"
              :prefix-icon="User"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>
          <el-form-item
            class="login-rise"
            style="--fuy-rise-delay: 440ms"
            label="口令"
            prop="password"
          >
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              show-password
              placeholder="请输入口令"
              autocomplete="current-password"
              :prefix-icon="Lock"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>
          <el-form-item class="login-submit-item login-rise" style="--fuy-rise-delay: 480ms">
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
        <p class="login-audit login-rise" style="--fuy-rise-delay: 520ms">
          登录行为纳入审计日志 · 留存不少于六个月
        </p>
      </section>
    </main>
  </div>
</template>

<style scoped>
/* ============================================================
   登录页 v2 · 全视口沉浸式两栏构图（批次 2 册 1 契约 §2）
   ------------------------------------------------------------
   语义：翻开当日病案前的身份确认页——全站唯一全视口页面，「纸质病案」
   世界的门厅：左栏=病历夹墨脊封皮（世界叙事面），右栏=摊开的病案纸
   工作面（表单工作面）。医护推门进来先站在这页纸上，身份签认后才被
   领入墨脊书脊的工作世界。
   红线：零新色值（--fuy-* token 实名或 DESIGN.md 既有字面量，逐处注明）；
   零 box-shadow（EP 输入围合的 inset box-shadow 为描边机制非投影，过渡
   通道沿用；本页无弹层无投影）；印泥朱只承校验错误态；组件内零
   @keyframes（唯一来源 motion.css）；表单逻辑零改动（模型/校验/提交/
   store 交互全保留），本块只承载视觉与动效消费。
   ============================================================ */

/* ---------- 全视口两栏骨架：左墨脊封皮 + 右纸面工作面 ---------- */
.login-split {
  /* 全视口沉浸锁（V3 真机修订）：app 全局无 body margin 归零（样稿 paper-chart.css
     第 58 行通配 reset，本 app 无对应层），文档流下登录面四周漏出 8px 白边且整页
     16px 外层滚动（docH 916 > 视口 900），破坏契约 §2「全站唯一全视口页面」。
     fixed + inset 0 把页面精确钉满视口，改动收敛在本页根元素、零全局副作用；
     桌面 overflow hidden（进场插签自下缘升入不露外滚轴），窄窗堆叠态转容器内滚 */
  position: fixed;
  inset: 0;
  display: grid;
  grid-template-columns: minmax(520px, 6fr) minmax(0, 7fr);
  overflow: hidden;
}

/* ============================================================
   左栏 · 世界叙事面（墨脊书脊＝病历夹封皮）
   ============================================================ */
.login-spine {
  position: relative;
  display: flex;
  flex-direction: column;
  background: var(--fuy-shell-bg);
  color: var(--fuy-shell-text);
  /* 书脊深墨缝：缝跟着面走——墨脊上的缝是缝在封皮上的深墨，不用灰线 */
  border-right: 1px solid var(--fuy-shell-hairline);
}

/* 品牌头：与壳层导轨品牌头 56px 同高同形态的放大呼应（rhyme，非复制）；
   水平内边距与叙事面正文同源 clamp——样稿品牌头固定 40px 在宽窗下与正文
   5vw 左缘错位，此处对齐后封皮左缘一条垂直网格线贯穿（实现增益） */
.spine-brand {
  height: 56px;
  flex: none;
  display: flex;
  align-items: center;
  padding: 0 clamp(40px, 5vw, 72px);
  background: var(--fuy-shell-bg);
  border-bottom: 1px solid var(--fuy-shell-hairline);
}

/* 品牌字标「富云」：亮纸白纸块 + 墨字（与导轨 brand-mark 同一物件，封皮尺度
   放大）；字距外扩以 text-indent 补偿尾随字距保证两字视觉居中 */
.spine-brand-mark {
  display: inline-flex;
  align-items: center;
  height: 40px;
  padding: 0 var(--fuy-space-4);
  background: var(--fuy-surface-card);
  color: var(--fuy-palette-brand-900);
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
  letter-spacing: 8px;
  text-indent: 8px;
  border-radius: var(--fuy-radius-md);
}

/* 叙事主体：垂直居中于品牌头与标签架之间的墨色旷野（沉浸呼吸） */
.spine-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: var(--fuy-space-12) clamp(40px, 5vw, 72px) var(--fuy-space-10);
}

.spine-content {
  max-width: 30em;
}

/* 系统名：门牌标题档（20px/600）落在墨脊上取书脊纸纱字 */
.spine-sysname {
  margin: 0;
  font-size: var(--fuy-font-size-2xl);
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: 2px;
  color: var(--fuy-shell-text);
}

/* 世界叙事文案：疏朗行距给出封皮排印的仪式感，纸纱字不抢字标 */
.spine-narrative {
  margin: var(--fuy-space-5) 0 0;
  font-size: var(--fuy-font-size-md);
  line-height: 2;
  color: var(--fuy-shell-text);
}

/* 「谁·何时」批注组：描边墨印「启」+ 批注两行——启封印承「翻开当日病案」
   语义（朱印只承危急，身份标记用墨印；墨脊上即墨不可见，沿导轨焦点环同款
   推论取纸纱白描边）；批注行沿「谁·何时」语法，签认人登录前未成立以 — 占位
   （DESIGN.md 门牌批注「空值以 — 占位」语法），开卷时刻取运行时真实日期 */
.spine-seal-row {
  margin-top: var(--fuy-space-12);
  display: flex;
  align-items: center;
  gap: var(--fuy-space-6);
}

.spine-seal {
  flex: none;
  width: 88px;
  height: 88px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid rgba(253, 252, 248, 0.62); /* 书脊弱字（DESIGN.md shell-dim 既有字面量，tokens.css 无实名） */
  border-radius: var(--fuy-radius-sm);
  color: rgba(253, 252, 248, 0.62); /* 同上 shell-dim 既有字面量 */
  font-size: 40px;
  font-weight: 600;
  /* 手钤印微倾：印章家族姿态（印章 rotate(-2deg) 同语法放大档） */
  transform: rotate(-6deg);
}

.spine-seal-label {
  margin: 0;
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  letter-spacing: 1px;
  color: rgba(253, 252, 248, 0.62); /* 同上 shell-dim 既有字面量 */
}

.spine-note {
  margin: var(--fuy-space-2) 0 0;
  font-size: var(--fuy-font-size-xs);
  color: rgba(253, 252, 248, 0.62); /* 同上 shell-dim 既有字面量 */
}

.spine-placeholder {
  padding: 0 2px;
}

/* 开卷时刻：日期数字走全站 tabular-nums（:root 继承），占位与数字同灰度不抢印 */
.spine-note-time {
  font-variant-numeric: tabular-nums;
}

/* ============================================================
   左栏 · 病案标签架 + 纸层意象（封皮底部铅封构图，纯 CSS 几何）
   语义：登录前先看见封皮里待翻的病案——五枚标签 = 五大业务域（真实产品
   域，零伪数据），三层纸缘 = 封皮内页块的层进透视（纸白三级亮度差，零投影）
   ============================================================ */
.spine-chart {
  flex: none;
  margin-top: auto;
}

.chart-tabs {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  padding: 0 48px;
}

.chart-tab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 36px;
  padding: 0 14px;
  background: var(--fuy-surface-card);
  border: 1px solid var(--fuy-color-rule-stone);
  border-bottom: 0;
  border-radius: var(--fuy-radius-sm) var(--fuy-radius-sm) 0 0;
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  letter-spacing: 2px;
}

/* 相邻标签高低错落：病案夹签的真实排布姿态 */
.chart-tab:nth-child(even) {
  height: 31px;
}

/* 纸层意象：封皮内页块的顶缘三级——最亮纸面在上、卡面次之、工作面最沉，
   层间石规缝分页；逐层内收表达层进透视 */
.chart-paper {
  padding: 0 var(--fuy-space-10);
}

.chart-paper-layer {
  border-radius: var(--fuy-radius-sm) var(--fuy-radius-sm) 0 0;
}

.chart-paper-layer--1 {
  height: 12px;
  background: var(--fuy-surface-input);
}

.chart-paper-layer--2 {
  height: 10px;
  margin: 0 var(--fuy-space-2);
  background: var(--fuy-surface-card);
  border-top: 1px solid var(--fuy-color-rule-stone);
}

.chart-paper-layer--3 {
  height: 8px;
  margin: 0 var(--fuy-space-4);
  background: var(--fuy-surface-page);
  border-top: 1px solid var(--fuy-color-rule-stone);
}

/* ============================================================
   右栏 · 表单工作面（纸面三级：工作面沉底，登录表为搁在纸上的卡面）
   ============================================================ */
.login-face {
  position: relative;
  display: grid;
  place-items: center;
  padding: var(--fuy-space-12) var(--fuy-space-10) var(--fuy-space-16);
  background: var(--fuy-surface-page);
}

.login-panel {
  width: 400px;
  max-width: 100%;
  background: var(--fuy-surface-card);
  border: var(--fuy-border-panel); /* 1px 石规围合（面板级规线） */
  /* 契约 §2：表单区 2px 墨规收底——页级压章线，覆盖下缘石规 */
  border-bottom: 2px solid var(--fuy-color-text-emphasis);
  border-radius: var(--fuy-radius-lg);
  padding: 32px 32px 28px;
  /* 错误态朱收编：EP 错误输入域描边/聚焦描边与校验文字默认取 --el-color-danger
     珊瑚红 #f56c6c，属 off-world 杂色且与印泥朱双红并立；作用域内供 EP 消费
     fuy 值（正向映射方向；scoped 就地下发沿现役先例，全局承载方式的登记归
     element-plus.css 权限，本页不动），统一为印泥朱——朱承校验状态 */
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

/* 首字段与卡头发丝线拉开表单行距（与 EP 表单行 18px 节奏同档） */
.login-panel .el-form-item:first-of-type {
  margin-top: 18px;
}

/* 字段排印只调视觉（EP 校验通道不动）：label 灰墨 13px；错误提示与必填星号收编
   为印泥朱——朱承必填/校验状态语义非装饰；EP 内部元素经 scoped :deep 定点收编
   （现役先例，非全局裸改 .el-*） */
.login-panel :deep(.el-form-item__label) {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

/* 错误提示入场节奏：上 4px 沉落 + 显影（motion.css fuy-login-note-in，弹层硬
   snap 语法的域内同源变体），base 档 enter 缓动——错误每次出现都有入场不跳变 */
.login-panel :deep(.el-form-item__error) {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
  animation: fuy-login-note-in var(--fuy-motion-base) var(--fuy-ease-enter) backwards;
}

.login-panel :deep(.el-form-item.is-required:not(.is-no-asterisk) .el-form-item__label::before) {
  color: var(--fuy-color-danger-text);
}

/* 输入域描边/聚焦微交互（120ms fast 档）：EP 以 inset box-shadow 通道代 border
   （描边机制非投影立体感），故过渡属性为 box-shadow；悬停描边经映射层即灰墨
   （--el-border-color-hover）、聚焦即浓墨（--el-input-focus-border→primary），
   reduced-motion 由 motion.css 全局兜底直达终态 */
.login-panel :deep(.el-input__wrapper) {
  transition: box-shadow var(--fuy-motion-fast) var(--fuy-ease-standard);
}

/* 聚焦墨环：描边转墨之外补 2px 即墨 outline（样稿 focus-within 环同语法）——
   登录是首试必须成功的表单，聚焦可感性强于常规工作面；is-focus 随 EP 聚焦态
   挂载，键盘/鼠标同一可见环 */
.login-panel :deep(.el-input__wrapper.is-focus) {
  outline: 2px solid var(--fuy-color-brand);
  outline-offset: 2px;
}

/* 域图标随域转墨：prefix 图标默认弱墨（映射层占位符档），聚焦随围合转浓墨，
   色彩过渡 120ms fast 档（EP 默认 all .3s 慢半档，收编为世界动效语法） */
.login-panel :deep(.el-input__prefix),
.login-panel :deep(.el-input__suffix) {
  transition: color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.login-panel :deep(.el-input__wrapper.is-focus .el-input__prefix) {
  color: var(--fuy-color-brand);
}

/* 口令可见性切换悬停反馈对齐样稿浓墨档（EP 默认灰墨 clear-hover 档），墨印
   View/Hide 图标对由 EP show-password 内建承载（零自绘零新 DOM） */
.login-panel :deep(.el-input__password:hover) {
  color: var(--fuy-color-text-emphasis);
}

/* 错误态聚焦环随朱（状态语义，样稿 is-error:focus-within 同语法） */
.login-panel :deep(.el-form-item.is-error .el-input__wrapper.is-focus) {
  outline-color: var(--fuy-color-danger-text);
}

/* 浏览器自动填充收编（浏览器原生面纪律）：Chrome/Edge autofill 私绘淡蓝底会
   破纸面，-webkit-text-fill-color 锁定正文墨色保可读性（背景反绘的常规手段
   是 inset box-shadow，触本页零投影红线，故仅锁文字通道并如实登记局限） */
.login-panel :deep(.el-input__inner:-webkit-autofill) {
  -webkit-text-fill-color: var(--fuy-color-text-emphasis);
  caret-color: var(--fuy-color-brand);
}

/* 提交行：与口令域保持 26px 呼吸距（18px 表单行距 + 8px 增量），行底距交给审计注记 */
.login-submit-item {
  margin-top: var(--fuy-space-2);
  margin-bottom: 0;
}

/* 登录按钮＝「墨即操作」：EP primary 映射后即浓墨实底，悬停/按压档位沿映射层
   （light-3 #626a7c / dark-2 #182236）；悬停微浮 1px / 按压落位走 transform
   通道（禁 width/height 动画），背景与位移过渡统一 120ms fast 档 */
.login-submit {
  width: 100%;
  /* 门面按钮 40px 与 large 输入域同高：主操作与字段行同构，门面右缘节奏收齐 */
  height: 40px;
  /* 加载态保浓墨纯底：EP is-loading 以 --el-mask-color-extra-light（默认 50% 白纱）
     叠加按钮，浓墨会被洗成灰紫、白色转轮对比塌陷；就地下发透明压制白纱，加载中
     保持样稿「浓墨底 + 白转轮」的墨块形态（scoped 变量通道，非裸改 .el-*） */
  --el-mask-color-extra-light: transparent;
  transition:
    background-color var(--fuy-motion-fast) var(--fuy-ease-standard),
    transform var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.login-submit:hover {
  transform: translateY(-1px);
}

.login-submit:active {
  transform: translateY(0);
}

/* 禁用/加载中不浮动：is-loading 已 pointer-events:none 免疫悬停，is-disabled
   仍会命中 :hover，置其后同特异性覆盖归零位移 */
.login-submit.is-disabled {
  transform: none;
}

.login-submit-text {
  letter-spacing: 4px;
  padding-left: 4px;
}

/* 键盘焦点环恢复即墨：EP 按钮自有 :focus-visible 描边（主色混白档，对卡面对比
   不足 3:1）特异性压过 tokens.css 全站即墨环——scoped 定点恢复「2px 墨 outline」
   全站语法 */
.login-submit:focus-visible {
  outline-color: var(--fuy-color-brand);
}

/* 合规注记：等保三级审计背景的真实性质提示（PRODUCT.md），弱墨居中不抢操作 */
.login-audit {
  margin: var(--fuy-space-4) 0 0;
  font-size: var(--fuy-font-size-xs);
  color: #676d7b; /* 弱墨脚注（DESIGN.md ink-faint 既有字面量，tokens.css 无实名） */
  text-align: center;
}

/* ============================================================
   进场编排（契约 §2 核心维度）——时间轴沿用样稿（已过独立验证的最低设计要求）：
     60ms   品牌字标（左栏第一笔）
     100ms  系统名
     140ms  世界叙事文案
     180ms  启封印 + 批注组（左栏叙事收束）
     220ms  纸层意象（封皮内页块浮现）
     260~420ms 标签架五签逐枚插签入档（40ms 步长 5 档，签名时刻）
     300ms  右栏登录卡整纸浮现（叙事先落、表单后至）
     360ms  卡头副题 → 400ms 登录名域 → 440ms 口令域
     480ms  登录按钮 → 520ms 审计注记
   keyframes 消费 motion.css 全局 fuy-rise / fuy-login-tab-in（组件内零定义）；
   只动 transform/opacity。fill-mode 取 backwards：delay 期压 from 态（透明下沉），
   动画结束后元素回到自然态——样稿 both 会在终态永久保留 translateY(0)，使容器
   长期充当绝对定位包含块（错误提示定位域被锁死），backwards 是视觉等效且零残留
   的等价实现（实现增益：渲染性能与层叠卫生）；
   reduced-motion 由 motion.css 全局兜底（时长与延迟双归零）直达终态
   ============================================================ */
.login-rise {
  animation: fuy-rise var(--fuy-motion-slow) var(--fuy-ease-enter) backwards;
  animation-delay: var(--fuy-rise-delay, 0ms);
}

.login-tab-rise {
  animation: fuy-login-tab-in var(--fuy-motion-slow) var(--fuy-ease-enter) backwards;
  animation-delay: var(--fuy-rise-delay, 0ms);
}

/* ---------- 窄窗保底（非响应式设计：workstation 以 1440+ 桌面为唯一目标形态，
   此处仅防破版，纵向堆叠两栏） ---------- */
@media (max-width: 960px) {
  .login-split {
    grid-template-columns: 1fr;
    /* 堆叠后内容可能超一屏：桌面 hidden 收紧为容器内滚动（全视口钉满不变） */
    overflow: auto;
  }

  .login-spine {
    border-right: 0;
    border-bottom: 1px solid var(--fuy-shell-hairline);
  }

  .spine-body {
    padding-top: var(--fuy-space-10);
    padding-bottom: var(--fuy-space-8);
  }

  .spine-chart {
    margin-top: var(--fuy-space-10);
  }

  .login-face {
    min-height: 640px;
  }
}
</style>
