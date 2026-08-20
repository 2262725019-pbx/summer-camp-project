package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** 工具结果既可以回填模型，也可以直接结束对话或携带图片。 */
public sealed interface ToolResult
        permits ToolResult.Text, ToolResult.Data, ToolResult.Completed, ToolResult.Image {

    record Text(String content) implements ToolResult {
        public Text {
            content = Objects.requireNonNullElse(content, "");
        }
    }

    record Data(JsonNode content) implements ToolResult {
        public Data {
            content = Objects.requireNonNull(content, "content").deepCopy();
        }

        @Override
        public JsonNode content() {
            return content.deepCopy();
        }
    }

    record Completed(String reply) implements ToolResult {
        public Completed {
            reply = Objects.requireNonNullElse(reply, "");
        }
    }

    record Image(byte[] data, String fileName, String caption) implements ToolResult {
        public Image {
            data = Objects.requireNonNull(data, "data").clone();
            fileName = Objects.requireNonNull(fileName, "fileName");
            caption = Objects.requireNonNullElse(caption, "");
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    static ToolResult text(String content) {
        return new Text(content);
    }

    static ToolResult data(JsonNode content) {
        return new Data(content);
    }

    static ToolResult completed(String reply) {
        return new Completed(reply);
    }

    static ToolResult image(byte[] data, String fileName, String caption) {
        return new Image(data, fileName, caption);
    }
}
