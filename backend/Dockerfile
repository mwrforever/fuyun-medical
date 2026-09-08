# fuyun-medical 后端镜像：多阶段构建（构建上下文 context = backend/，本文件内路径相对 backend/）
# 注意：P0 工程骨架（父 POM 与 fuyun-* 各模块）落地后方可实际构建，当前仅为 CI images job 的构建骨架
# 七条规范：多阶段 / 运行层必装 curl / 非 root 运行 / pom 先拷命中层缓存 / 打包跳过测试 / 镜像内零密钥 / jar 取自 fuyun-app

# ===== 构建层：Maven + JDK17（与 CI 门禁同一工具链基线） =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# 先拷全部 pom（父 POM + 各子模块 POM）单独成层，源码变动不失效该层
COPY pom.xml fuyun-*/pom.xml ./
# 预热依赖层：仅 pom 变动才失效，日常源码提交不重复下载依赖
RUN mvn -B -ntp dependency:go-offline
# 再拷源码全量
COPY . .
# 打包跳过测试：测试已由 CI backend job（mvn verify）前置门禁，镜像层不重复跑
RUN mvn -B -ntp package -DskipTests

# ===== 运行层：JRE17 基底 + curl（compose 健康检查探针依赖，Temurin JRE 系基底不含） =====
FROM eclipse-temurin:17-jre
# 安装 curl 后清空 apt 缓存压体积；固定 uid 1001 的非 root 用户运行（等保最小权限）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 fuyun
USER fuyun
WORKDIR /app
# 可执行 jar 仅产自装配模块 fuyun-app（唯一入口，不放业务逻辑）
COPY --from=build --chown=fuyun:fuyun /workspace/fuyun-app/target/*.jar app.jar
EXPOSE 8080
# $JAVA_OPTS 经环境变量注入 JVM 参数（凭据一律运行时 env 注入，镜像内零密钥）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
