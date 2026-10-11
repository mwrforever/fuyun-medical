/**
 * 病区看板（WardBoardView）各作业面 composable 共用工具：本地日期串、病情标记拆解与
 * 卡墙床号段分组（均本病区看板专属语义）。自 WardBoardView 巨型脚本原样随迁（EX-47 拆分）；
 * 原同文件的 formatTime/surfaceBizError 属全 app 通用工具，EX-43 已收拢至 src/utils/timeFormat.ts
 * 与 src/utils/bizError.ts（消费方直连 app 级共享点，本文件不再转发）。
 */
import type { WardPatientVO } from '@/api/nursing';

/** 本地日期串（yyyy-MM-dd，任务/交接班当日过滤共用） */
export function todayString(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** 病情标记逗号串拆解（condition_tags 存储「CRITICAL,SEVERE」形态） */
export function splitTags(raw: string | undefined): string[] {
  return (raw ?? '')
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0);
}

/** 卡墙分组阈值（暖纸渲染性能宪章 ⑨.4：卡墙 >120 卡必须分组/分页，禁一次全量平铺） */
export const WALL_GROUP_THRESHOLD = 120;

/** 卡墙床号段分组（>120 卡时启用；≤120 返回无组头单组） */
export interface WallBedGroup {
  /** 分组稳定键（段起始床号 / all / other） */
  key: string;
  /** 组头展示串（「01-10 床」形态；空串=无组头的单组平铺形态） */
  label: string;
  /** 组内患者（上游床位序切片，不重排） */
  patients: WardPatientVO[];
}

/** 床号数字前缀 → 十位段起始（非数字床号返回 null，归「其他床号」尾组） */
function bedSegmentStart(bedNo: string): number | null {
  const match = /^(\d+)/.exec(bedNo.trim());
  if (match === null) {
    return null;
  }
  const no = Number.parseInt(match[1] ?? '', 10);
  return no > 0 ? Math.floor((no - 1) / 10) * 10 + 1 : null;
}

/**
 * 卡墙分组（暖纸长列表策略，逐页蓝图 甲-1 §6）：≤阈值单组平铺（组头空串不渲染）；
 * 超阈值按床号十位段分组（组头=「01-10 床」石规签行），非数字床号归「其他床号」尾组。
 * 入参须为床位序清单（useWardContext.sortedPatients），组内顺序=入参序不重排。
 *
 * @param patients 床位序在区患者清单
 * @return 分组结果；空清单返回空数组（调用方渲染空态脸）
 */
export function groupWallPatients(patients: WardPatientVO[]): WallBedGroup[] {
  if (patients.length <= WALL_GROUP_THRESHOLD) {
    return patients.length === 0 ? [] : [{ key: 'all', label: '', patients }];
  }
  const buckets = new Map<string, WallBedGroup>();
  const others: WardPatientVO[] = [];
  for (const patient of patients) {
    const start = bedSegmentStart(patient.bedNo ?? '');
    if (start === null) {
      others.push(patient);
      continue;
    }
    const key = `seg-${start}`;
    const bucket = buckets.get(key);
    if (bucket === undefined) {
      // 组头「01-10 床」形态：段起始与段尾均两位补零（三位床号段仍以两位展示段界）
      buckets.set(key, {
        key,
        label: `${String(start).padStart(2, '0')}-${String(start + 9).padStart(2, '0')} 床`,
        patients: [patient],
      });
    } else {
      bucket.patients.push(patient);
    }
  }
  const groups = [...buckets.values()];
  if (others.length > 0) {
    groups.push({ key: 'other', label: '其他床号', patients: others });
  }
  return groups;
}
