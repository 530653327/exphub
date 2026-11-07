package com.exphub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
@MapperScan("com.exphub.mapper")
// 2026-11-07：登录失败计数的 @Scheduled 过期清理需要它。
// 全项目仅 LoginAttemptService 一个 @Scheduled，启用后只多一个单线程调度器。
@org.springframework.scheduling.annotation.EnableScheduling
public class ExpHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpHubApplication.class, args);
    }

    @org.springframework.stereotype.Component
    public static class StartupListener implements ApplicationListener<ApplicationReadyEvent> {
        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            Environment env = event.getApplicationContext().getEnvironment();
            String port = env.getProperty("server.port", "8080");
            String contextPath = env.getProperty("server.servlet.context-path", "");

            String baseUrl = "http://localhost:" + port + contextPath;

            System.out.println("\n");
            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.println("║                    经验阁 ExpHub 启动成功                      ║");
            System.out.println("╠══════════════════════════════════════════════════════════════╣");
            System.out.println("║  后台管理:  " + padRight(baseUrl + "/login", 50) + "║");
            System.out.println("║  默认账号:  admin / admin123（首次登录后请立即修改）             ║");
            System.out.println("╠══════════════════════════════════════════════════════════════╣");
            System.out.println("║  仪表盘:    " + padRight(baseUrl + "/dashboard", 50) + "║");
            System.out.println("║  经验管理:  " + padRight(baseUrl + "/docs", 50) + "║");
            System.out.println("║  助手管理:  " + padRight(baseUrl + "/assistants", 50) + "║");
            System.out.println("║  调用日志:  " + padRight(baseUrl + "/logs", 50) + "║");
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            System.out.println("\n");
        }

        private String padRight(String s, int length) {
            return String.format("%-" + length + "s", s);
        }
    }
}
