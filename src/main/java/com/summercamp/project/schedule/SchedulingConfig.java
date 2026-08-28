package com.summercamp.project.schedule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务基础设施：启用调度并统一线程池。
 * 时区由中国时区的 cron 注解（zone = "Asia/Shanghai"）承担，此处只负责线程池。
 * 主调度池处理本地快任务（自定义提醒/健康打卡/午餐菜单）；天气播报调用外部 API，
 * 独立使用单线程池，避免外部接口慢响应拖累其他定时任务。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean(name = "taskScheduler")
    @Primary
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("schedule-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    @Bean(name = "weatherScheduler")
    ThreadPoolTaskScheduler weatherScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("schedule-weather-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
