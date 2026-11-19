package com.exphub.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 基础控制器 - 提供通用属性
 */
@Controller
public class BaseController {

    /**
     * 添加上下文路径到 Model
     * 解决 Spring Boot 3.x 中 #httpServletRequest 不可用的问题
     */
    protected void addContextPath(Model model, HttpServletRequest request) {
        model.addAttribute("contextPath", request.getContextPath());
    }

    /**
     * 生成压缩页码窗口（2026-11-07 经验列表引入，2026-11-20 上提到基类）。
     * 全库 1200+ 篇 / 20 每页 = 60+ 页，直接 {@code sequence(1, totalPages)} 会渲染 60 个页码按钮。改成：
     * 首 1 + 尾 1 + 当前页前后各 2，中间用省略号（占位 0）隔开。
     * 例（共 62 页，当前第 30 页）：[1] … [28,29,30,31,32] … [62]
     *
     * <p>放在基类只有一个原因：经验列表和项目列表要同一套页码窗口。
     * 复制一份到新控制器，将来改一处忘另一处就会两页表现不一致。</p>
     */
    protected static List<Integer> paginationWindow(long current, long totalPages) {
        List<Integer> pages = new ArrayList<>();
        if (totalPages <= 7) {          // 页数少直接全列
            for (long i = 1; i <= totalPages; i++) pages.add((int) i);
            return pages;
        }
        long c = Math.max(1, Math.min(current, totalPages));
        // 候选集：[1, c-2 .. c+2, totalPages]
        TreeSet<Long> set = new TreeSet<>();
        set.add(1L);
        set.add(totalPages);
        for (long i = c - 2; i <= c + 2; i++) {
            if (i >= 1 && i <= totalPages) set.add(i);
        }
        long prev = -1;
        for (long p : set) {
            if (prev >= 1 && p - prev > 1) {
                pages.add(0);            // 省略号占位
            }
            pages.add((int) p);
            prev = p;
        }
        return pages;
    }
}
