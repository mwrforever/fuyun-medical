/**
 * 全局兜底错误处理器（册 0 P-GBL-03）：装配 app.config.errorHandler 后，组件渲染/生命周期
 * 钩子/事件处理器内未被捕获的异常统一在此留痕与提示——此前这类异常直达 console 成
 * unhandled error（research/00 §7 的 onWardChange 抛错即此形态）。
 *
 * <p>落位裁决：utils 独立模块而非 composables——消费方仅 main.ts 一处、非复用逻辑（不入
 * composables），与 utils/logger 同族（应用级日志/错误基础设施）；冷却时间戳为模块级去抖
 * 基础设施状态而非业务状态（utils「禁持业务状态」红线本意防隐形 store），文件头留痕此豁免。
 * 独立模块使本处理器可被单测直接安装到测试 app 实例，绕开 main.ts import 即 mount 的副作用。
 *
 * <p>边界声明（防未来误判，勿删）：本处理器救不了白屏——白屏是 Vue 内部 Transition 死锁，
 * 零异常抛出，errorHandler 感知不到（research/00 §2-5）；它只兜异步事件处理器/生命周期钩子
 * 内未捕获异常的次生问题。白屏防回归靠 views/template-root-guard.spec.ts 护栏，不靠本模块。
 */
import { isAxiosError } from 'axios';
import { ElMessage } from 'element-plus';
import type { App, ComponentPublicInstance } from 'vue';
import { error as logError } from './logger';

/** 用户提示冷却窗（毫秒）：错误风暴（轮询连环失败等）防重复弹窗轰炸 */
const TOAST_COOLDOWN_MS = 3000;

/** 上次弹提示的时间戳（模块级去抖状态；持态豁免理由见文件头落位裁决） */
let lastToastAt = 0;

/** 面向用户的统一提示文案（不暴露堆栈与技术细节） */
const USER_FACING_MESSAGE = '系统内部错误，本次操作未能完成，请重试；若持续出现请联系运维';

/**
 * 安装全局 errorHandler：每个错误经 logger.error 全量留痕（零去抖，含错误源 info 与原始
 * 异常堆栈）；3s 冷却窗内仅首个错误弹 ElMessage（窗内后续只记日志不弹）；AxiosError 只记
 * 日志不弹——HTTP 错误已由 api 响应拦截器统一弹错（web A.3-2），二次弹即双弹。
 * 不 rethrow：rethrow 产生 unhandled rejection 双重报告并触发 Vite overlay 干扰调试。
 *
 * @param app Vue 应用实例（main.ts 装配链 createApp 产物；须在 mount 前装配以覆盖首屏渲染期）
 */
export function installGlobalErrorHandler(app: App): void {
  app.config.errorHandler = (
    err: unknown,
    _instance: ComponentPublicInstance | null,
    info: string,
  ): void => {
    // 日志零去抖逐错留痕：traceId 为请求级生成、本场景无请求上下文不可得，以错误源 info +
    // 原始异常（含堆栈）锚定，不伪造 traceId
    logError(`全局未处理异常（错误源：${info}）`, err);
    // 防双弹 + 冷却窗去抖：AxiosError 归拦截器出口；时间戳仅在实弹时前移（窗内后续静默）
    if (isAxiosError(err) || Date.now() - lastToastAt < TOAST_COOLDOWN_MS) {
      return;
    }
    lastToastAt = Date.now();
    ElMessage.error(USER_FACING_MESSAGE);
  };
}
