package com.summercamp.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot 项目入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application implements CommandLineRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Override
    public void run(String... args) {
        LOGGER.debug("[演示] DEBUG：用于记录开发调试信息");
        LOGGER.info("[演示] INFO：Spring Boot 夏令营项目启动成功");
        LOGGER.warn("[演示] WARN：用于记录需要关注但不影响运行的问题");
        LOGGER.error("[演示] ERROR：用于记录导致功能失败的错误");
    }
}
