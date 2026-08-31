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
- 可独立注册的 Skill 框架，以及增肌饮食、运动健康、冷笑话、快速计算、JSON 格式化 5 个 Skill
- 可通过配置开关的统一关键词 RAG，整合各分支的项目 FAQ、技术资料与河南师范大学官方资料
- 大学生智能健康生活规划 Agent：一句最终目标自动拆解 12 步，协作天气、健康 RAG、营养 Skill、运动 Skill、完整性检查、结果页和二维码；独立步骤并行、依赖步骤串行
- Agent 支持短期天气/RAG 缓存、相关历史与 Prompt 压缩、SQLite 断点续跑、进度/取消命令，以及可跨重启恢复的每日健康提醒
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
agent.health.enabled=true
agent.health.generate-cover=false
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
| `帮我制定一个每周运动计划` | 进入运动健康 Skill，补充目标、城市、频率、时长等资料后生成计划 |
| `给我讲个冷笑话` | 进入冷笑话 Skill，本地返回一条纯文本笑话 |
| `计算 125乘36` | 由快速计算 Skill 直接复用安全计算工具，不必等待模型 |
| `JSON格式化：{"name":"summer"}` | 校验并美化 JSON；也支持下一条消息再发送 JSON |
| `介绍一下河南师范大学` | 命中河南师范大学本地 RAG 资料，再由模型整理回答 |
| `智谱 API Key 在哪里配置？` | 从项目 FAQ 检索资料，增强 Prompt 后交给模型回答 |
| `帮我制定未来7天的完整增肌健康生活方案……最后生成二维码` | 启动健康规划 Agent；支持增肌、减脂、提升体能和规律作息四种目标 |
| `继续刚才的健康计划` | 从失败步骤恢复，不重复调用已经成功的天气、RAG 和营养步骤 |
| `查看任务进度` | 查看健康规划 Agent 的完成比例、当前阶段和失败步骤 |
| `取消健康计划` | 清除当前健康任务、断点、完成计划和对应提醒 |
| `开启每日健康提醒 07:30` / `关闭健康提醒` | 每天推送当前计划的当天安排，或取消提醒；订阅可在程序重启后恢复 |
| `/clear` | 清除当前用户的对话上下文、待处理天气、待补充 Skill、健康提醒和 Agent 运行状态 |
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

自主规划型 Agent 已按 [大学生智能健康生活规划 Agent 设计文档](docs/autonomous-agent-scenario.md) 实现。它只在消息同时包含健康目标、完整计划和七日/二维码等长任务特征时启动，避免把普通 BMI、天气或饮食问题误路由到 Agent。目标资料会通过本地范围与健康边界校验；RAG、天气和营养三个只读步骤使用 Java 21 虚拟线程并行执行，训练、汇总、检查、结果页和二维码按依赖顺序串行执行。每步最多尝试两次，天气、RAG、封面或页面失败时按设计降级。封面默认为关闭，可通过 `agent.health.generate-cover=true` 开启。

推荐验收输入：

```text
帮我制定一份未来7天的增肌健康生活方案。我20岁，男，身高175cm，体重70kg，所在城市：上海，每周训练4次，每次训练60分钟，每日餐数4餐，健康确认：健康成人、无食物过敏。最后生成一份可以扫码查看的完整计划。
```

如缺少信息，机器人会一次性列出所有缺项，并在 30 分钟内接收下一条补充。第一版仅面向完成健康确认、无食物过敏的健康成年人；涉及疾病、伤病、孕期、进食障碍或用药影响时会停止自动规划并提示寻求专业意见。高德接口只提供未来三日天气，因此七日成品会明确要求第 4 天起每天重新确认天气。

自然语言解析支持“我在上海上学、一周练四次、每次一小时、一天三顿、身高 1 米 75”等常见说法，也支持 3～14 天的中文或数字计划周期及“长肌肉、控制体重、提高身体素质、早睡早起”等目标表达。训练次数、餐数等数值超出允许范围时会明确指出错误字段，不再笼统提示资料缺失。天气结果默认缓存 10 分钟，RAG 结果默认缓存 30 分钟；同一个查询的并发请求只访问一次下游，不同城市或不同问题可以并行查询。传给运动 Skill 的 RAG、天气和历史会被限制长度，只保留最近的健康相关上下文。热量、蛋白质和七日安排由本地代码计算与组装，减少不必要的模型 Token。

如果必需步骤连续失败，机器人会把运行状态和可恢复输出保存到本地 SQLite。30 分钟内即使重启程序，发送 `继续刚才的健康计划` 也能从失败步骤恢复；临时结果页、二维码和封面会在需要时重新生成。发送 `查看任务进度` 可以查看完成比例和当前阶段，发送 `取消健康计划` 会清除任务、成品和提醒。完成计划后可以发送 `开启每日健康提醒 07:30`；计划和订阅会跨重启恢复，计划天数结束后自动取消。

性能参数可在本地配置中调整：

```properties
agent.optimization.cache-enabled=true
agent.optimization.weather-cache-ttl=10m
agent.optimization.rag-cache-ttl=30m
agent.optimization.max-rag-prompt-chars=1200
agent.optimization.max-history-messages=6
agent.health.reminder.enabled=true
agent.health.reminder.plan-ttl=14d
agent.persistence.enabled=true
agent.persistence.database-path=runtime/agent-state.db
```

SQLite 文件位于 `runtime/agent-state.db`，已经被 Git 忽略。将 `agent.persistence.enabled` 改成 `false` 可恢复为仅内存断点。数据库保存结构化目标、步骤状态和计划成品，不保存用户最初发送的整段原文，也不保存图片、语音或 API Key。

Tool 用于执行计算、天气、二维码等单个动作；Skill 用于执行可重复、可测试的完整业务流程；RAG 用于从项目知识库检索事实资料。普通文字消息依次尝试明确 Skill、原有业务意图、RAG，均未命中时再直接使用 LLM 闲聊。

当前已将 GitHub 各非主分支的独有能力统一到同一套 `BotSkill`、`SkillRegistry` 和待补充状态机制中，共保留 5 个去重后的 Skill：

- `muscle-gain-meal-plan`：收集身体、活动和训练资料，在本地计算热量与营养素并生成训练日、休息日餐单。
- `exercise-health-advice`：根据运动目标、城市、训练频率、时长、健康情况和偏好生成运动建议，必要时可调用天气工具。
- `cold-joke`：识别“冷笑话、讲个笑话”等表达，为同一用户轮换返回适合文字和语音播放的纯文本笑话。
- `quick-calculator`：直接识别单步算式并复用现有安全计算工具；包含二维码、天气等后续步骤的复合任务仍交给多工具调用流程，避免截断链路。
- `json-format`：校验并美化 20,000 字以内的 JSON，支持指令中直接携带内容或在下一条消息补充。

其他分支的“项目帮助”能力与现有 `/help` 和自然语言帮助意图重复，因此没有再保留一套重复 Skill；“你有什么功能”“功能列表”“怎么用”等表达均会进入统一帮助流程。

GitHub 各分支原本包含多套不同的关键词检索器和重复知识。整合后只保留一套检索、评分、Top-K 和 Prompt 增强实现；知识库去重后包含 16 个主题：原有项目与学校知识之外，新增 WHO 身体活动、中国居民平衡膳食和国家卫生健康委体重管理三项健康规划资料。开启和关闭对比测试可直接编辑本地配置：

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
- 健康 Agent 的待补资料仍按用户隔离保存在内存中；开始执行后的断点和可恢复输出默认写入被 Git 忽略的本地 SQLite，30 分钟后过期。数据库不保存用户最初发送的整段原文；日志只记录运行 ID、步骤 ID、状态和错误类型。
- 完成的健康计划和每日提醒订阅默认保存在本地 SQLite；计划默认 14 天过期，计划周期结束、`/clear` 或“取消健康计划”会清除对应提醒。它们包含结构化健康资料，演示电脑应设置系统登录密码且不要分享数据库文件。
- 临时结果页使用不可预测的随机地址并设置有效期，同时禁止缓存、搜索来源泄露和页面嵌入；它仍只适合在可信局域网中演示，不应作为公开健康数据存储服务。
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
├── agent/                            # 健康 Agent 的目标解析、12 步计划、调度、检查与结果页
├── config/                           # 环境变量与客户端配置
├── conversation/                     # 按用户隔离的内存上下文
├── intent/                           # 本地规则、模型兜底与待补充意图
├── llm/                              # 智谱文本、视觉、图片与语音识别客户端
├── message/                          # 命令路由、去重和运行循环
├── rag/                              # 项目 FAQ 关键词检索和 Prompt 增强
├── result/                           # 临时计算结果存储和手机结果展示页
├── skill/                            # Skill 注册、待补充状态和 5 个业务 Skill
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
