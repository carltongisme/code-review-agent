# =========================================================================
# 阶段一：导入完整多模块工程上下文，利用 Maven 反应堆完成定向编译 (Java 21)
# 关键逻辑节点：引入具备 Java 21 运行时的 Alpine 版 Maven 镜像作为基础编译沙箱
# =========================================================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

# 设定编译沙箱内的临时工作根目录
WORKDIR /build

# 关键逻辑节点：完整导入多模块拓扑结构
# 动作：由于各模块间存在复杂的父子与兄弟依赖，必须将根目录下的所有配置文件及源码全量复制入沙箱
COPY . .

# 复杂算法分支/编译控制节点：调用反应堆机制进行定向差量打包
# 参数含义：
# -pl (project-list): 指定打包的目标子模块，此处填写你的子模块文件夹名称
# -am (also-make): 驱动反应堆自动向上追溯，主动协助编译该子模块所依赖的所有同级兄弟模块
# -DskipTests: 拦截并跳过单元测试执行，加速构建流程
# --mount=type=cache: 挂载宿主机物理目录，拦截并复用已有的 .m2 依赖缓存，缩短扩容时间
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -pl code-review-agent-api -am -DskipTests && \
    mv code-review-agent-api/target/*.jar /build/app.jar

# =========================================================================
# 阶段二：构建极轻量化的生产运行时镜像 (Java 21)
# 关键逻辑节点：完全剥离整个多模块源码与 Maven 工具链，仅保留最终的纯净运行时
# =========================================================================
FROM eclipse-temurin:21-jre-alpine

# 设定容器最终生产环境的物理运行目录
WORKDIR /app

# 时区校正节点：由于 Alpine 默认使用 UTC 时间，必须强行注入亚洲/上海时区层
RUN apk add --no-cache tzdata git && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 关键逻辑节点：跨阶段安全拦截编译产物
# 动作：仅从 builder 阶段的沙箱中将 app.jar 复制到当前生产镜像内，阻止源码泄露
COPY --from=builder /build/app.jar app.jar

# 声明向外暴露的物理网络通信端口
EXPOSE 8080

# 容器启动时的物理控制入口点
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]