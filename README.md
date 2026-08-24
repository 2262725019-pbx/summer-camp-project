# Summer Camp Project

这是一个 Java 21 + Spring Boot 微信 AI 机器人项目。项目通过微信 iLink SDK 收发消息，通过智谱开放平台完成连续对话、图片和语音识别，使用免费的 Microsoft Edge 在线朗读服务生成回复语音，并通过高德 Web 服务提供准确的中国行政区天气。

## 已实现功能

- 微信二维码登录、长轮询接收消息、发送文字、图片和原生微信语音
- 普通文字连续对话
- 微信语音转文字、语音上下文记录和免费语音回答；默认发送可播放 MP3，TTS 失败时自动改发文字
- 识别微信图片，并结合用户附带的问题回答
- 使用 `/image 图片描述` 生成图片并发回微信
- 本地规则优先、智谱结构化分类兜底的统一意图识别
- 高德实时天气、今天/明天/后天及未来三日预报
- 天气问题缺少地点时追问，并在 5 分钟内接续用户补充的地点
- 支持最多五轮 Function Calling / Tool Use，可执行依赖前一步结果的链式任务
- 整合计算、天气、日期时间、个人待办、上下文清除、图片生成、结果页和二维码生成共 10 个本地工具
- 可独立注册的 Skill 框架，以及本地计算的增肌饮食计划 Skill
- 可通过配置开关的项目 FAQ 关键词 RAG，命中资料后增强模型上下文
- 每个微信用户独立保存最近 10 轮、最多 12,000 字的上下文
- 上下文 30 分钟无新消息后自动过期，可用 `/clear` 主动清除
- SLF4J + Logback 多级日志、日志滚动和自动化测试
- GitHub Actions 在 Java 21 环境执行完整验证

## 环境要求

- JDK 21
- Git
- 一个智谱开放平台账号及 API Key
- 一个高德开放平台“Web 服务”类型的 Key（只在使用天气功能时需要）
- 不需要预先安装 Maven，项目自带 Maven Wrapper

微信 SDK 通过 Maven 引入：`io.github.lith0924:wechat-ilink-sdk:2.3.3`。

## 配置

### IDEA 直接运行（推荐）

打开本地配置文件 [config/application-local.properties](config/application-local.properties)，填写智谱和高德两个 Key：

```properties
ai.zhipu.api-key=你的新智谱API Key
weather.amap.api-key=你的高德Web服务Key
rag.enabled=true
```

高德 Key 的类型必须选择“Web 服务”，不能选择 Android、iOS 或 Web JS。暂时不查询天气时，高德 Key 可以先保留占位符，其他聊天能力仍可使用。

然后在 IDEA 中打开 `src/main/java/com/summercamp/project/Application.java`，点击 `main` 方法旁边的绿色运行按钮。程序会自动：

1. 启动微信机器人；
2. 生成 `runtime/wechat-login-qr.png`；
3. 使用 Windows 默认图片查看器打开二维码；
4. 扫码成功后删除二维码并开始接收消息。

`config/application-local.properties` 已被 `.gitignore` 忽略，不能提交到 GitHub。团队成员可以复制 `config/application-local.properties.example` 建立各自的本地配置。

### 终端运行（可选）

普通构建和测试不会访问微信或智谱。如果临时使用终端环境变量覆盖本地配置，可以设置：

```powershell
$env:ZHIPU_API_KEY = "你的智谱API Key"
$env:BOT_ENABLED = "true"
```

其他配置已经提供默认值，通常不需要设置。如需覆盖，可使用：

```powershell
$env:ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
$env:ZHIPU_CHAT_API_PATH = "/chat/completions"
$env:ZHIPU_IMAGE_API_PATH = "/images/generations"
$env:ZHIPU_ASR_API_PATH = "/audio/transcriptions"
$env:ZHIPU_TEXT_MODEL = "glm-4.7-flash"
$env:ZHIPU_TEXT_FALLBACK_MODELS = "glm-4-flash-250414"
$env:ZHIPU_VISION_MODEL = "glm-4.6v-flash"
$env:ZHIPU_VISION_FALLBACK_MODELS = "glm-4.1v-thinking-flash,glm-4v-flash"
$env:ZHIPU_IMAGE_MODEL = "cogview-3-flash"
$env:ZHIPU_IMAGE_SIZE = "1024x1024"
$env:ZHIPU_ASR_MODEL = "glm-asr-2512"
$env:EDGE_TTS_VOICE = "zh-CN-XiaoxiaoNeural"
$env:EDGE_TTS_CONNECT_TIMEOUT = "20s"
$env:AMAP_API_KEY = "你的高德Web服务Key"
$env:ZHIPU_TIMEOUT = "60s"
```

模型会按消息类型自动选择：

| 消息类型 | 默认模型 | 接口 |
|---|---|---|
| 普通文字 | `glm-4.7-flash` | `/chat/completions` |
| 微信图片 | `glm-4.6v-flash` | `/chat/completions` |
| `/image` 绘图命令 | `cogview-3-flash` | `/images/generations` |
| 微信语音转文字 | `glm-asr-2512` | `/audio/transcriptions` |
| 文字转微信语音 | Microsoft Edge `zh-CN-XiaoxiaoNeural` | 免费朗读服务，无需 API Key |

`ZHIPU_API_KEY` 必须来自智谱开放平台，不是网页登录密码。真实 Key 只填写在已被 Git 忽略的 `config/application-local.properties`，不要写入仓库、日志、截图或群聊。

普通对话默认先调用 `glm-4.7-flash`。如果平台返回模型繁忙、限流或临时服务错误，程序会自动尝试免费的 `glm-4-flash-250414`，备用模型可通过 `ZHIPU_TEXT_FALLBACK_MODELS` 调整。

图片识别默认先调用 `glm-4.6v-flash`。如果平台返回模型繁忙、限流或临时服务错误，程序会依次尝试 `glm-4.1v-thinking-flash` 和 `glm-4v-flash`。备用模型可通过 `ZHIPU_VISION_FALLBACK_MODELS` 调整。

默认单张图片上限是 10 MB，可用 `BOT_IMAGE_MAX_BYTES` 修改。语音上限是 25 MB、30 秒，可用 `BOT_VOICE_MAX_BYTES` 和 `BOT_VOICE_MAX_DURATION` 修改。智谱请求超时默认 60 秒，可用 `ZHIPU_TIMEOUT` 修改。

微信有转写文字时程序会直接复用；没有转写时，SILK 会在本地转换为 24 kHz、16-bit、单声道 WAV 后提交智谱 ASR，MP3 会直接提交。回复语音由 Microsoft Edge 在线朗读服务生成 MP3，不使用智谱 TTS 余额，也不需要额外 API Key；该能力需要正常访问互联网，单次失败会自动重试，仍不可用时才回退为文字。

iLink 当前可能接受原生语音请求却不向微信客户端投递，因此默认 `BOT_VOICE_REPLY_MODE=file`，把回答作为可播放的 MP3 文件可靠送达。平台恢复后可改为 `native` 尝试原生语音气泡。

## 构建与运行

Windows：

```powershell
# 编译并运行全部测试
.\mvnw.cmd clean verify

# 启动机器人
.\mvnw.cmd spring-boot:run

# 或先构建，再运行可执行 JAR
java -jar target\summer-camp-project-1.0.0-SNAPSHOT.jar
```

Linux 或 macOS：

```bash
./mvnw clean verify
./mvnw spring-boot:run
java -jar target/summer-camp-project-1.0.0-SNAPSHOT.jar
```

启动后，项目会在 `runtime/wechat-login-qr.png` 生成微信登录二维码。打开图片并在 3 分钟内扫码确认；登录成功后二维码文件会自动删除。机器人只有收到某个用户的消息后，才具备向该用户回复所需的微信上下文。

## 微信命令

| 命令或消息 | 作用 |
|---|---|
| 普通文字 | 结合该用户最近上下文生成文本回复 |
| 普通微信语音 | 识别语音内容，结合上下文处理，并用语音回答 |
| 图片，可附文字 | 识别图片并回答问题 |
| `帮我识别一张图片`（未附图） | 提示用户先发送图片，不调用模型 |
| `/image 一只在月球散步的橘猫` | 生成图片并发回微信 |
| `帮我生成一张图片：一只橘猫`、`给我画一只橘猫` | 自动识别绘图意图，生成图片并发回微信 |
| `未来三天北京天气` | 返回固定模板的准确天气预报，不让模型编造数据 |
| `明天天气怎么样`，再发 `宜春市袁州区` | 追问并接续完成天气查询 |
| `帮我计算 125 乘 36` | 模型调用 `calculate` 工具并回答 `4500` |
| `计算 12.5 * 3 + sqrt(9)` | 安全计算包含括号和数学函数的表达式 |
| `现在几点？` | 调用日期时间工具，默认使用 `Asia/Shanghai` 时区 |
| `把“写日报”加入待办` | 为当前微信用户添加个人待办 |
| `查看我的待办` / `完成第 1 项待办` | 查看或完成当前用户的待办，用户之间相互隔离 |
| `把 https://example.com 生成二维码` | 生成真实二维码图片并发回微信 |
| `添加“写日报”到待办，然后查看待办列表` | 连续调用两个工具，后一步读取前一步结果 |
| `帮我制定一个增肌饮食计划` | 进入增肌饮食 Skill，补充身体和训练资料后生成训练日、休息日计划 |
| `智谱 API Key 在哪里配置？` | 从项目 FAQ 检索资料，增强 Prompt 后交给模型回答 |
| `/clear` | 清除当前用户的对话上下文、待处理天气和待补充 Skill 请求 |
| `/help` | 查看机器人帮助 |

文件、视频和不支持的语音编码会收到明确提示，暂不送入模型。

## Function Calling / Tool Use

Function Calling 的作用是让模型决定“需要调用哪个函数以及传入哪些参数”，真正的函数由本项目在本地执行。完整流程如下：

1. 程序把用户消息和可用工具的 JSON Schema 一起发送给智谱模型；
2. 模型如果需要外部数据，会返回 `tool_calls`，而不是直接编造答案；
3. 程序按工具名称从白名单中查找工具、校验参数并执行；
4. 程序把带有相同 `tool_call_id` 的执行结果交回模型；
5. 模型根据真实工具结果组织最终微信回复。

当前提供 10 个去重后的工具：

| 工具名 | 作用 | 参数 |
|---|---|---|
| `get_weather` | 查询高德实时天气或预报 | `location`：地点；`period`：`CURRENT`、`TODAY`、`TOMORROW`、`DAY_AFTER_TOMORROW` 或 `THREE_DAYS` |
| `calculate` | 计算表达式，同时兼容精确两数四则运算 | 推荐传 `expression`；兼容 `left`、`right`、`operator` |
| `create_result_page` | 创建可由手机浏览器访问的临时计算结果页 | `expression`：表达式；`result`：计算结果；可选 `title` |
| `get_current_datetime` | 获取当前日期、时间和星期 | 可选 `timezone`，默认 `Asia/Shanghai` |
| `add_todo` | 添加当前用户的待办 | `item`：待办内容 |
| `list_todos` | 查看当前用户的待办 | 无参数 |
| `complete_todo` | 按序号完成待办 | `index`：从 1 开始的待办序号 |
| `clear_memory` | 清除当前用户上下文和待补充天气请求 | 无参数 |
| `generate_image` | 在工具链中根据描述生成图片 | `prompt`：图片描述 |
| `generate_qr_code` | 生成二维码图片 | `text`：承载内容；可选 `size` |

工具签名使用 JSON Schema 的 `type`、`properties`、`description`、`enum`、`required`、长度/数值范围和 `additionalProperties` 字段描述。程序只允许执行已注册的工具，不执行模型返回的代码或系统命令；重复工具名会阻止项目启动，未知工具、多余参数、错误类型、缺少必填参数和无效 JSON 都会转换成结构化错误交回模型。单轮最多执行 4 个工具，连续调用最多 5 轮，防止模型陷入无限调用。

工具结果统一分为文字、结构化数据、直接完成和图片四种类型。图片生成和二维码工具产生的图片会由消息层真正发送到微信，而不只是让模型用文字声称“已经生成”。

多工具协作采用“同轮独立工具并行、跨轮依赖工具串行”的策略：计算、时间、天气和二维码等无共享状态的工具，如果由模型在同一轮同时提出，会使用 Java 21 虚拟线程并行执行；待办、上下文清理和图片生成等包含状态变更或外部资源限制的工具保持串行。只要同一轮存在一个非并行安全工具，该轮就全部按模型给出的顺序执行。并行结果仍按原始 `tool_calls` 顺序交回模型，单个工具失败会转换成结构化错误，不会阻止同轮其他工具完成。

跨轮调用天然保持依赖顺序。例如“计算 125×36，然后把结果生成二维码”会依次执行计算、创建临时结果页、把结果页 URL 生成二维码；扫码后会在浏览器中显示表达式和结果。结果页默认保存 30 分钟，程序重启后清空。“查询北京天气，同时告诉我现在几点”则可以在同一轮并行查询天气和时间。

结果页默认监听 `0.0.0.0:8080`，并自动选择电脑的局域网 IPv4 地址生成链接。手机和电脑需要连接同一局域网，Windows 防火墙弹窗中需要允许 Java 访问专用网络。如果自动选择的地址不正确，在本地 `config/application-local.properties` 中配置：

```properties
result-page.public-base-url=http://你的电脑局域网IP:8080
result-page.port=8080
result-page.ttl=30m
```

例如电脑地址为 `192.168.1.100`，则填写 `http://192.168.1.100:8080`。该配置不要填写手机 IP，也不要填写 `localhost`，否则手机无法访问。

天气消息优先走 Function Calling。如果模型服务临时不可用，程序会回退到原有的高德直连流程，保证天气查询仍可回答。

## Skill 与 RAG

Tool 用于执行计算、天气、二维码等单个动作；Skill 用于执行可重复、可测试的完整业务流程；RAG 用于从项目知识库检索事实资料。普通文字消息依次尝试明确 Skill、原有业务意图、RAG，均未命中时再直接使用 LLM 闲聊。

当前 `muscle-gain-meal-plan` Skill 会先收集性别、年龄、身高、体重、日常活动、每周训练次数和时长、每日餐数及健康确认，然后在本地计算基础代谢、增肌热量和三大营养素，最后从本地常见食物库生成训练日与休息日餐单。日常活动支持久坐、轻度、中度和高度，其中重度、高强度、非常活跃会自动按高度处理；资料错误时会直接列出未识别字段。数值计算不依赖大模型，输出仅供健康饮食参考，不替代医生或注册营养师建议。

RAG 知识库存放于项目资源中，目前包含启动登录、API Key、本项目能力、天气语音图片、Tool/Skill/RAG、结果页网络及 Git 密钥安全等 FAQ。开启和关闭对比测试可直接编辑本地配置：

```properties
rag.enabled=true
rag.top-k=3
rag.min-score=2
rag.max-context-chars=2500
```

将 `rag.enabled` 改为 `false` 后，同一个问题不会携带检索资料，直接交给模型。RAG 只改变传给模型的参考上下文，不会把增强后的文字写入用户对话记忆。

## 上下文与隐私

- 上下文只保存在当前 Java 进程内，重启程序后自动清空。
- 不同微信用户的数据相互隔离。
- 只保存文字和“用户发送了图片”的占位描述，不保存图片 Base64 原始内容。
- 语音识别后的文字与普通文字共用同一份上下文，不保存语音二进制内容。
- 待补充天气请求只保存在内存中，5 分钟后过期，程序重启后清空。
- 待补充 Skill 请求只保存在内存中，5 分钟后过期；身体资料不会写入日志或持久化文件。
- 个人待办按微信用户保存在内存中，每人最多 100 项，程序重启后清空。
- 对话历史由项目主动随请求发送给智谱，项目不依赖模型平台的服务端会话状态。

## 日志

业务代码统一依赖 SLF4J 的 `Logger` 接口，不直接依赖具体日志实现。默认日志级别是 `INFO`，日志同时写入控制台和 `logs/application.log`。日志按天或达到 10 MB 时滚动，保留 14 天，总量最多 200 MB。

临时启用 `DEBUG` 日志：

```powershell
$env:LOG_LEVEL = "DEBUG"
.\mvnw.cmd spring-boot:run
```

## 主要代码结构

```text
src/main/java/com/summercamp/project/
├── Application.java                  # Spring Boot 入口
├── config/                           # 环境变量与客户端配置
├── conversation/                     # 按用户隔离的内存上下文
├── intent/                           # 本地规则、模型兜底与待补充意图
├── llm/                              # 智谱文本、视觉、图片与语音识别客户端
├── message/                          # 命令路由、去重和运行循环
├── rag/                              # 项目 FAQ 关键词检索和 Prompt 增强
├── result/                           # 临时计算结果存储和手机结果展示页
├── skill/                            # Skill 注册、待补充状态和增肌饮食计划
├── speech/                           # SILK/PCM/WAV 转换、ASR 与免费 Edge TTS
├── tool/                             # 统一工具框架、Schema 校验及 10 个本地工具
├── weather/                          # 高德行政区解析和天气固定模板
└── wechat/                           # iLink SDK 适配与微信收发
```

## 团队协作建议

1. 从 `main` 拉取最新代码后创建功能分支，例如 `feature/image-recognition`。
2. 每次提交只完成一个清晰目标，并补充相应测试。
3. 推送功能分支并发起 Pull Request，请至少一名成员检查后再合并。
4. 合并前确保本地 `clean verify` 和 GitHub Actions 均通过。

## 许可证

本项目使用 [MIT License](LICENSE)。
