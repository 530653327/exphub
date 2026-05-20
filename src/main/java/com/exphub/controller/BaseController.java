package com.exphub.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;

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
}
