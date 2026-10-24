package com.exphub.controller;

import com.exphub.common.R;
import com.exphub.service.ToolStatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP 工具使用统计接口。
 *
 * 页面 /tool-stats 走服务端渲染；本接口供页面增强（切窗口不刷新）与外部核对数据。
 * 数据口径与 ToolStatsService.getDashboard 完全一致，只有一处实现。
 */
@RestController
@RequestMapping("/api/tool-stats")
public class ToolStatsController {

    @Autowired
    private ToolStatsService toolStatsService;

    /**
     * @param days 统计窗口天数，默认 30，范围 1-365
     */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam(defaultValue = "30") int days) {
        int window = (days < 1 || days > 365) ? 30 : days;
        return R.ok(toolStatsService.getDashboard(window));
    }
}
