package com.summercamp.project.llm;

import java.util.Locale;

/** 用文件签名识别图片格式，防止只信任远程 Content-Type。 */
final class ImageFormats {

    private ImageFormats() {
    }

    static String detectMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new LlmException("生成图片为空或内容不完整");
        }
        if (starts(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (starts(bytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a")) {
            return "image/gif";
        }
        if (bytes.length >= 12 && asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP")) {
            return "image/webp";
        }
        throw new LlmException("生成结果不是受支持的图片格式");
    }

    static String extension(String mediaType) {
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> throw new LlmException("不支持的图片类型：" + mediaType);
        };
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiAt(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != (byte) value.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
