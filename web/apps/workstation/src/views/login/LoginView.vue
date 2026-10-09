<script setup lang="ts">
// 登录页（批次 2 册 1 契约 §2 v3「暖纸卷宗 + 朱砂印鉴」门面）：登录即「启封当日病案」，
// stage 双栏卡片（左封面 / 右病案内页）+ 外部留白六层动效底纹 + 页面级朱砂走线。
// 表单逻辑零改动红线：form 模型 / rules 校验 / useTemplateRef / authStore.login /
// submitting 状态 / redirect 站内跳转防御 / @keyup.enter 全保留；失败提示由响应
// 拦截器统一弹出（web A.3-2），本组件不重复弹错。视觉收编走 scoped :deep 通道
// （EP el-form/el-form-item/el-input 结构与 v-model/prop/show-password 通道不动）。
// 成功反馈：登录 resolve 后「启封成功」钤印浮层，≤1000ms 编排后执行既有跳转；
// 失败路径（catch）不显示浮层。
import { onMounted, onUnmounted, reactive, ref } from 'vue';
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
/** 成功钤印浮层显示态：登录 resolve 后置真，编排完成后跳转；失败路径保持假 */
const stampShown = ref(false);
/** 钤印后跳转的延时句柄：组件卸载时清理，防卸载后仍触发导航 */
let stampTimer: number | undefined;

/**
 * 偏好减少动效判定：jsdom/旧环境无 matchMedia 时按「未启用」处理（兜底不增强）。
 * @return 为 true 时浮层直达终态并缩短跳转编排延迟
 */
function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

/** 提交登录：校验 → store.login → 钤印浮层编排 → 按回跳地址跳转；失败仅终止流程（弹错归拦截器） */
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
    // 钤印浮层先盖（≤1000ms 编排，reduced-motion 直达终态并缩短延迟），再执行既有跳转
    stampShown.value = true;
    stampTimer = window.setTimeout(
      () => {
        // 与原同步 await 口径一致：导航失败（被守卫/重复导航中止）静默终止，弹错归拦截器
        router.push(redirect).catch(() => {});
      },
      prefersReducedMotion() ? 300 : 1000,
    );
  } catch {
    // 登录失败（凭据错误/锁定/停用）提示已由响应拦截器统一弹出，此处仅终止跳转与钤印
  } finally {
    submitting.value = false;
  }
}

/* ---------- 外部底纹鼠标视差（四层差速 rAF 插值，样稿编排原样）---------- */
// 仅 pointer:fine 且非 prefers-reduced-motion 启用；容器 transform 与容器内子元素
// 动画（keyframes 消费 motion.css fuy-login-*）分层不冲突。卸载必须
// cancelAnimationFrame + 解绑监听（web B.2-6 副作用清理）。
const bgInkRef = useTemplateRef<HTMLElement>('bgInkRef');
const bgSealRef = useTemplateRef<HTMLElement>('bgSealRef');
const bgCloudsRef = useTemplateRef<HTMLElement>('bgCloudsRef');
const bgCharsRef = useTemplateRef<HTMLElement>('bgCharsRef');
/** 视差停止器：onMounted 装配、onUnmounted 消费（null = 视差未启用） */
let parallaxStop: (() => void) | null = null;

onMounted(() => {
  // jsdom 等无 matchMedia 环境直接跳过（测试环境无指针设备）
  if (typeof window.matchMedia !== 'function') {
    return;
  }
  if (!window.matchMedia('(pointer: fine)').matches || prefersReducedMotion()) {
    return;
  }
  const layers = [
    { el: bgCloudsRef.value, fx: 22, fy: 14 },
    { el: bgSealRef.value, fx: -14, fy: -10 },
    { el: bgCharsRef.value, fx: 8, fy: 6 },
    { el: bgInkRef.value, fx: 5, fy: 4 },
  ].filter((layer): layer is { el: HTMLElement; fx: number; fy: number } => layer.el !== null);
  if (layers.length === 0) {
    return;
  }
  // 目标位/当前位分离 + 0.04 插值系数：指针位移被缓动摊开成跟手感（样稿原参数）
  let targetX = 0;
  let targetY = 0;
  let currentX = 0;
  let currentY = 0;
  let rafId = 0;
  const onMove = (event: MouseEvent): void => {
    targetX = event.clientX / window.innerWidth - 0.5;
    targetY = event.clientY / window.innerHeight - 0.5;
  };
  const tick = (): void => {
    currentX += (targetX - currentX) * 0.04;
    currentY += (targetY - currentY) * 0.04;
    for (const layer of layers) {
      layer.el.style.transform = `translate3d(${(currentX * layer.fx).toFixed(2)}px, ${(currentY * layer.fy).toFixed(2)}px, 0)`;
    }
    rafId = requestAnimationFrame(tick);
  };
  window.addEventListener('mousemove', onMove, { passive: true });
  rafId = requestAnimationFrame(tick);
  parallaxStop = () => {
    cancelAnimationFrame(rafId);
    window.removeEventListener('mousemove', onMove);
  };
});

onUnmounted(() => {
  // 视差与钤印跳转定时器都是本组件注册的全局副作用：卸载即清，防泄漏与卸载后导航
  parallaxStop?.();
  parallaxStop = null;
  if (stampTimer !== undefined) {
    window.clearTimeout(stampTimer);
    stampTimer = undefined;
  }
});
</script>

<template>
  <div class="login-page">
    <!-- 纸纹背景层：方胜纹重复 + 暖纸渐变 + 噪点 multiply（全页纸底） -->
    <div class="login-paper-bg" aria-hidden="true"></div>

    <!-- 外部留白 · 六层动效底纹（z:0，被 stage 遮挡，只在四周空白处可见）；
         前四层容器为鼠标视差作用面（差速位移，onMounted 装配） -->
    <div ref="bgInkRef" class="login-bg-ink" aria-hidden="true">
      <i class="login-ink-blob b1"></i><i class="login-ink-blob b2"></i
      ><i class="login-ink-blob b3"></i>
    </div>
    <div ref="bgSealRef" class="login-bg-seal" aria-hidden="true">
      <svg class="login-seal s1" viewBox="0 0 200 200">
        <circle cx="100" cy="100" r="96" fill="none" stroke="currentColor" stroke-width="1.6" />
        <circle
          cx="100"
          cy="100"
          r="84"
          fill="none"
          stroke="currentColor"
          stroke-width="1"
          stroke-dasharray="10 6"
        />
        <text
          x="100"
          y="120"
          text-anchor="middle"
          font-size="58"
          fill="currentColor"
          font-family="'Ma Shan Zheng','KaiTi',serif"
        >
          云
        </text>
      </svg>
      <svg class="login-seal s2" viewBox="0 0 200 200">
        <circle cx="100" cy="100" r="96" fill="none" stroke="currentColor" stroke-width="1.4" />
        <circle
          cx="100"
          cy="100"
          r="84"
          fill="none"
          stroke="currentColor"
          stroke-width="1"
          stroke-dasharray="8 5"
        />
        <text
          x="100"
          y="120"
          text-anchor="middle"
          font-size="58"
          fill="currentColor"
          font-family="'Ma Shan Zheng','KaiTi',serif"
        >
          墨
        </text>
      </svg>
    </div>
    <div ref="bgCloudsRef" class="login-bg-clouds" aria-hidden="true">
      <svg class="login-cloud c1" viewBox="0 0 120 64" fill="none">
        <path
          d="M32 52 c-14 0 -22 -8 -22 -18 c0 -10 10 -18 21 -16 c2 -12 15 -19 27 -15 c9 3 13 11 11 19 c9 1 15 8 13 16 c-2 9 -10 14 -23 14 z"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        />
        <path
          d="M22 36 c0 -6 5 -10 11 -9 c4 1 6 6 4 10"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
        />
        <path
          d="M70 58 q14 -4 28 0"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
          opacity=".7"
        />
      </svg>
      <svg class="login-cloud c2" viewBox="0 0 100 48" fill="none">
        <path
          d="M26 40 c-11 0 -17 -6 -17 -14 c0 -8 8 -14 17 -12 c2 -9 12 -14 21 -11 c7 2 10 9 8 15 c7 1 11 6 10 12 c-1 7 -8 10 -19 10 z"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        />
      </svg>
      <svg class="login-cloud c3" viewBox="0 0 120 64" fill="none">
        <path
          d="M32 52 c-14 0 -22 -8 -22 -18 c0 -10 10 -18 21 -16 c2 -12 15 -19 27 -15 c9 3 13 11 11 19 c9 1 15 8 13 16 c-2 9 -10 14 -23 14 z"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        />
        <path
          d="M22 36 c0 -6 5 -10 11 -9 c4 1 6 6 4 10"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
        />
      </svg>
    </div>
    <div ref="bgCharsRef" class="login-bg-chars" aria-hidden="true">
      <span class="login-fchar f1">墨</span>
      <span class="login-fchar f2">云</span>
      <span class="login-fchar f3">卷</span>
    </div>
    <div class="login-bg-waves" aria-hidden="true">
      <div class="login-wave w1"></div>
      <div class="login-wave w2"></div>
    </div>
    <div class="login-bg-sheen" aria-hidden="true"></div>

    <!-- 页面级边框：静态底线 + 朱砂走线（行军线） + 四角回字纹 -->
    <div class="login-page-frame" aria-hidden="true"></div>
    <svg class="login-page-flow" aria-hidden="true">
      <rect x="1" y="1" rx="10" width="100%" height="100%" />
    </svg>
    <i class="login-pcorner tl" aria-hidden="true"></i>
    <i class="login-pcorner tr" aria-hidden="true"></i>
    <i class="login-pcorner bl" aria-hidden="true"></i>
    <i class="login-pcorner br" aria-hidden="true"></i>

    <!-- stage 双栏卡片：左=卷宗封面（品牌/书法题字/业务域标签/山水远景/页脚钤印），
         右=病案内页（菱格底纹/四角花角/边栏云纹/表头/分隔/表单/审计） -->
    <main class="login-stage">
      <!-- 左 · 封面 -->
      <section class="login-cover">
        <div class="login-cover-deco" aria-hidden="true"></div>

        <div class="login-cover-top">
          <div class="login-brand login-rise" style="--login-rise-delay: 100ms">
            <div class="login-brand-mark" aria-hidden="true">富</div>
            <div class="login-brand-text">
              <span class="login-brand-title">富云医院信息系统</span>
              <span class="login-brand-sub">FUYUN HIS · PAPER ARCHIVE</span>
            </div>
          </div>

          <div class="login-cover-title login-rise" style="--login-rise-delay: 250ms">
            <h1 class="login-hero">以墨为凭 · 以纸为证</h1>
            <!-- 世界叙事文案：真实产品语义（全院业务闭环 + 留痕归档），禁 lorem 禁伪数据 -->
            <p class="login-tagline">
              门诊、住院、药房与护理的临床文书，在同一份病案上闭环流转——写下即留痕，签认即归档。
            </p>
          </div>

          <!-- 五业务域签牌（真实产品域，纯装饰不承操作） -->
          <div class="login-tabs login-rise" style="--login-rise-delay: 400ms" aria-hidden="true">
            <span class="login-tab">门诊</span>
            <span class="login-tab">住院</span>
            <span class="login-tab">药房</span>
            <span class="login-tab">护理</span>
            <span class="login-tab">收费</span>
          </div>
        </div>

        <!-- 山水远景：SVG 线描（远山/祥云/朱日/水波），纯装饰 -->
        <svg class="login-landscape" viewBox="0 0 460 190" fill="none" aria-hidden="true">
          <circle cx="332" cy="84" r="24" stroke="#a5352c" stroke-width="2" opacity=".45" />
          <circle
            cx="332"
            cy="84"
            r="31"
            stroke="#a5352c"
            stroke-width="1"
            opacity=".22"
            stroke-dasharray="3 7"
          />
          <g stroke="#a5352c" stroke-width="1.8" stroke-linecap="round" opacity=".5">
            <path
              d="M96 74 c-11 0 -18 -6 -18 -14 c0 -8 8 -14 16 -12 c1 -9 10 -15 19 -12 c7 2 10 9 8 15 c7 1 11 6 10 12 c-1 7 -7 11 -16 11 z"
            />
            <path d="M88 60 c0 -5 4 -8 8 -7 c4 1 5 5 3 8" />
            <path d="M150 92 c10 -3 20 -3 30 0" />
            <path d="M156 102 c7 -2 14 -2 21 0" stroke-width="1.5" opacity=".8" />
          </g>
          <g stroke="#8a7a5e" stroke-width="1.4" opacity=".35">
            <path
              d="M300 108 c-9 0 -14 -5 -14 -11 c0 -6 6 -11 13 -10 c1 -7 8 -12 15 -9 c5 2 8 7 6 12 c5 1 8 5 7 9 c-1 6 -5 9 -13 9 z"
            />
          </g>
          <g stroke="#5a4c38" stroke-linecap="round" fill="none">
            <path
              d="M18 148 Q64 98 108 126 Q136 84 178 118 Q214 96 258 136"
              stroke-width="2"
              opacity=".5"
            />
            <path
              d="M60 148 Q120 120 168 140 Q230 112 300 142 Q368 122 442 146"
              stroke-width="1.5"
              opacity=".32"
            />
          </g>
          <g stroke="#b08d4f" stroke-linecap="round" fill="none">
            <path
              d="M8 166 q13 -8 26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0"
              stroke-width="1.6"
              opacity=".55"
            />
            <path
              d="M20 178 q13 -7 26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0 t26 0"
              stroke-width="1.2"
              opacity=".35"
            />
          </g>
        </svg>

        <div class="login-cover-foot login-rise" style="--login-rise-delay: 550ms">
          <!-- 封面元数据：产品真实语义（门面身份 + 签认入口），竖排朱印承「启封有据」 -->
          <div class="login-meta">
            富云 HIS · 纸质病案<br />
            医护签认入口 · 全链路留痕归档
          </div>
          <div class="login-vertical-seal" aria-hidden="true">启封有据</div>
        </div>
      </section>

      <!-- 右 · 病案内页（登录表单：EP 通道零改动，视觉经 scoped :deep 收编样稿形态） -->
      <section class="login-sheet">
        <div class="login-sheet-texture" aria-hidden="true"></div>
        <i class="login-ornament c1" aria-hidden="true"></i>
        <i class="login-ornament c2" aria-hidden="true"></i>
        <i class="login-ornament c3" aria-hidden="true"></i>
        <i class="login-ornament c4" aria-hidden="true"></i>
        <div class="login-margin-clouds" aria-hidden="true"></div>
        <div class="login-margin-note" aria-hidden="true">闭环流转 · 永续留痕 · 签认归档</div>

        <div class="login-sheet-head login-rise" style="--login-rise-delay: 350ms">
          <div class="login-sheet-title">
            <h2>启 · 当日病案</h2>
            <span class="login-sheet-en">UNSEAL TODAY'S RECORD</span>
          </div>
          <div class="login-sheet-status">
            <div class="login-status-pill">
              <span class="login-status-dot" aria-hidden="true"></span>
              待启封
            </div>
            <!-- 「谁·何时」批注语法：签认人登录前身份未成立、开卷时刻未到均以 — 占位（零伪数据） -->
            <div>启封批注 · 签认人 —</div>
            <div>开卷时刻 —</div>
          </div>
        </div>

        <div class="login-divider login-rise" style="--login-rise-delay: 450ms" aria-hidden="true">
          <i class="login-div-cloud dl"></i>
          <i class="login-div-cloud dr"></i>
        </div>

        <el-form
          ref="formRef"
          class="login-form login-rise"
          style="--login-rise-delay: 550ms"
          :model="form"
          :rules="rules"
          label-position="top"
          require-asterisk-position="right"
        >
          <el-form-item prop="loginName">
            <template #label>
              <span>登录名</span>
              <span class="login-field-hint">工号或归档编号</span>
            </template>
            <el-input
              v-model="form.loginName"
              size="large"
              placeholder="请输入登录名"
              autocomplete="username"
              :prefix-icon="User"
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
              :prefix-icon="Lock"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>
          <!-- 提交按钮＝载体替换（样稿朱砂实底形态，原样稿演示于原生 button）：
               onClick 调既有 handleSubmit、:disabled 绑 submitting，提交逻辑零改动 -->
          <el-form-item class="login-submit-item">
            <button
              type="button"
              class="login-submit"
              :class="{ 'is-loading': submitting }"
              :disabled="submitting"
              @click="handleSubmit"
            >
              <span class="login-submit-ring fuy-loading-essential" aria-hidden="true"></span>
              <span class="login-submit-label">
                <svg
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
                  <polyline points="10 17 15 12 10 7" />
                  <line x1="15" y1="12" x2="3" y2="12" />
                </svg>
                登 录
              </span>
            </button>
          </el-form-item>

          <!-- 合规注记：等保三级审计背景的真实性质提示，金左线提示条贴底收尾 -->
          <div class="login-audit login-rise" style="--login-rise-delay: 650ms">
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
            登录行为纳入审计日志 · 留存不少于六个月
          </div>
        </el-form>
      </section>
    </main>

    <!-- 成功钤印浮层：登录 resolve 后盖「启封成功」朱印，≤1000ms 编排后跳转；
         失败路径不显示；reduced-motion 下过渡直达终态、延迟缩短（脚本侧） -->
    <div class="login-stamp-toast" :class="{ 'is-show': stampShown }" aria-live="polite">
      启封成功
    </div>
  </div>
</template>

<style scoped>
/* ============================================================
   登录页 v3 ·「暖纸卷宗 + 朱砂印鉴」门面（批次 2 册 1 契约 §2 v3）
   ------------------------------------------------------------
   语义：登录即「启封当日病案」——全站唯一全视口页面，纸质卷宗世界的
   门厅：医护在案卷封面上签认身份，翻开内页进入工作世界。
   色域（契约 v3 登录页局部色域）：只用样稿 :root 十值及其 alpha 派生，
   零新增色值；全站 tokens.css 零变动。样稿辅助字面量（#fdf6e3 纸亮字、
   #f7f0e0 系渐变档、rgba(60,46,28) 阴影棕）按「十值 alpha 派生」等价
   收敛（纸白字→--paper、渐变档→paper/paper-deep 派生、阴影棕→墨 alpha），
   逐处登记见实现报告偏差清单。
   红线：表单逻辑零改动；keyframes 全在 motion.css（fuy-login- 前缀，
   组件内零 @keyframes）；动效仅 transform/opacity（走线行军为契约
   显式例外）；blur 滤镜仅静态常驻；字体零外链（子集 woff2 本地化）；
   EP 收编走 scoped :deep 与页内变量下发（v2 先例，非裸改 .el-*）。
   ============================================================ */

/* ---------- 门面字体：字符子集 woff2 本地化（零外链，font-display swap） ----------
   用字集 = 样稿全文 + 模板动态文案共 147 字（含数字/周序/大写拉丁/标点），
   经 css2 API text= 子集化；weight 档位按样稿实际消费精简为 400/600/700（grep 实证：
   样稿仅 brand-title/sheet-title/必填星 700 与提交按钮 600，正文 400） */
@font-face {
  font-family: 'Noto Serif SC';
  font-style: normal;
  font-weight: 400;
  font-display: swap;
  src: url('../../assets/fonts/noto-serif-sc-400.subset.woff2') format('woff2');
}
@font-face {
  font-family: 'Noto Serif SC';
  font-style: normal;
  font-weight: 600;
  font-display: swap;
  src: url('../../assets/fonts/noto-serif-sc-600.subset.woff2') format('woff2');
}
@font-face {
  font-family: 'Noto Serif SC';
  font-style: normal;
  font-weight: 700;
  font-display: swap;
  src: url('../../assets/fonts/noto-serif-sc-700.subset.woff2') format('woff2');
}
@font-face {
  font-family: 'Ma Shan Zheng';
  font-style: normal;
  font-weight: 400;
  font-display: swap;
  src: url('../../assets/fonts/ma-shan-zheng-400.subset.woff2') format('woff2');
}

/* ---------- 页根：全视口锁 + 登录页局部色域（样稿十值） + EP 页内变量下发 ---------- */
.login-page {
  /* 样稿 :root 十值（契约 §2 v3 唯一色板）——本页全部色彩仅取以下变量及其 alpha */
  --login-paper: #f4ecdb;
  --login-paper-deep: #eadfc8;
  --login-paper-edge: #d8c9a8;
  --login-ink: #2a2318;
  --login-ink-soft: #5a4c38;
  --login-ink-faint: #8a7a5e;
  --login-cinnabar: #a5352c;
  --login-cinnabar-dk: #82251f;
  --login-cinnabar-lt: #c7544a;
  --login-gold: #b08d4f;
  /* 派生量：lineweight = ink-soft alpha；三级阴影 = 墨 alpha（样稿 rgba(60,46,28) 的
     十值收敛）；双缓动沿样稿（out=门面浮入、ink=钤印/下划线） */
  --login-line: rgba(90, 76, 56, 0.22);
  --login-shadow-lg: 0 24px 60px -12px rgba(42, 35, 24, 0.28), 0 4px 12px rgba(42, 35, 24, 0.1);
  --login-shadow-md: 0 10px 24px -6px rgba(42, 35, 24, 0.18);
  --login-shadow-sm: 0 4px 10px rgba(42, 35, 24, 0.1);
  --login-ease-out: cubic-bezier(0.22, 1, 0.36, 1);
  --login-ease-ink: cubic-bezier(0.65, 0.05, 0.36, 1);
  /* 字体栈：子集 woff2 在前，系统宋/楷族兜底（网络字体产物缺失时零外链降级） */
  --login-font-serif: 'Noto Serif SC', 'Songti SC', 'STSong', 'SimSun', Georgia, serif;
  --login-font-brush: 'Ma Shan Zheng', 'KaiTi', 'STKaiti', serif;

  /* EP 通道收编（页内变量下发，v2 同款 scoped 变量通道非裸改 .el-*）：
     校验错误族（必填星/错误描边/错误文字）统一为朱——朱承校验状态；
     EP 文字随页进衬线世界 */
  --el-color-danger: var(--login-cinnabar);
  --el-font-family: var(--login-font-serif);

  /* 全视口沉浸锁（v2 实测经验沿袭）：app 全局无 body margin 归零，fixed + inset 0
     把页面精确钉满视口、改动收敛在本页根元素零全局副作用；桌面 overflow hidden
     （进场浮入不露外滚轴），窄窗堆叠态转容器内滚 */
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  font-family: var(--login-font-serif);
  color: var(--login-ink);
  background: var(--login-paper);
  -webkit-font-smoothing: antialiased;
}

/* ---------- 纸纹背景层：方胜纹 + 暖纸渐变 + 噪点 ---------- */
.login-paper-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}
.login-paper-bg::before {
  content: '';
  position: absolute;
  inset: 0;
  /* 方胜纹（回字套叠）+ 左上提亮/右下压暗径向 + 暖纸三档渐变；
     渐变档用 paper/paper-deep/paper-edge alpha 收敛（样稿 #f7f0e0 系的十值等价），
     底色锚 paper-deep 保证上层纸白 alpha 派生可见（光带/亮纹的亮度来源） */
  background:
    url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='40' height='40'><g fill='none' stroke='%235a4c38' stroke-opacity='.13'><rect x='11' y='11' width='18' height='18'/><rect x='16' y='16' width='8' height='8'/></g></svg>")
      repeat,
    radial-gradient(ellipse 80% 60% at 20% 10%, rgba(244, 236, 219, 0.55), transparent 60%),
    radial-gradient(ellipse 70% 50% at 85% 90%, rgba(90, 76, 56, 0.1), transparent 60%),
    linear-gradient(
      160deg,
      var(--login-paper) 0%,
      var(--login-paper-deep) 45%,
      rgba(216, 201, 168, 0.9) 100%
    );
}
.login-paper-bg::after {
  content: '';
  position: absolute;
  inset: 0;
  opacity: 0.35;
  mix-blend-mode: multiply;
  /* 静态噪点纹理（feTurbulence 灰噪 multiply 压花纸面）——blur/噪声滤镜仅静态常驻不参与动画 */
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='3'/><feColorMatrix type='saturate' values='0'/></filter><rect width='200' height='200' filter='url(%23n)' opacity='0.35'/></svg>");
}

/* ============================================================
   外部留白 · 六层动效底纹（z:0 被 stage 遮挡，四周空白处可见）
   ============================================================ */
.login-bg-ink,
.login-bg-seal,
.login-bg-clouds,
.login-bg-chars,
.login-bg-sheen {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}
/* 视差容器提升为合成层：rAF 逐帧写 transform，预声明免逐帧晋升（渲染性能增益） */
.login-bg-ink,
.login-bg-seal,
.login-bg-clouds,
.login-bg-chars {
  will-change: transform;
}

/* —— 层1：墨晕呼吸 —— */
.login-ink-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px); /* 静态常驻模糊（氛围晕），不参与动画 */
}
.login-ink-blob.b1 {
  width: 420px;
  height: 420px;
  left: -8%;
  top: -10%;
  background: radial-gradient(circle, rgba(176, 141, 79, 0.16), transparent 70%);
  animation: fuy-login-ink-breathe 11s ease-in-out infinite;
}
.login-ink-blob.b2 {
  width: 520px;
  height: 520px;
  right: -12%;
  top: 28%;
  background: radial-gradient(circle, rgba(165, 53, 44, 0.1), transparent 70%);
  animation: fuy-login-ink-breathe 14s 2s ease-in-out infinite;
}
.login-ink-blob.b3 {
  width: 460px;
  height: 460px;
  left: 20%;
  bottom: -18%;
  background: radial-gradient(circle, rgba(90, 76, 56, 0.12), transparent 70%);
  animation: fuy-login-ink-breathe 17s 4s ease-in-out infinite;
}

/* —— 层2：缓旋双印（云/墨） —— */
.login-bg-seal .login-seal {
  position: absolute;
}
.login-bg-seal .s1 {
  top: -70px;
  right: 5%;
  width: 280px;
  height: 280px;
  color: var(--login-cinnabar);
  opacity: 0.07;
  animation: fuy-login-seal-spin 130s linear infinite;
}
.login-bg-seal .s2 {
  bottom: -50px;
  left: 4%;
  width: 220px;
  height: 220px;
  color: var(--login-ink-soft);
  opacity: 0.05;
  animation: fuy-login-seal-spin 170s linear infinite reverse;
}

/* —— 层3：祥云缓移 —— */
.login-cloud {
  position: absolute;
  left: 0;
  will-change: transform;
}
.login-cloud.c1 {
  top: 11%;
  width: 150px;
  opacity: 0.09;
  color: var(--login-cinnabar);
  animation: fuy-login-cloud-drift 70s linear infinite;
}
.login-cloud.c2 {
  top: 55%;
  width: 105px;
  opacity: 0.06;
  color: var(--login-ink-faint);
  animation: fuy-login-cloud-drift 95s 18s linear infinite;
}
.login-cloud.c3 {
  top: 79%;
  width: 180px;
  opacity: 0.05;
  color: var(--login-gold);
  animation: fuy-login-cloud-drift 120s 40s linear infinite;
}

/* —— 层4：浮墨字 —— */
.login-fchar {
  position: absolute;
  top: 100%;
  left: 0;
  font-family: var(--login-font-brush);
  opacity: 0;
  will-change: transform, opacity;
  animation: fuy-login-char-float 30s linear infinite;
}
.login-fchar.f1 {
  left: 4%;
  font-size: 120px;
  color: var(--login-ink);
}
.login-fchar.f2 {
  left: 11%;
  font-size: 88px;
  color: var(--login-cinnabar);
  animation-delay: 10s;
}
.login-fchar.f3 {
  right: 6%;
  left: auto;
  font-size: 140px;
  color: var(--login-ink-soft);
  animation-delay: 19s;
}

/* —— 层5：底部双线流水 —— */
.login-bg-waves {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 120px;
  z-index: 0;
  pointer-events: none;
}
.login-wave {
  position: absolute;
  left: 0;
  right: 0;
  height: 60px;
  overflow: hidden;
}
.login-wave::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  height: 100%;
  width: calc(100% + 240px);
  background-repeat: repeat-x;
  background-position: left center;
  background-size: 240px 60px;
  will-change: transform;
}
.login-wave.w1 {
  bottom: 6px;
  opacity: 0.35;
}
.login-wave.w1::before {
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='240' height='60' viewBox='0 0 240 60'><path d='M0 30 q30 -20 60 0 t60 0 t60 0 t60 0' fill='none' stroke='%23b08d4f' stroke-width='1.5' stroke-linecap='round'/></svg>");
  animation: fuy-login-wave-flow 22s linear infinite;
}
.login-wave.w2 {
  bottom: 22px;
  opacity: 0.22;
}
.login-wave.w2::before {
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='240' height='60' viewBox='0 0 240 60'><path d='M0 30 q30 -13 60 0 t60 0 t60 0 t60 0' fill='none' stroke='%238a7a5e' stroke-width='1.2' stroke-linecap='round'/></svg>");
  animation: fuy-login-wave-flow 34s linear infinite reverse;
}

/* —— 层6：光带缓扫 —— */
.login-bg-sheen {
  inset: -20%;
  background: linear-gradient(
    115deg,
    transparent 42%,
    rgba(244, 236, 219, 0.45) 50%,
    transparent 58%
  );
  transform: translateX(-60%);
  animation: fuy-login-sheen 16s ease-in-out infinite;
  will-change: transform, opacity;
}

/* ---------- 页面级边框：静态底线 + 朱砂走线 + 四角回字纹 ---------- */
.login-page-frame {
  position: fixed;
  inset: 12px;
  z-index: 0;
  pointer-events: none;
  border: 1px solid rgba(90, 76, 56, 0.14);
  border-radius: 10px;
}
.login-page-flow {
  position: fixed;
  inset: 12px;
  z-index: 0;
  pointer-events: none;
  overflow: visible;
  width: calc(100% - 24px);
  height: calc(100% - 24px);
}
/* 行军线：dasharray 18 14 与 fuy-login-frame-march 的 -64 位移严格对齐（契约 §2 钉定） */
.login-page-flow rect {
  x: 1px;
  y: 1px;
  width: calc(100% - 2px);
  height: calc(100% - 2px);
  rx: 10px;
  fill: none;
  stroke: rgba(165, 53, 44, 0.38);
  stroke-width: 1.2;
  stroke-dasharray: 18 14;
  animation: fuy-login-frame-march 26s linear infinite;
}
.login-pcorner {
  position: fixed;
  width: 60px;
  height: 60px;
  z-index: 0;
  pointer-events: none;
  opacity: 0.42;
  background: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='60' height='60' viewBox='0 0 60 60'><g fill='none' stroke='%23a5352c' stroke-width='1.8' stroke-linecap='round'><path d='M20 2 H12 Q2 2 2 12 V20'/><path d='M18 10 H14 Q10 10 10 14 V18'/></g><circle cx='30' cy='30' r='1.8' fill='%23a5352c'/></svg>")
    no-repeat center/contain;
}
.login-pcorner.tl {
  top: 8px;
  left: 8px;
}
.login-pcorner.tr {
  top: 8px;
  right: 8px;
  transform: scaleX(-1);
}
.login-pcorner.bl {
  bottom: 8px;
  left: 8px;
  transform: scaleY(-1);
}
.login-pcorner.br {
  bottom: 8px;
  right: 8px;
  transform: scale(-1, -1);
}

/* ============================================================
   stage 双栏卡片：左封面 / 右病案内页
   ============================================================ */
.login-stage {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1160px;
  display: grid;
  grid-template-columns: 1fr 1.05fr;
  gap: 0;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: var(--login-shadow-lg);
  /* 暖纸双向渐变（样稿 #f8f2e3→#f0e6cf 的十值收敛档） */
  background: linear-gradient(160deg, var(--login-paper) 0%, var(--login-paper-deep) 100%);
  border: 1px solid var(--login-paper-edge);
  min-height: 640px;
  animation: fuy-login-stage-in 0.8s var(--login-ease-out) both;
}

/* ============================================================
   左侧封面
   ============================================================ */
.login-cover {
  position: relative;
  padding: 56px 48px 40px;
  background: linear-gradient(135deg, rgba(244, 236, 219, 0.65) 0%, rgba(234, 223, 200, 0.35) 100%);
  border-right: 1px dashed var(--login-line);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
}
/* 封皮左缘虚缝装订线（repeating 渐变静态纹理） */
.login-cover::before {
  content: '';
  position: absolute;
  left: 20px;
  top: 24px;
  bottom: 24px;
  width: 2px;
  background: repeating-linear-gradient(
    to bottom,
    var(--login-ink-faint) 0 8px,
    transparent 8px 16px
  );
  opacity: 0.4;
}
/* 书法底字「启」：呼吸的巨字水印（装饰层，绝对定位出血） */
.login-cover-deco {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}
.login-cover-deco::before {
  content: '启';
  position: absolute;
  right: -30px;
  top: -40px;
  font-family: var(--login-font-brush);
  font-size: 280px;
  line-height: 1;
  color: rgba(165, 53, 44, 0.06);
  animation: fuy-login-seal-breathe 6s ease-in-out infinite;
}
.login-landscape {
  position: absolute;
  left: 20px;
  right: 0;
  bottom: 6px;
  z-index: 1;
  width: calc(100% - 40px);
  height: auto;
  pointer-events: none;
}
.login-cover-top {
  position: relative;
  z-index: 2;
}
.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
}
/* 品牌「富」朱印块：钤印进场（1.8 倍旋入落章），内圈纸白描线 */
.login-brand-mark {
  width: 44px;
  height: 44px;
  background: var(--login-cinnabar);
  color: var(--login-paper);
  border-radius: 6px;
  display: grid;
  place-items: center;
  font-family: var(--login-font-brush);
  font-size: 26px;
  box-shadow:
    var(--login-shadow-sm),
    inset 0 0 0 1.5px rgba(244, 236, 219, 0.18);
  position: relative;
  animation: fuy-login-seal-in 0.7s 0.2s var(--login-ease-ink) both;
}
.login-brand-mark::after {
  content: '';
  position: absolute;
  inset: 4px;
  border-radius: 3px;
  border: 1px solid rgba(244, 236, 219, 0.5);
}
.login-brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.login-brand-title {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.06em;
}
.login-brand-sub {
  font-size: 11px;
  letter-spacing: 0.32em;
  color: var(--login-ink-faint);
}
.login-cover-title {
  margin-top: 52px;
}
/* hero 书法题字：墨色渐变裁字（以墨为凭·以纸为证） */
.login-hero {
  font-family: var(--login-font-brush);
  font-size: 56px;
  line-height: 1.15;
  letter-spacing: 0.04em;
  margin: 0;
  background: linear-gradient(160deg, var(--login-ink) 20%, var(--login-ink-soft) 90%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.login-tagline {
  margin: 20px 0 0;
  font-size: 14.5px;
  line-height: 2;
  color: var(--login-ink-soft);
  max-width: 28em;
  padding-left: 14px;
  border-left: 2px solid var(--login-cinnabar);
}
/* 五业务域签牌：hover 浮起 + 朱下划展开（纯装饰） */
.login-tabs {
  margin-top: 40px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.login-tab {
  padding: 7px 16px 8px;
  font-size: 13px;
  letter-spacing: 0.14em;
  color: var(--login-ink-soft);
  background: linear-gradient(to bottom, rgba(244, 236, 219, 0.85), rgba(234, 223, 200, 0.65));
  border: 1px solid var(--login-paper-edge);
  border-radius: 3px;
  box-shadow: var(--login-shadow-sm);
  position: relative;
  cursor: default;
  transition:
    transform 0.35s var(--login-ease-out),
    box-shadow 0.35s var(--login-ease-out),
    color 0.3s;
}
.login-tab::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--login-cinnabar);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.35s var(--login-ease-out);
}
.login-tab:hover {
  transform: translateY(-3px);
  box-shadow: var(--login-shadow-md);
  color: var(--login-cinnabar);
}
.login-tab:hover::after {
  transform: scaleX(1);
}
.login-cover-foot {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 24px;
  border-top: 1px dashed var(--login-line);
}
.login-cover-foot .login-meta {
  font-size: 11.5px;
  letter-spacing: 0.08em;
  color: var(--login-ink-faint);
  line-height: 1.8;
}
/* 竖排朱印「启封有据」：卷宗封皮的钤批 */
.login-vertical-seal {
  writing-mode: vertical-rl;
  font-family: var(--login-font-brush);
  font-size: 14px;
  letter-spacing: 0.3em;
  color: var(--login-cinnabar);
  padding: 10px 6px;
  border: 1.5px solid var(--login-cinnabar);
  border-radius: 3px;
  background: rgba(165, 53, 44, 0.05);
  opacity: 0.85;
}

/* ============================================================
   右侧病案内页（登录）
   ============================================================ */
.login-sheet {
  position: relative;
  padding: 56px 56px 40px;
  display: flex;
  flex-direction: column;
  background: linear-gradient(to bottom, rgba(244, 236, 219, 0.6), rgba(244, 236, 219, 0.4));
  overflow: hidden;
}
/* 内页左缘金虚缝装订线 */
.login-sheet::before {
  content: '';
  position: absolute;
  left: 18px;
  top: 28px;
  bottom: 28px;
  width: 2px;
  background: repeating-linear-gradient(to bottom, var(--login-gold) 0 10px, transparent 10px 20px);
  opacity: 0.35;
}
/* 书法底字「归」：内页右下的呼吸水印（与封面「启」对仗） */
.login-sheet::after {
  content: '归';
  position: absolute;
  right: -18px;
  bottom: -58px;
  font-family: var(--login-font-brush);
  font-size: 230px;
  line-height: 1;
  color: rgba(165, 53, 44, 0.05);
  transform: rotate(-8deg);
  animation: fuy-login-seal-breathe 8s ease-in-out infinite reverse;
  pointer-events: none;
}
/* 菱格底纹（双层错位菱形贴片，金线弱档） */
.login-sheet-texture {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  opacity: 0.15;
  background-image:
    url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='34' height='30' viewBox='0 0 34 30'><path d='M17 1 L32 8.5 V21.5 L17 29 L2 21.5 V8.5 Z' fill='none' stroke='%23b08d4f' stroke-width='1' opacity='.55'/></svg>"),
    url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='34' height='30' viewBox='0 0 34 30'><path d='M17 1 L32 8.5 V21.5 L17 29 L2 21.5 V8.5 Z' fill='none' stroke='%23b08d4f' stroke-width='1' opacity='.55'/></svg>");
  background-size:
    34px 30px,
    34px 30px;
  background-position:
    0 0,
    17px 15px;
}
/* 四角朱花角（回字套叠 + 云头小勾） */
.login-ornament {
  position: absolute;
  width: 60px;
  height: 60px;
  z-index: 0;
  pointer-events: none;
  opacity: 0.5;
  background: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64' viewBox='0 0 64 64'><g fill='none' stroke='%23a5352c' stroke-width='1.6' stroke-linecap='round'><path d='M46 2 H12 Q2 2 2 12 V46'/><path d='M36 10 H16 Q10 10 10 16 V36'/><path d='M24 24 q7 0 7 7'/></g></svg>")
    no-repeat center/contain;
}
.login-ornament.c1 {
  top: 18px;
  left: 32px;
}
.login-ornament.c2 {
  top: 18px;
  right: 20px;
  transform: scaleX(-1);
}
.login-ornament.c3 {
  bottom: 16px;
  left: 32px;
  transform: scaleY(-1);
}
.login-ornament.c4 {
  bottom: 16px;
  right: 20px;
  transform: scale(-1, -1);
}
/* 边栏云纹列（右缘竖排祥云贴片 repeat-y） */
.login-margin-clouds {
  position: absolute;
  right: 22px;
  top: 86px;
  bottom: 86px;
  width: 30px;
  z-index: 0;
  pointer-events: none;
  opacity: 0.3;
  background: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='60' height='112' viewBox='0 0 60 112'><g fill='none' stroke='%23a5352c' stroke-width='1.4' stroke-linecap='round'><path d='M30 38 c-9 0 -15 -5 -15 -12 c0 -7 7 -12 13 -10 c1 -8 9 -13 16 -10 c6 2 8 8 7 13 c6 1 9 5 8 10 c-1 6 -6 9 -13 9 z'/><path d='M23 28 c0 -4 3 -7 7 -6 c3 1 4 4 3 6'/><path d='M12 62 q9 -5 18 0 t18 0'/><path d='M16 72 q7 -4 14 0 t14 0' opacity='.65'/></g><circle cx='30' cy='90' r='2' fill='%23a5352c'/></svg>")
    repeat-y center top;
  background-size: 30px auto;
}
/* 竖排批注：内页左缘的世界语义批注（10px 弱墨疏排） */
.login-margin-note {
  position: absolute;
  left: 33px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 0;
  writing-mode: vertical-rl;
  white-space: nowrap;
  font-size: 10px;
  letter-spacing: 0.5em;
  color: var(--login-ink-faint);
  opacity: 0.6;
  pointer-events: none;
}
.login-sheet-head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}
.login-sheet-title {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.login-sheet-title h2 {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.08em;
  margin: 0;
}
.login-sheet-title .login-sheet-en {
  font-size: 11px;
  letter-spacing: 0.24em;
  color: var(--login-ink-faint);
  font-family: Georgia, serif;
}
/* 状态区：呼吸点胶囊 + 「谁·何时」批注（— 占位零伪数据） */
.login-sheet-status {
  text-align: right;
  font-size: 11.5px;
  line-height: 1.9;
  color: var(--login-ink-faint);
  letter-spacing: 0.06em;
}
.login-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 20px;
  background: rgba(165, 53, 44, 0.08);
  border: 1px solid rgba(165, 53, 44, 0.25);
  color: var(--login-cinnabar);
  font-size: 11px;
  letter-spacing: 0.1em;
  margin-bottom: 6px;
}
/* 呼吸点：本体恒亮 + 伪元素光环 scale/opacity 呼吸（样稿 box-shadow 脉冲的
   transform/opacity 等价改写，防逐帧重绘） */
.login-status-dot {
  position: relative;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--login-cinnabar);
}
.login-status-dot::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(165, 53, 44, 0.15);
  animation: fuy-login-pulse-halo 2s ease-in-out infinite;
}
/* 「签」字分隔 + 双云：内页页眉与表单区的文书分界 */
.login-divider {
  position: relative;
  z-index: 1;
  margin: 28px 0 32px;
  height: 1px;
  background: linear-gradient(
    to right,
    transparent,
    var(--login-line) 15%,
    var(--login-line) 85%,
    transparent
  );
}
.login-divider::after {
  content: '签';
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  padding: 0 14px;
  background: var(--login-paper);
  font-family: var(--login-font-brush);
  font-size: 15px;
  color: var(--login-cinnabar);
  letter-spacing: 0.2em;
}
.login-div-cloud {
  position: absolute;
  top: 50%;
  width: 34px;
  height: 16px;
  transform: translateY(-50%);
  background: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='36' height='16' viewBox='0 0 36 16'><g fill='none' stroke='%23a5352c' stroke-width='1.4' stroke-linecap='round'><path d='M2 12 q8 -8 15 -4 q5 3 1 6 q-3 2 -4 -1'/><path d='M22 12 q6 -3 12 -2'/></g></svg>")
    no-repeat center/contain;
  opacity: 0.5;
  pointer-events: none;
}
.login-div-cloud.dl {
  left: 20px;
}
.login-div-cloud.dr {
  right: 20px;
  transform: translateY(-50%) scaleX(-1);
}

/* ============================================================
   表单区：EP 通道收编（el-form/el-form-item/el-input 结构与校验通道
   零改动，样稿 field 形态经 scoped :deep 收编进 EP 内部结构）
   ============================================================ */
.login-form {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
}
/* 表单行距对齐样稿 .form gap 24px（EP 默认 18px） */
.login-form :deep(.el-form-item) {
  margin-bottom: 24px;
}
/* 字段标签 = 样稿 field-label：12.5px 疏排灰墨 + 右挂 hint（label 槽承载） */
.login-form :deep(.el-form-item__label) {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: auto;
  line-height: 1.6;
  font-size: 12.5px;
  letter-spacing: 0.14em;
  color: var(--login-ink-soft);
  margin-bottom: 8px;
}
.login-field-hint {
  margin-left: auto;
  font-size: 10.5px;
  letter-spacing: 0.1em;
  color: var(--login-ink-faint);
}
/* 必填星（require-asterisk-position=right → label::after）：朱色承必填状态 */
.login-form
  :deep(
    .el-form-item.is-required:not(.is-no-asterisk).asterisk-right > .el-form-item__label::after
  ) {
  color: var(--login-cinnabar);
  font-weight: 700;
}
/* 输入围合 = 样稿 field-wrap：纸亮底 + paper-edge 描边（EP 以 inset box-shadow 承描边
   机制非投影），聚焦转朱环 + 纸面提亮；样稿无悬停描边态，压 EP 悬停加深 */
.login-form :deep(.el-input__wrapper) {
  position: relative; /* 供朱砂下划线 ::after 定位 */
  padding: 0 14px;
  background: rgba(244, 236, 219, 0.75);
  border-radius: 4px;
  box-shadow: 0 0 0 1px var(--login-paper-edge) inset;
  transition:
    box-shadow 0.3s,
    background-color 0.3s;
}
.login-form :deep(.el-input__wrapper:hover:not(.is-focus)) {
  box-shadow: 0 0 0 1px var(--login-paper-edge) inset;
}
/* 聚焦：朱描边 + 3px 朱晕环（error 规则在前、focus 在后——同特异性后者胜，
   聚焦态完整覆盖错误描边，样稿 focus-within 环语法） */
.login-form :deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--login-cinnabar) inset;
  animation: fuy-login-shake 0.45s var(--login-ease-out);
}
.login-form :deep(.el-input__wrapper.is-focus) {
  background: rgba(244, 236, 219, 0.95);
  box-shadow:
    0 0 0 1px var(--login-cinnabar) inset,
    0 0 0 3px rgba(165, 53, 44, 0.08);
}
/* 朱砂下划线：聚焦自中心展开（45ms ink 缓动，样稿 field-underline 语法） */
.login-form :deep(.el-input__wrapper::after) {
  content: '';
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: -1px;
  height: 2px;
  background: linear-gradient(to right, var(--login-cinnabar), var(--login-cinnabar-lt));
  transform: scaleX(0);
  transform-origin: center;
  transition: transform 0.45s var(--login-ease-ink);
  border-radius: 2px;
}
.login-form :deep(.el-input__wrapper.is-focus::after) {
  transform: scaleX(1);
}
/* 输入文字：15px 疏排墨字（EP large 38px 高收敛改写为样稿 46px 行高） */
.login-form :deep(.el-input__inner) {
  height: 46px;
  line-height: 46px;
  font-size: 15px;
  letter-spacing: 0.05em;
  color: var(--login-ink);
}
.login-form :deep(.el-input__inner::placeholder) {
  color: var(--login-ink-faint);
  font-size: 13.5px;
  letter-spacing: 0.12em;
}
/* 域图标随域转朱：默认弱墨、聚焦随围合转朱（样稿 field-icon 聚焦变色语法） */
.login-form :deep(.el-input__prefix),
.login-form :deep(.el-input__suffix) {
  color: var(--login-ink-faint);
  transition: color 0.3s;
}
.login-form :deep(.el-input__wrapper.is-focus .el-input__prefix) {
  color: var(--login-cinnabar);
}
/* 口令可见切换：朱字 + 朱洗浅底悬停（样稿 eye-btn hover 语法，EP show-password
   内建承载零自绘零新 DOM） */
.login-form :deep(.el-input__suffix .el-icon) {
  border-radius: 3px;
  transition:
    color 0.3s,
    background-color 0.3s;
}
.login-form :deep(.el-input__suffix .el-icon:hover) {
  color: var(--login-cinnabar);
  background: rgba(165, 53, 44, 0.06);
}
/* 浏览器自动填充收编（浏览器原生面纪律）：Chrome/Edge autofill 私绘淡蓝底会破纸面，
   -webkit-text-fill-color 锁定正文墨色保可读性（v2 同款手段与局限登记） */
.login-form :deep(.el-input__inner:-webkit-autofill) {
  -webkit-text-fill-color: var(--login-ink);
  caret-color: var(--login-ink);
}
/* 错误呈现 = EP 校验通道零改动，视觉按样稿 field-error 态收编：
   朱字 + 圈叹图标（::before data-uri，视觉通道）+ note-in 沉落显影 */
.login-form :deep(.el-zoom-in-top-enter-active),
.login-form :deep(.el-zoom-in-top-leave-active) {
  /* 压制 EP 内建 zoom 过渡（AppSidebar 压 collapse-transition 同款挂类手法），
     让位给下方动画：级联中 EP 过渡会整体压过 transform 动画（v2 R1 修复结论） */
  transition: none;
}
.login-form :deep(.el-form-item__error) {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  letter-spacing: 0.06em;
  color: var(--login-cinnabar);
  animation: fuy-login-note-in var(--fuy-motion-base) var(--fuy-ease-enter) backwards;
}
.login-form :deep(.el-form-item__error)::before {
  content: '';
  flex: none;
  width: 12px;
  height: 12px;
  background: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23a5352c' stroke-width='2.5' stroke-linecap='round'><circle cx='12' cy='12' r='10'/><line x1='12' y1='8' x2='12' y2='12'/><line x1='12' y1='16' x2='12.01' y2='16'/></svg>")
    no-repeat center/contain;
}

/* ---------- 提交按钮：原生 button 承载样稿朱砂实底形态（载体替换非逻辑改动） ---------- */
.login-submit {
  position: relative;
  width: 100%;
  margin-top: 8px;
  padding: 15px 24px;
  border: none;
  cursor: pointer;
  border-radius: 4px;
  overflow: hidden;
  background: linear-gradient(160deg, var(--login-cinnabar) 0%, var(--login-cinnabar-dk) 100%);
  color: var(--login-paper);
  font-family: inherit;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.3em;
  box-shadow:
    var(--login-shadow-md),
    inset 0 1px 0 rgba(244, 236, 219, 0.18);
  transition:
    transform 0.3s var(--login-ease-out),
    box-shadow 0.3s var(--login-ease-out),
    filter 0.3s;
}
/* 纸纹噪点 overlay（静态纹理 multiply 质感层，不参与动画） */
.login-submit::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='60' height='60'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='1.2' numOctaves='2'/><feColorMatrix type='saturate' values='0'/></filter><rect width='60' height='60' filter='url(%23n)' opacity='0.12'/></svg>");
  mix-blend-mode: overlay;
  pointer-events: none;
}
/* hover 光带扫过：样稿 left 位移禁改（禁 left 动画红线），改 translateX 等价承载
   （伪元素宽 40%、-60% 起步 → 450% 自身位移即样稿 left:-60%→120% 的同轨迹） */
.login-submit::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  width: 40%;
  left: -60%;
  background: linear-gradient(to right, transparent, rgba(244, 236, 219, 0.3), transparent);
  transform: translateX(0) skewX(-18deg);
  transition: transform 0.6s var(--login-ease-out);
  pointer-events: none;
}
.login-submit:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow:
    var(--login-shadow-lg),
    inset 0 1px 0 rgba(244, 236, 219, 0.22);
  filter: brightness(1.06);
}
.login-submit:hover:not(:disabled)::after {
  transform: translateX(450%) skewX(-18deg);
}
/* 按压沉底：1px 下沉 + 0.99 缩放（落章手感） */
.login-submit:active:not(:disabled) {
  transform: translateY(1px) scale(0.99);
}
.login-submit:disabled {
  cursor: not-allowed;
  filter: grayscale(0.3) brightness(0.9);
}
/* 键盘焦点环：朱砂实底上覆写纸白（焦点永远可见，导轨白环同语法） */
.login-submit:focus-visible {
  outline: 2px solid var(--login-paper);
  outline-offset: 2px;
}
/* 标签层与转轮层分离：loading 时标签隐没、白转轮显形（spin 经 motion.css 豁免类保转） */
.login-submit-label {
  position: relative;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: opacity 0.3s;
}
.login-submit.is-loading .login-submit-label {
  opacity: 0;
}
.login-submit-ring {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 26px;
  height: 26px;
  margin: -13px 0 0 -13px;
  border: 2px solid rgba(244, 236, 219, 0.4);
  border-top-color: var(--login-paper);
  border-radius: 50%;
  opacity: 0;
  transition: opacity 0.2s;
}
.login-submit.is-loading .login-submit-ring {
  opacity: 1;
  animation: fuy-login-spin 0.8s linear infinite;
}

/* ---------- 审计注记：金左线提示条（等保三级真实合规语义），margin-top:auto 贴底 ---------- */
.login-audit {
  position: relative;
  z-index: 1;
  margin-top: auto;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11.5px;
  letter-spacing: 0.08em;
  color: var(--login-ink-faint);
  background: rgba(216, 201, 168, 0.2);
  border-left: 2px solid var(--login-gold);
  border-radius: 0 3px 3px 0;
}
.login-audit svg {
  flex-shrink: 0;
  color: var(--login-gold);
}

/* ---------- 成功钤印浮层：居中盖章（缩小斜置 → 归位斜盖），失败路径不显示 ---------- */
.login-stamp-toast {
  position: fixed;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%) scale(0.4) rotate(-16deg);
  z-index: 99;
  padding: 20px 28px;
  background: var(--login-cinnabar);
  color: var(--login-paper);
  font-family: var(--login-font-brush);
  font-size: 24px;
  letter-spacing: 0.2em;
  border-radius: 6px;
  box-shadow: 0 20px 40px -10px rgba(130, 37, 31, 0.5);
  opacity: 0;
  pointer-events: none;
  transition:
    opacity 0.35s var(--login-ease-ink),
    transform 0.5s var(--login-ease-ink);
}
.login-stamp-toast.is-show {
  opacity: 1;
  transform: translate(-50%, -50%) scale(1) rotate(-8deg);
}

/* ============================================================
   进场编排（样稿时间轴原样）：stage-in 整卡浮入 → 品牌 100ms → 题字 250ms →
   域标签 400ms → 封面页脚 550ms ‖ 表头 350ms → 分隔 450ms → 表单 550ms → 审计 650ms
   keyframes 消费 motion.css fuy-login-stage-in/fade-up/seal-in（组件内零定义）；
   只动 transform/opacity。fill-mode 取 backwards：delay 期压 from 态、终态零残留
   transform（v2 验证过的层叠卫生增益：容器不长期充当绝对定位包含块）；
   reduced-motion 由 motion.css 全局兜底（时长/延迟双归零）直达终态
   ============================================================ */
.login-rise {
  animation: fuy-login-fade-up 0.8s var(--login-ease-out) backwards;
  animation-delay: var(--login-rise-delay, 0ms);
}

/* ---------- 窄窗保底（非响应式设计：workstation 以 1440+ 桌面为唯一目标形态，
   断点沿样稿仅防破版） ---------- */
@media (max-width: 900px) {
  .login-stage {
    grid-template-columns: 1fr;
    min-height: auto;
  }
  .login-page {
    overflow: auto;
  }
  .login-cover {
    padding: 36px 32px 28px;
    border-right: none;
    border-bottom: 1px dashed var(--login-line);
  }
  .login-hero {
    font-size: 38px;
  }
  .login-sheet {
    padding: 36px 28px 28px;
  }
  .login-sheet::before {
    display: none;
  }
  .login-cover-deco::before {
    font-size: 180px;
  }
  .login-margin-clouds,
  .login-margin-note,
  .login-ornament,
  .login-div-cloud {
    display: none;
  }
  .login-sheet::after {
    font-size: 150px;
    bottom: -40px;
  }
  .login-bg-clouds,
  .login-bg-chars,
  .login-bg-sheen,
  .login-page-flow {
    display: none;
  }
}
@media (max-width: 620px) {
  .login-page-frame,
  .login-page-flow,
  .login-pcorner {
    display: none;
  }
  .login-bg-waves,
  .login-bg-seal {
    display: none;
  }
  .login-page {
    padding: 12px;
  }
}
</style>
