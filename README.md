# Summer Camp Project

这是六人暑期夏令营小组的 Java 项目骨架，使用 Maven 管理依赖，并通过 SLF4J + Logback 提供统一的多级日志能力。

## 环境要求

- JDK 21 或更高版本
- Git
- 不需要预先安装 Maven，项目自带 Maven Wrapper

## 快速开始

克隆项目后，在项目根目录执行：

```powershell
# Windows：编译并运行测试
.\mvnw.cmd clean verify

# Windows：运行日志示例
.\mvnw.cmd exec:java
```

Linux 或 macOS 使用：

```bash
./mvnw clean verify
./mvnw exec:java
```

## 日志

业务代码统一依赖 SLF4J 的 `Logger` 接口，不直接依赖具体日志实现：

```java
private static final Logger LOGGER = LoggerFactory.getLogger(YourClass.class);

LOGGER.debug("调试信息");
LOGGER.info("普通运行信息");
LOGGER.warn("需要关注的情况");
LOGGER.error("功能执行失败", exception);
```

默认日志级别是 `INFO`，日志会同时写入控制台和 `logs/application.log`。日志按天或达到 10 MB 时滚动，保留 14 天，总量最多 200 MB。

临时启用 `DEBUG` 日志：

```powershell
# Windows PowerShell
$env:LOG_LEVEL = "DEBUG"
.\mvnw.cmd exec:java
```

```bash
# Linux / macOS
LOG_LEVEL=DEBUG ./mvnw exec:java
```

## 项目结构

```text
src/
├── main/
│   ├── java/com/summercamp/project/   # 项目代码
│   └── resources/logback.xml          # 日志配置
└── test/
    └── java/com/summercamp/project/   # 测试代码
```

## 团队协作建议

1. 从 `main` 拉取最新代码后创建个人功能分支，例如 `feature/login`。
2. 每次提交只完成一个清晰目标，提交信息说明“做了什么”。
3. 推送功能分支并发起 Pull Request，请至少一名组员检查后再合并。
4. 合并前确保本地测试和 GitHub Actions 均通过。

## 许可证

本项目使用 [MIT License](LICENSE)。
