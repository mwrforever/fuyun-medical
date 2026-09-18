/**
 * 金额展示换算（web 宪法 A.3-6 全站唯一件）：后端金额以「分」经 Jackson Long→String 输出，
 * 前端仅展示不运算——本函数 BigInt 字符串运算完成分→元两位小数格式化，零浮点误差；
 * 非法输入原样返回（展示层吞错会掩盖后端契约异常，宁可渲染原文供排障）。
 */
export function fenToYuanDisplay(fen: string | number): string {
  const raw = String(fen);
  if (!/^-?\d+$/.test(raw)) {
    return raw;
  }
  const value = BigInt(raw);
  const sign = value < 0n ? '-' : '';
  const abs = value < 0n ? -value : value;
  const yuan = abs / 100n;
  const remainder = (abs % 100n).toString().padStart(2, '0');
  return `${sign}${yuan.toString()}.${remainder}`;
}
