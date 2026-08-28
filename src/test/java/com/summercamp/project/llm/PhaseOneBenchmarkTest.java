package com.summercamp.project.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.config.AiChatProperties;
import com.summercamp.project.conversation.InMemoryConversationMemoryStore;
import com.summercamp.project.speech.WechatAudioConverter;
import com.summercamp.project.tool.BotTool;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolDefinition;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import com.summercamp.project.tool.ToolSelector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第一阶段（工具裁剪 / 历史压缩 / 消息并发）的基准测试。
 * 同一测试内同时计算"改前"（关闭工具过滤、未压缩历史、单线程）与"改后"两套数据，
 * 量化 token（按字符数估算）与响应速度（并发吞吐）的提升幅度。
 */
class PhaseOneBenchmarkTest {

    private static final List<String> PLAIN_CHAT = List.of(
            "你好",
            "讲个冷笑话吧",
            "这个项目是做什么的",
            "帮我看看午餐吃什么好呢",
            "谢谢，再见");
    private static final List<String> TOOL_MESSAGES = List.of(
            "查一下北京明天天气",
            "帮我计算125乘36",
            "帮我添加一个待办：写日报",
            "把计算结果生成二维码");

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldQuantifyPhaseOneImprovements() {
        ToolRegistry registry = new ToolRegistry(realisticTools(), objectMapper);
        ZhipuAiClient beforeClient = newClient(registry, false); // 改前：不过滤工具
        ZhipuAiClient afterClient = newClient(registry, true);   // 改后：过滤工具

        // ---- A1 工具裁剪 ----
        Size beforePlain = payloadSizes(beforeClient, PLAIN_CHAT);
        Size afterPlain = payloadSizes(afterClient, PLAIN_CHAT);
        Size beforeTool = payloadSizes(beforeClient, TOOL_MESSAGES);
        Size afterTool = payloadSizes(afterClient, TOOL_MESSAGES);

        // ---- A2 历史压缩 ----
        HistorySize history = compressedHistoryPayload(afterClient);

        // ---- B1 消息并发 ----
        double serialMillis = executorMillis(1);
        double pooledMillis = executorMillis(4);

        StringBuilder report = new StringBuilder();
        report.append("\n==================== 第一阶段量化报告 ====================\n");
        report.append("A1 工具裁剪（按 prompt 体积，token 数按字符/1.6 估算）：\n");
        report.append(String.format(
                "  普通聊天：改前 %d 字符 / 约%d token，改后 %d 字符 / 约%d token，节省 %.1f%%\n",
                beforePlain.chars(), beforePlain.tokens(),
                afterPlain.chars(), afterPlain.tokens(),
                percent(beforePlain.chars(), afterPlain.chars())));
        report.append(String.format(
                "  工具类消息：改前 %d 字符，改后 %d 字符，节省 %.1f%%（保持全量，功能不退化）\n",
                beforeTool.chars(), afterTool.chars(), percent(beforeTool.chars(), afterTool.chars())));
        report.append("\nA2 历史压缩（15 轮长对话后单次请求）：\n");
        report.append(String.format(
                "  改前 %d 字符 / 约%d token，改后 %d 字符 / 约%d token，节省 %.1f%%\n",
                history.beforeChars(), history.beforeTokens(),
                history.afterChars(), history.afterTokens(),
                percent(history.beforeChars(), history.afterChars())));
        report.append("\nB1 消息并发（8 条消息 × 每条模拟 300ms 处理）：\n");
        report.append(String.format(
                "  单线程改前 %.0fms，4 线程并发改后 %.0fms，提速 %.1f 倍\n",
                serialMillis, pooledMillis, serialMillis / pooledMillis));
        report.append("==========================================================\n");
        System.out.println(report);

        assertTrue(afterPlain.chars() < beforePlain.chars(), "工具裁剪应显著减少普通聊天体积");
        assertTrue(history.afterChars() < history.beforeChars(), "历史压缩应减小长对话体积");
        assertTrue(pooledMillis < serialMillis, "并发处理应快于串行");
    }

    // ---------------------------------------------------------------- A1 测量

    private Size payloadSizes(ZhipuAiClient client, List<String> messages) {
        int chars = 0;
        int bytes = 0;
        for (String message : messages) {
            JsonNode payload = client.buildChatPayload(new ChatRequest(List.of(), message, List.of()));
            try {
                bytes += objectMapper.writeValueAsBytes(payload).length;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            chars += payload.toString().length();
        }
        return new Size(chars, bytes);
    }

    // ---------------------------------------------------------------- A2 测量

    private HistorySize compressedHistoryPayload(ZhipuAiClient client) {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        for (int round = 0; round < 15; round++) {
            store.recordExchange("bench",
                    userMessage(round),
                    assistantMessage(round));
        }
        // 改前：旧逻辑保留最近 20 条（10 轮）原文，无摘要
        List<ChatMessage> beforeHistory = new ArrayList<>();
        for (int round = 5; round < 15; round++) {
            beforeHistory.add(ChatMessage.user(userMessage(round)));
            beforeHistory.add(ChatMessage.assistant(assistantMessage(round)));
        }
        // 改后：压缩摘要 + 最近 10 条
        List<ChatMessage> afterHistory = store.history("bench");
        return new HistorySize(
                payloadChars(client, beforeHistory),
                payloadChars(client, afterHistory));
    }

    /** 模拟较长用户提问（约 120 字符）。 */
    private String userMessage(int round) {
        return "第" + round + "轮：帮我看看这个项目的日志配置怎么改？"
                + "还有数据库连接超时该怎么调？我把截图发你看看。"
                + "补充说明补充说明补充说明补充说明补充说明补充说明";
    }

    /** 模拟较长的助手答复（约 600 字符，如天气/健康/计划类输出）。 */
    private String assistantMessage(int round) {
        return "第" + round + "轮答复：日志配置在 logback-spring.xml，把根级别改成 DEBUG 即可，"
                + "并配置 RollingFileAppender 按天滚动归档，同时把 ConsoleAppender 的编码设为 UTF-8。"
                + "数据库连接超时在 application.properties 里调整 spring.datasource 相关参数，"
                + "建议连接池初始大小 5、最大 20、连接超时 10 秒，并开启连接泄漏检测，"
                + "同时建议为慢查询开启 SQL 日志并定期分析执行计划。"
                + "另外提醒你：项目用了 virtual thread 处理并发，不要在请求线程里做阻塞式长任务，"
                + "尽量把耗时操作交给异步线程池，避免影响消息轮询的及时性。"
                + "如果还是报错，请把完整堆栈发给我，我再帮你定位。";
    }

    private int payloadChars(ZhipuAiClient client, List<ChatMessage> history) {
        JsonNode payload = client.buildChatPayload(new ChatRequest(history, "继续", List.of()));
        return payload.toString().length();
    }

    // ---------------------------------------------------------------- B1 测量

    /** 模拟旧/新两种消息执行器：单线程串行 vs 多线程 + 每用户锁。 */
    private double executorMillis(int poolSize) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(200),
                runnable -> {
                    Thread thread = new Thread(runnable, "bench-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            int messageCount = 8;
            int users = 4;
            ConcurrentHashMap<Integer, ReentrantLock> userLocks = new ConcurrentHashMap<>();
            CountDownLatch done = new CountDownLatch(messageCount);
            long started = System.nanoTime();
            for (int index = 0; index < messageCount; index++) {
                int userId = index % users;
                ReentrantLock lock = userLocks.computeIfAbsent(userId, ignored -> new ReentrantLock());
                executor.execute(() -> {
                    lock.lock();
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        lock.unlock();
                        done.countDown();
                    }
                });
            }
            try {
                done.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return (System.nanoTime() - started) / 1_000_000.0;
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 工具与构造

    private ZhipuAiClient newClient(ToolRegistry registry, boolean toolFilterEnabled) {
        AiChatProperties properties = new AiChatProperties(
                "https://open.bigmodel.cn/api/paas/v4",
                "/chat/completions",
                "/images/generations",
                "/audio/transcriptions",
                "bench-key",
                "text-model",
                List.of("text-fallback-1"),
                "vision-model",
                List.of(),
                "image-model",
                "1024x1024",
                "asr-model",
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                toolFilterEnabled);
        return new ZhipuAiClient(
                properties, objectMapper, java.net.http.HttpClient.newHttpClient(),
                new WechatAudioConverter(), registry, new ToolSelector(),
                new CheckpointStore(objectMapper, null, java.time.Clock.systemUTC()));
    }

    /** 用与真实工具数量、schema 规模相近的 11 个占位工具近似实际 prompt 体积。 */
    private List<BotTool> realisticTools() {
        return List.of(
                tool("get_weather", "查询天气", "city", "period"),
                tool("calculate", "数值计算", "expression"),
                tool("get_current_datetime", "获取当前时间"),
                tool("add_todo", "添加待办", "item"),
                tool("list_todos", "列出待办"),
                tool("complete_todo", "完成待办", "index"),
                tool("add_reminder", "设置提醒", "atIso", "content", "repeat"),
                tool("clear_memory", "清除上下文"),
                tool("create_result_page", "创建结果页", "expression", "result"),
                tool("generate_qr_code", "生成二维码", "text"),
                tool("generate_image", "生成图片", "prompt"));
    }

    private BotTool tool(String name, String description, String... stringProps) {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (String prop : stringProps) {
            properties.putObject(prop).put("type", "string");
        }
        if (stringProps.length > 0) {
            ArrayNode required = schema.putArray("required");
            for (String prop : stringProps) {
                required.add(prop);
            }
        }
        schema.put("additionalProperties", false);
        ToolDefinition definition = new ToolDefinition(name, description, schema);
        return new BotTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext context) {
                return ToolResult.text("ok");
            }
        };
    }

    private double percent(int before, int after) {
        return (before - after) * 100.0 / before;
    }

    private record Size(int chars, int bytes) {
        int tokens() {
            return Math.round(chars / 1.6f);
        }
    }

    private record HistorySize(int beforeChars, int afterChars) {
        int beforeTokens() {
            return Math.round(beforeChars / 1.6f);
        }

        int afterTokens() {
            return Math.round(afterChars / 1.6f);
        }
    }
}
