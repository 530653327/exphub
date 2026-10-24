package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.Doc;
import com.exphub.mapper.DocMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查端点（2026-10-24 新增）。
 *
 * 为什么需要：deploy.sh 此前只负责重启，没有任何自检 —— 部署后应用挂了也只能靠人发现。
 * 本端点无需登录（SecurityConfig 已放行），供 deploy.sh 与外部监控调用。
 *
 * 返回码语义：DB 可用 200，DB 不可用 503（让 curl -f 能直接判定失败）。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @Autowired
    private DocMapper docMapper;

    @Value("${spring.ai.mcp.server.version:1.0.0}")
    private String appVersion;

    @Value("${exphub.mcp.sse-heartbeat-seconds:30}")
    private int heartbeatSeconds;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "exphub");
        body.put("version", appVersion);
        body.put("mcpHeartbeatSeconds", heartbeatSeconds);
        body.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        boolean dbUp = false;
        long latency = -1L;
        String dbError = null;
        try {
            long t0 = System.currentTimeMillis();
            // 用一次极轻量的 count 验证连接池与数据库可用（不读大字段）
            docMapper.selectCount(new QueryWrapper<Doc>().isNotNull("id"));
            latency = System.currentTimeMillis() - t0;
            dbUp = true;
        } catch (Exception e) {
            dbError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("health check: DB unavailable", e);
        }

        body.put("db", dbUp ? "UP" : "DOWN");
        body.put("dbLatencyMs", latency);
        if (dbError != null) {
            body.put("dbError", dbError);
        }
        body.put("status", dbUp ? "UP" : "DOWN");

        return dbUp
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
