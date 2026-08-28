package com.summercamp.project.llm;

/**
 * 模型多次返回空文本（无 content 也无工具调用）时抛出。
 * 与 {@link ZhipuHttpException} 类似，可触发主模型 → 备用模型的降级链路。
 */
public class EmptyTextException extends LlmException {

    public EmptyTextException(String message) {
        super(message);
    }
}
