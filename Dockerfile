# ==========================================
# 阶段一：基于 Maven 容器完成高效编译
# ==========================================
FROM maven:3.8.8-eclipse-temurin-17-alpine AS builder

# 设置容器内的临时编译工作目录
WORKDIR /build

# 利用 BuildKit 的独立本地缓存挂载，阻止每次编译都重复从公网下载整个 Maven 中央仓库
# 动作：将宿主机的 .m2 目录安全挂载进编译沙箱，大幅缩短后续代码更新时的扩容时间
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=bind,source=pom.xml,target=pom.xml \
    --mount=type=bind,source=src,target=src \
    mvn clean package -DskipTests && \
    # 物理提取生成的 jar 包，将其重命名定位到固定路径，便于下一阶段精准拦截
    mv target/*.jar /build/app.jar

# ==========================================
# 阶段二：构建极轻量化的生产运行时镜像
# ==========================================
FROM eclipse-temurin:17-jre-alpine

# 设置最终生产环境的工作目录
WORKDIR /app

# 核心动作：从上一个名为 builder 的沙箱中，跨阶段拦截并仅仅复制编译好的 jar 包
COPY --from=builder /build/app.jar app.jar

# 暴露出标准的 Spring Boot Web 端口
EXPOSE 8080

# 容器启动时的物理控制入口
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]