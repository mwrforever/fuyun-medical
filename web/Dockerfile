# fuyun-medical 前端镜像：多阶段构建（构建上下文 context = web/，本文件内路径相对 web/）
# 设计要点：构建层容器内独立完成 install + build，不依赖 CI frontend job 的产物传递，保证镜像可独立复现
# 注意：P0 工程骨架（pnpm workspace 三应用）落地后方可实际构建；nginx 站点配置（/api 反代 + /ws 升级）归 deploy/ 挂载，本镜像只放静态产物

# ===== 构建层：node:24 + corepack 启用 pnpm 12.3.4（与 CI/本地版本严格一致，无 latest 漂移） =====
FROM node:24 AS build
WORKDIR /workspace
RUN corepack enable && corepack prepare pnpm@12.3.4 --activate
# 全量拷贝 workspace 源码与清单（含 pnpm-lock.yaml，frozen-lockfile 严格校验一致性）
COPY . .
# 安装 + 按拓扑序构建三应用（workstation / portal / bigscreen，产物在各应用 dist/ 目录）
RUN pnpm install --frozen-lockfile && pnpm build

# ===== 运行层：nginx 1.30.4 承载三应用静态产物（与 dev compose 镜像版本严格一致） =====
FROM nginx:1.30.4
# 三应用产物分目录承载，挂载约定 /usr/share/nginx/html/{workstation,bigscreen,portal}
# 站点配置归 deploy/（fuyun.conf 以 bind mount 提供），此处不放任何 nginx 配置文件
COPY apps/workstation/dist /usr/share/nginx/html/workstation
COPY apps/bigscreen/dist /usr/share/nginx/html/bigscreen
COPY apps/portal/dist /usr/share/nginx/html/portal
