package com.summercamp.project.llm;

import com.summercamp.project.tool.ToolContext;
import java.util.Optional;

public interface ChatModelClient {

    ChatOutcome chat(ChatRequest request, ToolContext context);

    /** 从该用户上次失败的模型工具链断点继续执行；没有断点或续跑失败返回空。 */
    default Optional<ChatOutcome> resume(ToolContext context) {
        return Optional.empty();
    }
}
