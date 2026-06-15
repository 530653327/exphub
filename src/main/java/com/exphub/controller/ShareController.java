package com.exphub.controller;

import com.exphub.common.R;
import com.exphub.entity.Doc;
import com.exphub.entity.DocShare;
import com.exphub.service.DocShareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 经验分享控制器
 * - /share/{token} 公开查看页面（无需登录）
 * - /api/share/{token} 公开API（无需登录）
 */
@Controller
public class ShareController {

    @Autowired
    private DocShareService docShareService;

    /**
     * 公开分享页面 - 无需登录
     */
    @GetMapping("/share/{token}")
    public String viewShare(@PathVariable String token, Model model, HttpServletRequest request) {
        Doc doc = docShareService.getSharedDoc(token);
        if (doc == null) {
            model.addAttribute("error", "分享链接无效或已过期");
            return "share";
        }
        DocShare share = docShareService.getByToken(token);
        model.addAttribute("doc", doc);
        model.addAttribute("share", share);
        model.addAttribute("contextPath", request.getContextPath());
        // 记录一次查看（不算调用，但可以加计数？暂不加）
        return "share";
    }

    /**
     * 公开API：根据token获取分享的经验文档JSON
     * ApiKeyInterceptor 已排除此路径
     */
    @GetMapping("/api/share/{token}")
    @ResponseBody
    public R<Doc> getSharedDoc(@PathVariable String token) {
        Doc doc = docShareService.getSharedDoc(token);
        if (doc == null) {
            return R.fail("分享链接无效或已过期");
        }
        return R.ok(doc);
    }
}
