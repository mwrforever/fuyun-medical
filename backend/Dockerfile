# fuyun-medical 后端镜像：多阶段构建（构建上下文 context = backend/，本文件内路径相对 backend/）
# 七条规范：多阶段 / 运行层必装 curl / 非 root 运行 / pom 先拷命中层缓存 / 打包跳过测试 / 镜像内零密钥 / jar 取自 fuyun-app

# ===== 构建层：Maven + JDK17（与 CI 门禁同一工具链基线） =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# 先拷父 POM 与全部子模块 POM，保持各自目录结构（消除 glob 拍平），单独成层命中依赖缓存
COPY pom.xml ./
COPY fuyun-common/pom.xml fuyun-common/
COPY fuyun-app/pom.xml fuyun-app/
COPY fuyun-system/pom.xml fuyun-system/
COPY fuyun-patient/pom.xml fuyun-patient/
COPY fuyun-outpatient/pom.xml fuyun-outpatient/
COPY fuyun-inpatient/pom.xml fuyun-inpatient/
COPY fuyun-nursing/pom.xml fuyun-nursing/
COPY fuyun-pharmacy/pom.xml fuyun-pharmacy/
COPY fuyun-lab/pom.xml fuyun-lab/
COPY fuyun-imaging/pom.xml fuyun-imaging/
COPY fuyun-emr/pom.xml fuyun-emr/
COPY fuyun-surgery/pom.xml fuyun-surgery/
COPY fuyun-icu/pom.xml fuyun-icu/
COPY fuyun-transfusion/pom.xml fuyun-transfusion/
COPY fuyun-billing/pom.xml fuyun-billing/
COPY fuyun-iot/pom.xml fuyun-iot/
COPY fuyun-asset/pom.xml fuyun-asset/
COPY fuyun-ward/pom.xml fuyun-ward/
COPY fuyun-peis/pom.xml fuyun-peis/
COPY fuyun-internet/pom.xml fuyun-internet/
COPY fuyun-ops/pom.xml fuyun-ops/
COPY fuyun-integration/pom.xml fuyun-integration/
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
# 可执行 jar 仅产自装配模块 fuyun-app（唯一入口，不放业务逻辑）；repackage 以 classifier=exec
# 产出 fat jar（主构件为薄 jar，集成测试 classpath 依赖薄 jar），故取 *-exec.jar
COPY --from=build --chown=fuyun:fuyun /workspace/fuyun-app/target/*-exec.jar app.jar
EXPOSE 8080
# $JAVA_OPTS 经环境变量注入 JVM 参数（凭据一律运行时 env 注入，镜像内零密钥）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
# TODO(iot-simulator): PR-4 若按 D-3 默认裁决增补 backend/iot-simulator Maven 子模块，上方 POM 拷贝清单同步追加其 COPY 行
