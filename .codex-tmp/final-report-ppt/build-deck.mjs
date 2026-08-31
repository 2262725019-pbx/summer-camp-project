import fs from "node:fs/promises";
import path from "node:path";
import { Presentation, PresentationFile } from "@oai/artifact-tool";

const TMP = "D:/ykdSummerText/.codex-tmp/final-report-ppt";
const OUT = "D:/ykdSummerText/artifacts/summer-camp-project-final-report.pptx";
const HERO = `${TMP}/assets/multimodal-agent-hero.png`;
const RENDER = `${TMP}/artifact-render`;
const heroBytes = await fs.readFile(HERO);

const W = 1280;
const H = 720;
const C = {
  canvas: "#FFFFFF",
  ink: "#101114",
  muted: "#60656F",
  panel: "#EDEDED",
  panelBlue: "#EAF5FB",
  rule: "#B8BCC4",
  accent: "#6DCBF4",
  blue: "#3D8DFF",
  green: "#25C78A",
  white: "#FFFFFF",
  dark: "#14253D",
};

const deck = Presentation.create({ slideSize: { width: W, height: H } });

function addText(slide, text, x, y, w, h, size = 22, options = {}) {
  const box = slide.shapes.add({
    geometry: "textbox",
    name: options.name,
    position: { left: x, top: y, width: w, height: h },
    fill: "none",
    line: { style: "solid", fill: "none", width: 0 },
  });
  box.text = text;
  box.text.style = {
    fontSize: size,
    bold: Boolean(options.bold),
    color: options.color ?? C.ink,
    alignment: options.align ?? "left",
    verticalAlignment: options.valign ?? "top",
    autoFit: options.autoFit ?? "shrinkText",
    wrap: "square",
  };
  return box;
}

function addPanel(slide, x, y, w, h, options = {}) {
  const geometry = options.geometry ?? "roundRect";
  const config = {
    geometry,
    name: options.name,
    position: { left: x, top: y, width: w, height: h },
    fill: options.fill ?? C.panel,
    line: {
      style: "solid",
      fill: options.line ?? "none",
      width: options.lineWidth ?? 0,
    },
  };
  if (geometry === "rect" || geometry === "textbox" || geometry === "roundRect") {
    config.borderRadius = options.radius ?? "rounded-xl";
  }
  return slide.shapes.add(config);
}

function addRule(slide, x, y, w, color = C.rule, thickness = 2) {
  return slide.shapes.add({
    geometry: "rect",
    position: { left: x, top: y, width: w, height: thickness },
    fill: color,
    line: { style: "solid", fill: "none", width: 0 },
  });
}

function addArrow(slide, x, y, w, h, color = C.accent) {
  return slide.shapes.add({
    geometry: "rightArrow",
    position: { left: x, top: y, width: w, height: h },
    fill: color,
    line: { style: "solid", fill: "none", width: 0 },
  });
}

function addSlideTitle(slide, title, index, eyebrow = "SUMMER CAMP PROJECT") {
  addText(slide, eyebrow, 56, 30, 360, 26, 14, { bold: true, color: C.blue });
  addText(slide, title, 56, 68, 1120, 66, 40, { bold: true });
  addText(slide, String(index).padStart(2, "0"), 1180, 666, 44, 22, 14, {
    color: C.muted,
    align: "right",
  });
}

function addNotes(slide, talk, sources) {
  const sourceLines = sources.map((source) => `- ${source}`).join("\n");
  slide.speakerNotes.textFrame.setText(`${talk}\n\n[Sources]\n${sourceLines}\n[/Sources]`);
  slide.speakerNotes.setVisible(true);
}

function addLabel(slide, text, x, y, w, fill = C.blue, color = C.white) {
  const pill = addPanel(slide, x, y, w, 34, { fill, radius: "rounded-full" });
  pill.text = text;
  pill.text.style = {
    fontSize: 16,
    bold: true,
    color,
    alignment: "center",
    verticalAlignment: "middle",
    autoFit: "shrinkText",
  };
  return pill;
}

// 1. Cover — Codex Grid slide-08 silhouette.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addText(slide, "夏令营项目最终成果汇报", 56, 44, 500, 30, 16, { bold: true, color: C.blue });
  addText(slide, "微信多模态\nAI Agent", 56, 136, 520, 150, 58, { bold: true });
  addText(slide, "从消息收发到自主规划的完整闭环", 56, 315, 520, 48, 28, { color: C.dark });
  addRule(slide, 56, 400, 240, C.blue, 5);
  addText(slide, "Java 21 · Spring Boot · iLink · 智谱 · 高德 · SQLite", 56, 430, 530, 36, 18, { color: C.muted });
  addText(slide, "项目组：__________    汇报人：__________", 56, 594, 530, 32, 18, { color: C.muted });
  addPanel(slide, 660, 44, 564, 582, { fill: C.panelBlue, line: C.rule, lineWidth: 1 });
  slide.images.add({
    blob: heroBytes,
    contentType: "image/png",
    alt: "多模态移动 AI Agent 主视觉",
    fit: "cover",
    geometry: "roundRect",
    borderRadius: "rounded-xl",
    position: { left: 660, top: 44, width: 564, height: 582 },
  });
  addText(slide, "01", 1180, 666, 44, 22, 14, { color: C.muted, align: "right" });
  addNotes(slide,
    "开场先给出一句话定位：我们没有停留在问一句答一句，而是完成了一个能感知、能行动、能自主规划并交付结果的微信 AI Agent。",
    ["D:/ykdSummerText/README.md", "Generated visual: multimodal-agent-hero.png"]);
}

// 2. Core problem and value.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "我们解决的不是一次问答，而是一整套任务", 2);
  addPanel(slide, 56, 176, 510, 406, { fill: C.panelBlue });
  addLabel(slide, "用户只说最终目标", 86, 205, 188, C.green);
  addText(slide,
    "“帮我制定未来 7 天的完整健康生活方案，结合天气、饮食和训练，最后生成结果页面与二维码。”",
    86, 270, 450, 210, 30, { bold: true, color: C.dark });
  addText(slide, "不提供步骤，不指定工具，不要求用户反复追问。", 86, 505, 430, 46, 20, { color: C.muted });

  addArrow(slide, 588, 343, 70, 32, C.accent);
  const stages = [
    ["理解", "提取目标与约束"],
    ["规划", "拆解依赖与并行步骤"],
    ["执行", "协作天气、RAG、Skill、Tool"],
    ["交付", "七日计划、页面与二维码"],
  ];
  stages.forEach((item, i) => {
    const y = 170 + i * 108;
    addText(slide, `0${i + 1}`, 682, y + 12, 56, 48, 28, { bold: true, color: C.blue });
    addText(slide, item[0], 752, y, 170, 40, 26, { bold: true });
    addText(slide, item[1], 752, y + 44, 390, 32, 19, { color: C.muted });
    if (i < stages.length - 1) addRule(slide, 752, y + 92, 390, C.rule, 1);
  });
  addNotes(slide,
    "强调 Agent 与普通聊天的差别：用户给的是目标，系统负责决定步骤、选择能力并形成最终成品。",
    ["D:/ykdSummerText/README.md", "D:/ykdSummerText/src/main/java/com/summercamp/project/agent/AgentRouter.java"]);
}

// 3. Evolution timeline — Codex Grid slide-17 silhouette.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "两周迭代让项目完成三次能力跃迁", 3);
  addRule(slide, 72, 344, 1136, C.ink, 2);
  const items = [
    { x: 72, date: "第一阶段", title: "Bot 能用", body: "Spring Boot 项目骨架\n微信二维码登录与长轮询\nSLF4J / Logback / CI", color: C.accent },
    { x: 450, date: "第二阶段", title: "Bot 能感知与行动", body: "文字、图片、语音\n天气与 Function Calling\nTool、Skill、RAG", color: C.blue },
    { x: 828, date: "第三阶段", title: "Bot 能自主完成任务", body: "12 步健康规划 Agent\n并行、缓存、Prompt 压缩\nSQLite 续跑与每日提醒", color: C.green },
  ];
  items.forEach((item) => {
    addPanel(slide, item.x, 337, 16, 16, { geometry: "ellipse", fill: item.color, radius: 0 });
    addText(slide, item.date, item.x, 278, 150, 30, 17, { bold: true, color: item.color });
    addText(slide, item.title, item.x, 390, 320, 42, 27, { bold: true });
    addText(slide, item.body, item.x, 447, 320, 126, 19, { color: C.muted });
  });
  addNotes(slide,
    "按能力而不是按日期流水账讲述：先打通微信，再补齐多模态与工具体系，最后升级成自主规划 Agent。",
    ["D:/ykdSummerText/README.md", "D:/ykdSummerText/src/main/java/com/summercamp/project/Application.java"]);
}

// 4. Architecture diagram.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "统一消息路由把多种能力组织成一条链路", 4);

  // Connectors first so they remain behind nodes.
  addArrow(slide, 262, 345, 60, 28, C.accent);
  addArrow(slide, 558, 345, 60, 28, C.accent);
  addArrow(slide, 888, 345, 60, 28, C.accent);

  addPanel(slide, 56, 190, 205, 362, { fill: C.panelBlue });
  addText(slide, "微信输入", 82, 220, 150, 36, 26, { bold: true });
  ["文字 + 上下文", "图片 + 问题", "语音 + 转写", "命令 / 媒体"].forEach((v, i) =>
    addText(slide, `• ${v}`, 82, 292 + i * 52, 150, 34, 19, { color: C.dark }));

  addPanel(slide, 322, 238, 236, 270, { fill: C.ink });
  addText(slide, "MessageProcessor", 347, 275, 186, 40, 25, { bold: true, color: C.white, align: "center" });
  addRule(slide, 352, 335, 176, C.accent, 3);
  addText(slide, "去重 · 限制检查\n命令优先 · 意图识别\n待处理状态 · 统一回复", 347, 360, 186, 108, 19, {
    color: C.white, align: "center"
  });

  addPanel(slide, 618, 170, 270, 392, { fill: C.panel });
  addText(slide, "智能编排", 646, 200, 214, 38, 26, { bold: true });
  const orchestration = [
    ["Intent", "本地规则 + 模型兜底"],
    ["Skill", "明确业务流程"],
    ["RAG", "可信知识增强"],
    ["Tool", "白名单原子动作"],
    ["Agent", "规划、执行、评估"],
  ];
  orchestration.forEach((row, i) => {
    addText(slide, row[0], 646, 260 + i * 56, 74, 30, 19, { bold: true, color: C.blue });
    addText(slide, row[1], 728, 260 + i * 56, 140, 30, 17, { color: C.muted });
  });

  addPanel(slide, 948, 190, 276, 362, { fill: C.panelBlue });
  addText(slide, "服务与输出", 976, 220, 220, 38, 26, { bold: true });
  ["智谱：聊天 / 视觉 / 绘图 / ASR", "高德：行政区与天气", "Edge TTS：免费语音合成", "SQLite：状态与提醒", "文本 / 图片 / 音频 / 页面 / 二维码"].forEach((v, i) =>
    addText(slide, `• ${v}`, 976, 282 + i * 48, 220, 36, 17, { color: C.dark }));

  addNotes(slide,
    "这页只讲一次完整消息路径：微信消息先进入统一路由，再由 Intent、Skill、RAG、Tool 或 Agent 接管，最后调用外部服务并形成多种输出。",
    ["D:/ykdSummerText/src/main/java/com/summercamp/project/message/MessageProcessor.java", "D:/ykdSummerText/src/main/java/com/summercamp/project/llm/ZhipuAiClient.java"]);
}

// 5. Capability columns — Codex Grid slide-07 silhouette.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "三层能力让机器人既能理解，也能执行", 5);
  const cols = [
    { x: 56, color: C.green, no: "01", title: "多模态交互", body: "连续文字对话\n图片识别与生成\n语音识别与 MP3 回复\n超限与异常媒体提示" },
    { x: 442, color: C.blue, no: "02", title: "可信信息", body: "高德实时与三日天气\n项目 FAQ 与校园知识 RAG\n每用户独立上下文\n固定模板避免事实编造" },
    { x: 828, color: C.accent, no: "03", title: "行动与交付", body: "10 个本地工具\n5 个可注册 Skill\n串行 / 并行多工具\n结果页面与真实二维码" },
  ];
  cols.forEach((col) => {
    addPanel(slide, col.x, 214, 330, 330, { fill: C.panel });
    addPanel(slide, col.x + 24, 180, 58, 58, { geometry: "ellipse", fill: col.color, radius: 0 });
    addText(slide, col.no, col.x + 24, 191, 58, 34, 18, { bold: true, color: C.white, align: "center" });
    addText(slide, col.title, col.x + 28, 265, 274, 44, 27, { bold: true });
    addRule(slide, col.x + 28, 326, 82, col.color, 4);
    addText(slide, col.body, col.x + 28, 355, 274, 156, 20, { color: C.dark });
  });
  addNotes(slide,
    "用三层归纳而不是逐条念功能：多模态解决输入输出，可信信息解决事实来源，行动与交付解决真正完成任务。",
    ["D:/ykdSummerText/README.md", "D:/ykdSummerText/src/main/java/com/summercamp/project/message/MessageProcessor.java"]);
}

// 6. Tool / Skill / RAG collaboration.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "Tool、Skill、RAG 分工明确，组合后更智能", 6);
  addText(slide, "用户消息", 66, 190, 150, 38, 24, { bold: true });
  addArrow(slide, 220, 196, 90, 26, C.accent);
  addText(slide, "统一路由", 326, 190, 150, 38, 24, { bold: true });
  addArrow(slide, 476, 196, 90, 26, C.accent);
  addText(slide, "按意图选择最短、最可靠的执行路径", 588, 190, 560, 38, 24, { bold: true, color: C.blue });

  const cols = [
    { x: 56, title: "Tool", subtitle: "原子动作", body: "计算、天气、时间、待办、图片、二维码、结果页\n\n由模型通过 JSON Schema 选择，本地白名单校验后执行。", example: "例：计算 125×36", color: C.blue },
    { x: 442, title: "Skill", subtitle: "可复用业务流程", body: "增肌饮食、运动健康、冷笑话、快速计算、JSON 格式化\n\n明确关键词优先命中，减少无效模型调用。", example: "例：制定增肌饮食计划", color: C.green },
    { x: 828, title: "RAG", subtitle: "可信知识上下文", body: "项目 FAQ、技术资料、健康知识、河南师范大学资料\n\n先检索最相关内容，再增强 Prompt。", example: "例：API Key 在哪里配置", color: C.accent },
  ];
  cols.forEach((col) => {
    addPanel(slide, col.x, 274, 330, 300, { fill: C.panel });
    addText(slide, col.title, col.x + 26, 300, 120, 48, 34, { bold: true, color: col.color });
    addText(slide, col.subtitle, col.x + 150, 313, 145, 30, 18, { bold: true, color: C.muted, align: "right" });
    addRule(slide, col.x + 26, 362, 278, C.rule, 1);
    addText(slide, col.body, col.x + 26, 386, 278, 120, 18, { color: C.dark });
    addText(slide, col.example, col.x + 26, 526, 278, 30, 17, { bold: true, color: col.color });
  });
  addNotes(slide,
    "Tool、Skill、RAG 不是三个同义词。Tool 做动作，Skill 承载流程，RAG 提供依据；统一路由让它们在同一条消息链路中协作。",
    ["D:/ykdSummerText/src/main/java/com/summercamp/project/tool/ToolRegistry.java", "D:/ykdSummerText/src/main/java/com/summercamp/project/skill/SkillRegistry.java", "D:/ykdSummerText/src/main/java/com/summercamp/project/rag/KeywordRagRetriever.java"]);
}

// 7. Agent workflow.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "一个最终目标会被自动拆成 12 个可追踪步骤", 7);
  const phases = [
    { x: 56, w: 268, label: "理解目标", color: C.green, steps: "01 解析自然语言\n02 校验完整性与安全边界" },
    { x: 350, w: 340, label: "并行获取依据", color: C.accent, steps: "03 检索健康 RAG\n04 查询三日天气\n05 计算营养目标" },
    { x: 716, w: 508, label: "串行生成与交付", color: C.blue, steps: "06 生成运动方案   07 生成餐单\n08 组装七日计划   09 完整性评估\n10 可选封面   11 结果页面   12 二维码" },
  ];
  addArrow(slide, 316, 370, 42, 26, C.rule);
  addArrow(slide, 682, 370, 42, 26, C.rule);
  phases.forEach((phase) => {
    addPanel(slide, phase.x, 210, phase.w, 350, { fill: C.panel });
    addLabel(slide, phase.label, phase.x + 26, 240, Math.min(180, phase.w - 52), phase.color);
    addText(slide, phase.steps, phase.x + 28, 320, phase.w - 56, 170, 21, { color: C.dark });
  });
  addText(slide, "独立步骤并行，依赖步骤串行；每一步都有状态、重试次数和可复用输出。", 56, 600, 1120, 38, 22, { bold: true, color: C.blue });
  addNotes(slide,
    "重点讲依赖关系：RAG、天气和营养彼此独立，因此并行；训练、餐单、组装和评估依赖前一步，因此严格串行。",
    ["D:/ykdSummerText/src/main/java/com/summercamp/project/agent/planning/TaskPlanner.java", "D:/ykdSummerText/src/main/java/com/summercamp/project/agent/HealthPlanAgent.java"]);
}

// 8. Performance and resilience.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "速度、成本和稳定性被纳入同一套设计", 8);
  const items = [
    { y: 190, word: "快", color: C.blue, title: "并行执行 + 短期缓存", body: "天气、RAG、营养并行；相同天气与检索结果按 TTL 复用。" },
    { y: 300, word: "省", color: C.green, title: "本地规则 + Prompt 压缩", body: "确定意图、身体资料和固定模板不重复消耗模型 Token。" },
    { y: 410, word: "稳", color: C.accent, title: "重试、备用模型与降级", body: "聊天与视觉模型自动回退；天气、TTS、封面失败不阻断主流程。" },
    { y: 520, word: "续", color: C.dark, title: "SQLite 断点与提醒持久化", body: "成功步骤保存；重启后可继续任务并恢复每日健康提醒。" },
  ];
  items.forEach((item) => {
    addPanel(slide, 60, item.y, 64, 64, { geometry: "ellipse", fill: item.color, radius: 0 });
    addText(slide, item.word, 60, item.y + 10, 64, 42, 25, { bold: true, color: C.white, align: "center" });
    addText(slide, item.title, 154, item.y, 390, 38, 25, { bold: true });
    addText(slide, item.body, 154, item.y + 43, 800, 42, 18, { color: C.muted });
    addRule(slide, 154, item.y + 93, 1010, C.rule, 1);
  });
  addPanel(slide, 980, 188, 230, 374, { fill: C.panelBlue });
  addText(slide, "结果", 1006, 220, 180, 38, 24, { bold: true, color: C.blue });
  addText(slide, "更少等待\n更少重复调用\n外部服务失败可降级\n程序重启仍可恢复", 1006, 286, 180, 190, 23, { bold: true, color: C.dark });
  addNotes(slide,
    "这页对应后期优化成果：不只追求功能跑通，还要控制响应时间、Token 消耗和故障影响范围。",
    ["D:/ykdSummerText/src/main/java/com/summercamp/project/agent/AgentPromptOptimizer.java", "D:/ykdSummerText/src/main/java/com/summercamp/project/agent/store/AgentStateDatabase.java", "D:/ykdSummerText/src/main/java/com/summercamp/project/weather/CachingWeatherClient.java"]);
}

// 9. Demo scenario.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "现场演示：一句话触发完整健康规划闭环", 9);
  addPanel(slide, 56, 180, 535, 390, { fill: C.panelBlue });
  addText(slide, "演示输入", 86, 210, 170, 34, 22, { bold: true, color: C.blue });
  addText(slide,
    "帮我制定未来 7 天完整增肌健康生活方案。性别男，20 岁，175 cm，70 kg，住在宜春市袁州区，每周训练 4 次，每次 60 分钟，每天 4 餐，健康成人、无食物过敏，最后生成结果页面和二维码。",
    86, 270, 470, 235, 24, { bold: true, color: C.dark });
  addArrow(slide, 606, 360, 58, 30, C.accent);
  addText(slide, "最终交付", 700, 196, 190, 42, 26, { bold: true });
  const outputs = [
    ["七日计划", "每日饮食、训练与恢复安排"],
    ["真实依据", "天气、健康 RAG、营养估算"],
    ["安全检查", "年龄范围、健康状况、过敏边界"],
    ["可访问成品", "结果页面 + 手机可扫二维码"],
  ];
  outputs.forEach((item, i) => {
    const y = 268 + i * 74;
    addText(slide, `0${i + 1}`, 700, y, 48, 34, 20, { bold: true, color: C.green });
    addText(slide, item[0], 760, y - 4, 190, 32, 21, { bold: true });
    addText(slide, item[1], 760, y + 31, 390, 30, 17, { color: C.muted });
  });
  addText(slide, "一句目标 → 自动执行 → 完整成品", 700, 585, 470, 38, 24, { bold: true, color: C.blue });
  addNotes(slide,
    "建议现场直接复制左侧话术。等待过程中可以发送“查看任务进度”，完成后扫描二维码展示结果页面。",
    ["D:/ykdSummerText/README.md", "D:/ykdSummerText/src/main/java/com/summercamp/project/agent/HealthPlanAgent.java"]);
}

// 10. Metrics — Codex Grid slide-19 silhouette.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "功能扩展建立在可重复验证的工程基础上", 10);
  addText(slide, "Java 21 + Spring Boot 4.1 的模块化单体，共编译 126 个主源码文件；GitHub Actions 使用 Java 21 执行完整验证。", 56, 150, 1120, 58, 21, { color: C.muted });
  const stats = [
    { x: 56, value: "154", label: "自动化测试", note: "0 失败 · 0 错误", color: C.blue },
    { x: 442, value: "10", label: "本地 Tool", note: "白名单 + JSON Schema", color: C.green },
    { x: 828, value: "5", label: "自定义 Skill", note: "规则优先，可独立注册", color: C.accent },
  ];
  stats.forEach((stat) => {
    addPanel(slide, stat.x, 270, 330, 280, { fill: C.panel });
    addText(slide, stat.value, stat.x + 30, 305, 270, 100, 62, { bold: true, color: stat.color });
    addText(slide, stat.label, stat.x + 30, 423, 270, 38, 25, { bold: true });
    addText(slide, stat.note, stat.x + 30, 477, 270, 34, 17, { color: C.muted });
  });
  addText(slide, "另有 12 步 Agent 工作流、4 种健康目标、最多 5 轮 Function Calling 与每轮最多 4 个工具调用。", 56, 595, 1120, 42, 20, { bold: true, color: C.dark });
  addNotes(slide,
    "用数字证明项目不是演示脚本：154 个测试覆盖路由、工具、Skill、天气、语音、结果页、并行调度和 SQLite 上下文启动。",
    ["Maven verify output, 2026-08-30", "D:/ykdSummerText/src/test/java", "D:/ykdSummerText/src/main/java/com/summercamp/project/llm/ZhipuAiClient.java"]);
}

// 11. Challenges and solutions.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addSlideTitle(slide, "最大的困难来自真实系统之间的边界", 11);
  const rows = [
    ["微信连接与登录状态", "二维码生命周期、网络重置、重复消息", "自动打开二维码、轮询重试、消息去重"],
    ["模型能力与平台波动", "文本/视觉/绘图接口不同，可能限流", "分模型配置、备用模型、统一异常提示"],
    ["语音与媒体格式", "SILK、PCM、WAV、MP3 与发送兼容", "优先微信转写，本地转换，TTS 失败回退文字"],
    ["多人分支整合", "重复工具、接口不一致、配置泄露风险", "统一注册表与接口，去重择优，本地配置 Git 忽略"],
  ];
  addPanel(slide, 56, 165, 1120, 54, { fill: C.ink, radius: 0 });
  addText(slide, "难点", 78, 176, 250, 30, 18, { bold: true, color: C.white });
  addText(slide, "具体表现", 350, 176, 385, 30, 18, { bold: true, color: C.white });
  addText(slide, "解决方式", 760, 176, 380, 30, 18, { bold: true, color: C.white });
  rows.forEach((row, i) => {
    const y = 220 + i * 94;
    if (i % 2 === 0) addPanel(slide, 56, y, 1120, 94, { fill: C.panelBlue, radius: 0 });
    addText(slide, row[0], 78, y + 23, 250, 50, 20, { bold: true });
    addText(slide, row[1], 350, y + 17, 385, 62, 18, { color: C.muted });
    addText(slide, row[2], 760, y + 17, 380, 62, 18, { color: C.dark });
    addRule(slide, 56, y + 93, 1120, C.rule, 1);
  });
  addNotes(slide,
    "这一页强调真实工程经验：最难的不是写一个算法，而是让微信、模型、天气、语音和团队代码在同一套边界内稳定工作。",
    ["D:/ykdSummerText/README.md", "D:/ykdSummerText/src/main/java/com/summercamp/project/wechat/ILinkWechatGateway.java", "D:/ykdSummerText/src/main/java/com/summercamp/project/speech/WechatAudioConverter.java"]);
}

// 12. Close.
{
  const slide = deck.slides.add();
  slide.background.fill = C.canvas;
  addText(slide, "最终结论", 56, 52, 180, 30, 16, { bold: true, color: C.blue });
  addText(slide, "一句目标，\nBot 可以交付完整结果", 56, 150, 850, 142, 54, { bold: true });
  addRule(slide, 56, 334, 280, C.blue, 6);
  addText(slide,
    "项目已经形成“可对话、可感知、可调用工具、可检索知识、可自主规划、可断点恢复”的完整系统。",
    56, 382, 940, 80, 27, { color: C.dark });
  addText(slide, "下一步：向量 RAG · 更丰富的个人 Skill · 持久化用户画像 · 可观测性与性能评测", 56, 520, 1080, 50, 20, { color: C.muted });
  addText(slide, "Q & A", 1030, 600, 180, 54, 34, { bold: true, color: C.green, align: "right" });
  addText(slide, "12", 1180, 666, 44, 22, 14, { color: C.muted, align: "right" });
  addNotes(slide,
    "收尾回到开场：项目的核心成果不是功能数量，而是把多种能力组织成了一个能自主完成复杂任务、并且可以稳定恢复的系统。",
    ["D:/ykdSummerText/README.md"]);
}

async function writeBlob(filePath, blob) {
  await fs.writeFile(filePath, new Uint8Array(await blob.arrayBuffer()));
}

await fs.mkdir(path.dirname(OUT), { recursive: true });
await fs.mkdir(RENDER, { recursive: true });

for (const [index, slide] of deck.slides.items.entries()) {
  const stem = `slide-${String(index + 1).padStart(2, "0")}`;
  const png = await deck.export({ slide, format: "png", scale: 1 });
  await writeBlob(`${RENDER}/${stem}.png`, png);
  const layout = await slide.export({ format: "layout" });
  await fs.writeFile(`${RENDER}/${stem}.layout.json`, await layout.text(), "utf8");
}

const montage = await deck.export({ format: "webp", montage: true, scale: 1 });
await writeBlob(`${RENDER}/deck-montage.webp`, montage);

const pptx = await PresentationFile.exportPptx(deck);
await pptx.save(OUT);
console.log(`Created ${OUT} with ${deck.slides.items.length} slides.`);
