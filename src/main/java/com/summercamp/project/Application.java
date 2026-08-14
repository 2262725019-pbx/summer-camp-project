package com.summercamp.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 项目入口，用于演示统一日志接口的使用方式。
 */
public final class Application {

    private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    private Application() {
    }

    public static void main(String[] args) {
        LOGGER.debug("[演示] DEBUG：用于记录开发调试信息");
        LOGGER.info("[演示] INFO：夏令营项目启动成功");
        LOGGER.warn("[演示] WARN：用于记录需要关注但不影响运行的问题");
        LOGGER.error("[演示] ERROR：用于记录导致功能失败的错误");
    }
}
