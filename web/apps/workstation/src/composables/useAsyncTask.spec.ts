// 异步任务三态包装单测（EX-42 范式沉淀）：任务注入纯函数承载（无 api mock 面），覆盖正常
// （loading 在途翻转/出参透传）、边界（失败后重跑清零 error、onFinally 成败共用）、异常
// （收拢不上抛、error 态落值、onError 注入钩子接收原因）三类场景。任务桩用
// Promise.resolve/reject 直构（非 async 箭头），与被测 run 的 await 语义等价。
import { describe, expect, it, vi } from 'vitest';
import { useAsyncTask } from './useAsyncTask';

describe('useAsyncTask', () => {
  it('在途期间 loading 为 true，成功后透传出参且 error 保持 null', async () => {
    let resolveTask: (value: string) => void = () => {};
    const { loading, error, run } = useAsyncTask(
      () =>
        new Promise<string>((resolve) => {
          resolveTask = resolve;
        }),
    );
    const pending = run();
    expect(loading.value).toBe(true);
    resolveTask('费用清单');
    await expect(pending).resolves.toBe('费用清单');
    expect(loading.value).toBe(false);
    expect(error.value).toBeNull();
  });

  it('任务失败收拢不上抛：run 返回 undefined、error 落值、loading 复位', async () => {
    const cause = new Error('接口超时');
    const { loading, error, run } = useAsyncTask(() => Promise.reject(cause));
    await expect(run()).resolves.toBeUndefined();
    expect(loading.value).toBe(false);
    expect(error.value).toBe(cause);
  });

  it('失败后重跑成功：error 按次清零不驻留上一轮失败', async () => {
    let fail = true;
    const { error, run } = useAsyncTask(() =>
      fail ? Promise.reject(new Error('首轮失败')) : Promise.resolve('第二轮成功'),
    );
    await run();
    expect(error.value).not.toBeNull();
    fail = false;
    await expect(run()).resolves.toBe('第二轮成功');
    expect(error.value).toBeNull();
  });

  it('onError 注入钩子接收失败原因（surfaceBizError 差异分支承载面）', async () => {
    // 业务拒绝形态：Error 携 detail 字段（surfaceBizError 展示口径）
    const cause = Object.assign(new Error('业务拒绝'), { detail: '挂号号源已锁定' });
    const onError = vi.fn();
    const { run } = useAsyncTask(() => Promise.reject(cause), { onError });
    await run();
    expect(onError).toHaveBeenCalledExactlyOnceWith(cause);
  });

  it('默认无 onError 时失败静默（弹错归响应拦截器口径）不额外抛错', async () => {
    const { run } = useAsyncTask(() => Promise.reject(new Error('静默口径')));
    await expect(run()).resolves.toBeUndefined();
  });

  it('onFinally 成败共用：成功与失败路径各执行一次且在 loading 复位后触发', async () => {
    const onFinally = vi.fn();
    const success = useAsyncTask(() => Promise.resolve('成功'), { onFinally });
    await success.run();
    expect(onFinally).toHaveBeenCalledTimes(1);
    const failure = useAsyncTask(() => Promise.reject(new Error('失败')), { onFinally });
    await failure.run();
    expect(onFinally).toHaveBeenCalledTimes(2);
    expect(failure.loading.value).toBe(false);
  });

  it('run 透传任务入参（具参任务同参替换调用点）', async () => {
    const task = vi.fn((visitId: string, page: number) => Promise.resolve(`${visitId}:${page}`));
    const { run } = useAsyncTask(task);
    await expect(run('V001', 2)).resolves.toBe('V001:2');
    expect(task).toHaveBeenCalledExactlyOnceWith('V001', 2);
  });
});
