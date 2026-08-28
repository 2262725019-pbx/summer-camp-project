package com.summercamp.project.tool;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 根据用户消息选择注入模型的工具集合，用于减少普通聊天的 prompt 体积。
 * 命中任一工具触发词时注入全部工具（保证多步工具链路完整）；未命中时只注入极小常备工具集。
 * 判定全部为本地关键词规则，不额外消耗模型调用。
 */
@Component
public class ToolSelector {

    /** 常备工具：任何消息都会注入。get_current_datetime 与 calculate 的 schema 极小。 */
    public static final List<String> ALWAYS_TOOLS = List.of("get_current_datetime", "calculate");

    /** 命中任一触发词即注入全部工具，避免模型因缺少工具无法完成任务或链路断裂。 */
    private static final List<String> TRIGGER_KEYWORDS = List.of(
            // 天气
            "天气", "气温", "温度", "多少度", "下雨", "下雪", "带伞", "降雨", "降雪",
            "预报", "冷不冷", "热不热",
            // 计算
            "计算", "算一下", "算一算", "帮我算", "帮我计算", "算出", "等于多少",
            "加上", "减去", "乘以", "除以", "平方", "开方", "根号", "百分比",
            // 待办
            "待办", "记一下", "记录一下", "任务列表", "完成第",
            // 提醒
            "提醒", "叫我", "喊我",
            // 上下文
            "清除", "清空",
            // 二维码 / 结果页
            "二维码", "qr码", "qrcode", "结果页",
            // 图片
            "图片", "图像", "生成", "绘制", "创作", "画一张", "画个", "画一幅", "帮我画");

    /** 该消息是否需要注入全部工具。 */
    public boolean needsFullTools(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return TRIGGER_KEYWORDS.stream().anyMatch(normalized::contains);
    }
}
