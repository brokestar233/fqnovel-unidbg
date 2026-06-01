# ============================================
# 多阶段构建：fqnovel-unidbg
# ============================================

# --- Stage 1: Maven 编译打包 ---
FROM maven:3.8-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# 先复制 pom.xml 和 Maven wrapper，利用 Docker 层缓存
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./
RUN chmod +x mvnw

# 下载依赖（如果 pom.xml 没变则命中缓存）
RUN mvn dependency:go-offline -B 2>/dev/null || true

# 复制源码和资源（LFS 文件已解析为实际内容）
COPY src ./src

# 打包（跳过测试）
RUN mvn clean package -DskipTests -B

# --- Stage 2: 运行时 ---
# 使用 Debian 版本而非 Alpine，确保 libstdc++ 等原生库可用
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.source="https://github.com/brokestar233/fqnovel-unidbg"
LABEL org.opencontainers.image.description="FQNovel Unidbg Signature Server"
LABEL org.opencontainers.image.licenses="MIT"

# 安装 unidbg/JNA 需要的原生库 + tini + wget + openssl（SSL 私钥格式转换）
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      tini \
      wget \
      openssl \
      libstdc++6 \
      libc6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 从构建阶段复制 Spring Boot fat jar
COPY --from=builder /build/target/unidbg-boot-server-*.jar /app/app.jar

# 创建非 root 用户（基础镜像已有 UID/GID 1000，使用 1001 避免冲突）
RUN groupadd -g 1001 appuser && \
    useradd -u 1001 -g appuser -s /bin/sh -m appuser && \
    chown -R appuser:appuser /app

USER appuser

# 暴露端口
EXPOSE 8099

# 健康检查（支持 HTTP 和 HTTPS）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- --no-check-certificate https://localhost:8099/api/fqnovel/health 2>/dev/null || wget -qO- http://localhost:8099/api/fqnovel/actuator/health 2>/dev/null || exit 1

# JVM 参数可通过环境变量 JAVA_OPTS 覆盖
ENV JAVA_OPTS="-Xms512m -Xmx1g"

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar /app/app.jar"]
