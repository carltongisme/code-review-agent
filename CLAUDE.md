# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库中工作时提供指导。

## 项目职责

本项目在于实现一个 Code Review Agent。
1. 初始项目代码全量导入向量数据库
2. 当一个仓库（gitlab/github）的有发生变更的时候，会请求我提供的http接口进行代码审查。
3. 代码审查的过程中，需要关注到函数改动带来的影响（例如说有哪个上游函数调用到这个函数，如果大模型 LLM 需要知道上游函数是做什么的，你需要根据物理坐标去反查（Function Call）；同理下游函数也是）
4. 最后将大模型 LLM 的审查意见提交到对应仓库的 comment（通过/拒绝+拒绝原因）
5. 当通过并 merge 到 master 分支后，再主动更新向量数据库。

## 构建 / 运行命令

```bash
# 构建所有模块（编译 + 测试 + 打包）
mvn clean install

# 仅编译并运行测试
mvn test

# 运行单个测试类
mvn -pl <模块名> test -Dtest="ClassName"

# 运行 Spring Boot 应用（API 模块）
mvn -pl code-review-agent-api spring-boot:run

# 仅构建单个模块及其依赖
mvn -pl code-review-agent-api -am clean install
```

项目中没有 Maven Wrapper（`mvnw`），请使用本地安装的 Maven。

## 架构

多模块 Maven 项目，采用分层架构，Java 21，Spring Boot 3.2.4。

**模块依赖图（自上而下）：**

```
code-review-agent-api          → common, domain
code-review-agent-repository   → common, domain
code-review-agent-domain       → common
code-review-agent-common       → （无内部依赖）
```

**模块职责：**

- **`code-review-agent-api`** — Spring Boot 入口点（`@SpringBootApplication` 标注在 `CodeReviewAgentApiApplication` 上）。REST 控制器、请求/响应 DTO、输入校验均在此模块。依赖 `spring-boot-starter-web`。
- **`code-review-agent-domain`** — 业务逻辑、领域模型、领域服务、值对象。不依赖 `repository` 模块，保持业务逻辑与持久化解耦。
- **`code-review-agent-repository`** — 数据访问层（repository、DAO、持久化映射）。
- **`code-review-agent-common`** — 共享工具类、常量、横切关注点。不依赖其他任何项目模块。

内层（common、domain）对外层（api、repository）一无所知，以此保证清晰的领域驱动分层。

**关键依赖（根 POM 统一管理）：**

- Spring Boot 3.2.4（通过 `spring-boot-dependencies` BOM 管理依赖版本）
- Project Lombok 1.18.46（compile 作用域，所有模块）
- Apache Commons Collections 4.5.0（compile 作用域，所有模块）
- Spring Boot Starter Test（test 作用域，所有模块）

**应用入口：** `org.example.api.CodeReviewAgentApiApplication`（位于 API 模块）。项目中尚无 `application.properties` 或 `application.yml`，应用默认在 8080 端口启动，暂无配置的端点。

**Java 版本：** Amazon Corretto 21，编译器使用 `-parameters` 参数（在运行时保留方法参数名）。