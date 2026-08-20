package com.summercamp.project.llm;

import java.util.List;
import java.util.Objects;

/** 一次模型处理的最终结果，可同时包含文字和多张工具生成的图片。 */
public record ChatOutcome(String text, List<Media> media) {

    public ChatOutcome {
        text = Objects.requireNonNullElse(text, "");
        media = media == null ? List.of() : List.copyOf(media);
    }

    public static ChatOutcome text(String text) {
        return new ChatOutcome(text, List.of());
    }

    public record Media(byte[] data, String fileName, String caption) {
        public Media {
            data = Objects.requireNonNull(data, "data").clone();
            fileName = Objects.requireNonNull(fileName, "fileName");
            caption = Objects.requireNonNullElse(caption, "");
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }
}
