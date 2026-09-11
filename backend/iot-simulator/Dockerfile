# iot-simulator 模拟设备镜像：多阶段构建（构建上下文 context = backend/，本文件内路径相对 backend/）
# 与 backend/Dockerfile 七条规范对齐：多阶段 / 运行层必装 curl / 非 root 运行 / pom 先拷命中层缓存 / 打包跳过测试 / 镜像内零密钥 / 固定 tag 基底
# 独立 Dockerfile 结论（BRIEF-PR4-01 §5，D-3 裁决）：backend 镜像运行层固定产出 fuyun-app 单入口，
# 双镜像须两文件，禁扩展 backend/Dockerfile 承载双镜像

# ===== 构建层：Maven + JDK17（与 CI 门禁同一工具链基线） =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# 先拷父 POM 与全部子模块 POM（与 backend/Dockerfile 同清单，含 simulator 自身），单独成层命中依赖缓存
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
COPY iot-simulator/pom.xml iot-simulator/
# 预热依赖层：仅 pom 变动才失效，日常源码提交不重复下载依赖
RUN mvn -B -ntp dependency:go-offline
# 再拷源码全量
COPY . .
# 仅构建 simulator 模块（纯 Java 零 Spring 无上游模块，-am 兜底依赖链）；跳过测试：测试已由 CI backend job（mvn verify）前置门禁
RUN mvn -B -ntp -pl iot-simulator -am package -DskipTests

# ===== 运行层：JRE17 基底 + curl + 非 root（与 backend 运行层同规范） =====
FROM eclipse-temurin:17-jre
# 安装 curl 后清空 apt 缓存压体积；固定 uid 1001 的非 root 用户运行（等保最小权限）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 fuyun
USER fuyun
WORKDIR /app
# 薄 jar + copy-dependencies 产出的 libs 依赖目录共同组成运行类路径（非 Spring 胖 jar 应用）
COPY --from=build --chown=fuyun:fuyun /workspace/iot-simulator/target/iot-simulator-*.jar app.jar
COPY --from=build --chown=fuyun:fuyun /workspace/iot-simulator/target/libs/ libs/
# 无端口监听（纯上行客户端）；IOTDA_* 凭据仅运行时 env 注入（镜像内零密钥），deviceSecret 禁入日志
# 类路径通配交由 JVM 展开（单引号防 shell 抢先展开）；$JAVA_OPTS 经环境变量注入 JVM 参数
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp 'app.jar:libs/*' com.fuyun.iotsimulator.IotSimulatorApplication"]
