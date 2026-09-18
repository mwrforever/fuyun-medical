// 金额展示换算单测（web A.3-6）：整除/补零/负值红冲/非法输入原样透出（不吞错）；
// 红线断言=输出恒为「元」两位小数展示串，输入输出全程无浮点参与。
import { describe, expect, it } from 'vitest';
import { fenToYuanDisplay } from './money';

describe('金额分→元展示换算', () => {
  it('整除与补零：6000→60.00、5→0.05、0→0.00', () => {
    expect(fenToYuanDisplay('6000')).toBe('60.00');
    expect(fenToYuanDisplay('5')).toBe('0.05');
    expect(fenToYuanDisplay('0')).toBe('0.00');
  });
  it('负值红冲口径：-1234→-12.34', () => {
    expect(fenToYuanDisplay('-1234')).toBe('-12.34');
  });
  it('超大分值无精度丢失（BigInt 字符串运算，>2^53）', () => {
    expect(fenToYuanDisplay('9007199254740993')).toBe('90071992547409.93');
  });
  it('非法输入原样透出（错误展示不被换算层吞掉）', () => {
    expect(fenToYuanDisplay('N/A')).toBe('N/A');
  });
});
