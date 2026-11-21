package com.exphub.m;

import com.exphub.entity.DispatchHeader;
import com.exphub.service.DispatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 移动端模板「能渲染」守卫（2026-11-08 加）。
 *
 * <h3>为什么必须存在这个测试</h3>
 * 2026-11-08 第一批移动页上线后，手机上打开是「一片深灰、什么都没有」，而 HTTP 状态是 <b>200</b>。
 * 根因：模板里写了 {@code ${#httpServletRequest.contextPath}}，本项目的 Thymeleaf 3.1 下该对象为 null，
 * 取属性直接抛 {@code TemplateProcessingException}。而 Thymeleaf 是边渲染边输出，
 * 异常发生时响应已提交，Spring 连错误页都渲染不了（日志原话：
 * {@code Cannot render error page ... as the response has already been committed}），
 * 浏览器收到的是半截 HTML —— 只剩 head 里的背景色。监控、状态码、curl 全部看不见。
 *
 * <p>所以这里不做「对源码文本断言」，而是<b>真的把模板渲染一遍</b>：
 * 模板只要有一个表达式炸，这条测试就红，而不是等人在手机上看着空白页去猜。
 *
 * <p>本类只依赖 thymeleaf + spring-mock，不启动 Spring 容器，也不连数据库 ——
 * 前面的 {@code @WebMvcTest} 方案因 MyBatis mapper 要 sqlSessionFactory 起不来，故改用纯引擎渲染。
 */
class MobileTemplateRenderTest {

    private static final String CTX_PATH = "/exphub";

    /** 移动页 + 各自需要的模型变量。新增移动页必须加进来，否则它永远没人渲染过。 */
    private static final List<String> TEMPLATES = List.of(
            "m/login", "m/dashboard", "m/pending", "m/users", "m/problems", "m/me",
            // 2026-11-17：底栏第三项「埋点」下线，换成派单三页
            "m/dispatch", "m/dispatch-new", "m/dispatch-detail"
    );

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext context() {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath(CTX_PATH);
        request.setRequestURI(CTX_PATH + "/m/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Thymeleaf 3.1 起 WebContext 只接受 IWebExchange（不再直接吃 Servlet 三件套）
        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext ctx = new WebContext(webApp.buildExchange(request, response), Locale.SIMPLIFIED_CHINESE);
        // 与 MobileController 往 Model 里放的字段保持一致
        ctx.setVariable("title", "看板");
        ctx.setVariable("me", "管理员");
        ctx.setVariable("username", "admin");
        ctx.setVariable("lastLogin", "2026-11-08 02:00");
        ctx.setVariable("activeTab", "dashboard");
        // _csrf 在测试里没有 Security 上下文，模板按 null 分支走（这正是要覆盖的分支之一）
        ctx.setVariable("_csrf", null);

        // ---- 派单三页的模型（2026-11-17）----
        // 全塞进来不会影响别的页面；少一个的话 Thymeleaf 取属性会直接炸，
        // 而炸在线上就是「200 + 半截灰页」，只是这次不是白屏而是断在卡片中间。
        DispatchHeader o = new DispatchHeader();
        o.setOrderNo("W-20261117-000001");
        o.setOrigin(DispatchHeader.ORIGIN_HUMAN);
        o.setCreator("越哥");
        o.setAssignee("白泽");
        o.setTitle("派单页渲染冒烟");
        o.setDescription("模板能不能渲染完整");
        o.setStatus(DispatchHeader.ST_REVIEWING);
        o.setPriority(3);
        o.setVersion("1.0.1");
        o.setCreatedAt(LocalDateTime.of(2026, 11, 17, 10, 0));
        o.setUpdatedAt(LocalDateTime.of(2026, 11, 17, 10, 0));
        o.setAuthModifyCode(true);
        o.setAuthCommit(true);
        o.setAuthPush(false);
        o.setAuthRelease(true);
        ctx.setVariable("order", o);
        ctx.setVariable("orders", List.of(o));
        ctx.setVariable("items", List.of());
        ctx.setVariable("labels", Map.of(DispatchHeader.ST_REVIEWING, "待审核"));
        ctx.setVariable("counts", Map.of(DispatchHeader.ST_REVIEWING, 1L));
        // 2026-11-19 派单翻页：Controller 一定给这五个，少一个页面就断在翻页条上
        ctx.setVariable("total", 21L);
        ctx.setVariable("page", 2);
        ctx.setVariable("totalPages", 3);
        ctx.setVariable("from", 11);
        ctx.setVariable("to", 20);
        // 里程碑筛选（W-20261121-000010）：Controller 必然给这两个，缺一个模板 ${milestones.isEmpty()} 就炸
        ctx.setVariable("milestone", "");
        ctx.setVariable("milestones", List.of("M1", "M2"));
        // 每页条数：翻页条（fragments/dispatch-pager）读这两个
        ctx.setVariable("size", 10);
        ctx.setVariable("pageSizes", DispatchService.PAGE_SIZE_CHOICES);
        ctx.setVariable("granted", List.of("修改代码", "commit"));
        ctx.setVariable("suggestedVersion", "1.0.1");
        ctx.setVariable("roles", DispatchService.ASSIGNABLE_ROLES);
        ctx.setVariable("projects", List.of());
        // 2026-11-19 建单页新增：人工单自动归属的派单共享 Key 尾号
        ctx.setVariable("ownerKeyTail", "…9999");
        ctx.setVariable("ballOwner", "越哥");
        return ctx;
    }

    @Test
    @DisplayName("五个移动页都能渲染完成，且 contextPath 被 @{/} 正确拼上")
    void allMobileTemplatesRender() {
        SpringTemplateEngine engine = engine();

        for (String t : TEMPLATES) {
            String html;
            try {
                html = engine.process(t, context());
            } catch (RuntimeException e) {
                throw new AssertionError("模板 " + t + " 渲染失败 —— 线上表现会是「200 + 半截白/灰页」，"
                        + "因为响应已提交时错误页也渲染不出来。原始异常：" + e.getMessage(), e);
            }

            assertTrue(html.contains("<!DOCTYPE html>"), t + " 输出不是完整 HTML：\n" + head(html));
            // @{/} 在 contextPath=/exphub 下应产出 /exphub/ 前缀
            assertTrue(html.contains(CTX_PATH), t + " 里没有出现 contextPath " + CTX_PATH
                    + "（@{/} 没生效？）");

            // 登录页是唯一不带底栏的页（还没登录，点底栏只会被弹回来）。
            // 2026-11-17 前这条断言对 login 是「假通过」：旧 layout 把一整段 <style> 内联进每个页，
            // 里面恰好有 .tabbar 这个选择器，字符串命中了但导航其实不存在。
            // 换成外链 CSS 后假通过消失、断言变红 —— 所以这里必须把 login 显式排除，
            // 而不是放宽成「包含 tabbar 就算过」。
            if (!"m/login".equals(t)) {
                assertTrue(html.contains("dock-label"), t + " 缺底部导航（layout 的 tabbar 片段没被 include）");
            }
        }
    }

    @Test
    @DisplayName("dashboard 渲染后 JS 与数据接口都在，不能只剩一个空壳")
    void dashboardHasWorkingScript() {
        String html = engine().process("m/dashboard", context());

        assertTrue(html.contains("id=\"refreshBtn\""), "刷新按钮不存在 —— 脚本会在 addEventListener 处抛错，"
                + "后面的 load() 根本不会跑（线上表现就是没有任何 /api 请求）");
        assertTrue(html.contains("/api/stats/overview"), "看板脚本里没有 overview 接口调用");
        assertTrue(html.contains("/api/stats/call-trend"), "看板脚本里没有趋势接口调用");
        assertTrue(html.contains("id=\"hotList\""), "热门列表容器不存在");
        // contextPath 拼接：模板里是 CTX + '/api/...'，CTX 不能带尾斜杠否则出现 //api
        assertFalse(html.contains("//api/"), "contextPath 拼接出现双斜杠：CTX 末尾的 / 没被去掉");
    }

    @Test
    @DisplayName("pending 页渲染后要带列表接口、CSRF 与二次确认脚本")
    void pendingHasWorkingScript() {
        String html = engine().process("m/pending", context());

        // 列表接口：没有它这一页就是个空壳
        assertTrue(html.contains("/api/docs/pending"), "待删队列没有调用 /api/docs/pending");
        // 详情接口：手机上删之前必须能看正文
        assertTrue(html.contains("/api/docs/' + id"), "看正文没走 /api/docs/{id}");
        // 删除仍然走 Session 登录的 PageController 端点，不是新开的 REST 删除口子
        assertTrue(html.contains("/docs/' + id + '/delete"), "删除没走 /docs/{id}/delete");
        // CSRF：删除是写操作，没有 token 会被 Spring Security 403 拦掉
        assertTrue(html.contains("name=\"_csrf\""), "页面缺 _csrf meta，删除会被 403 拦掉");
        assertTrue(html.contains("getElementById('sentinel')"), "分页哨兵不存在，长列表加载不了更多");
    }

    @Test
    @DisplayName("派单列表页必须带状态筛选与新建入口，不能只剩一个空壳")
    void dispatchListHasWorkingLinks() {
        String html = engine().process("m/dispatch", context());

        // 八个状态筛选一个都不能少：少了「已完成 / 已取消」就翻不到历史单（2026-11-17 越哥要求）
        for (String st : List.of("REVIEWING", "READY", "RUNNING", "CONFIRMING", "VERIFYING",
                "DONE", "CANCELLED")) {
            assertTrue(html.contains("status=" + st), "派单列表缺状态筛选：" + st);
        }
        assertTrue(html.contains("/m/dispatch/new"), "派单列表没有新建入口");
        // 列表项必须能进详情，否则手机上只能看不能审
        assertTrue(html.contains("/m/dispatch/W-20261117-000001"), "列表项没有链接到详情页");
        // 状态中文必须来自后端 labels，不是模板里硬编码的映射
        assertTrue(html.contains("待审核"), "状态中文没渲染出来（labels 没传或键名不一致）");

        // 关键词模糊搜索：输入框 + 后端读得到的参数名，两者缺一就搜不了
        assertTrue(html.contains("name=\"keyword\""), "派单列表没有搜索输入框（name=keyword）");
        assertTrue(html.contains("<form"), "搜索必须能在手机键盘上直接提交（缺 GET 表单）");

        // 里程碑下拉 + chip 带住 milestone（W-20261121-000010）
        assertTrue(html.contains("name=\"milestone\""), "派单列表没有里程碑下拉（name=milestone）");
        assertTrue(html.contains("全部里程碑"), "里程碑下拉缺「全部」选项");
        assertTrue(html.contains("value=\"M1\""), "里程碑下拉没渲染出库里的 M1");
        WebContext withMs = context();
        withMs.setVariable("milestone", "M1");
        assertTrue(engine().process("m/dispatch", withMs).contains("milestone=M1"),
                "切状态 chip 会丢掉 milestone");
        // 验收标准 8：库里没有任何里程碑时，手机下拉为空也要正常渲染（disabled 分支），不能 500
        WebContext emptyMs = context();
        emptyMs.setVariable("milestones", List.of());
        String emptyHtml = engine().process("m/dispatch", emptyMs);
        assertTrue(emptyHtml.contains("全部里程碑"), "无里程碑数据时手机下拉仍要正常渲染");
        assertTrue(emptyHtml.contains("disabled"), "无里程碑时手机下拉应置灰");
    }

    @Test
    @DisplayName("派单列表翻页：手机上有上一页/下一页，且不许丢 status 与 keyword")
    void dispatchPagerKeepsFilters() {
        WebContext c = context();
        c.setVariable("status", "DONE");
        c.setVariable("keyword", "七色");
        c.setVariable("milestone", "M1");

        String html = engine().process("m/dispatch", c);
        assertTrue(html.contains("上一页"), "手机派单列表没有上一页入口");
        assertTrue(html.contains("下一页"), "手机派单列表没有下一页入口");
        assertTrue(html.contains("page=1") && html.contains("page=3"),
                "翻页链接没指向相邻页 —— 点一下可能原地不动");
        assertTrue(html.contains("status=DONE"), "翻页链接丢了 status：一翻页筛选就被清空");
        assertTrue(html.contains("milestone=M1"), "翻页链接丢了 milestone：一翻页里程碑筛选就被清空");
        // 每页条数：链接要带 size，界面上要能看见选择器（数据不足一页时靠它验证翻页）
        assertTrue(html.contains("size=10"), "翻页链接丢了 size：一翻页每页条数被打回默认");
        assertTrue(html.contains("/m/dispatch?page=1") || html.contains("page=1&amp;size"),
                "每页条数选择器没渲染出来");
    }

    @Test
    @DisplayName("派单列表翻页：第 1 页的「上一页」、末页的「下一页」必须置灰")
    void firstAndLastPageButtonsAreDisabled() {
        WebContext first = context();
        first.setVariable("page", 1);
        // btn-disabled 是我们唯一依赖的禁用类；改成只调透明度会「看着灰、其实能点」
        assertTrue(engine().process("m/dispatch", first).contains("btn-disabled"),
                "第 1 页的翻页按钮没有置灰标记");

        WebContext last = context();
        last.setVariable("page", 3);
        assertTrue(engine().process("m/dispatch", last).contains("btn-disabled"),
                "最后一页的「下一页」没有置灰标记");
    }

    @Test
    @DisplayName("派单详情页的动作表单按状态出现，不该出现的绝不出现")
    void dispatchDetailHasActionForms() {
        // 动作走统一的 /m/dispatch/act，靠 action 参数分派
        assertTrue(engine().process("m/dispatch-detail", context()).contains("/m/dispatch/act"),
                "详情页没有动作表单提交地址");
        // 授权位保存是独立端点
        assertTrue(engine().process("m/dispatch-detail", context()).contains("/m/dispatch/auths"),
                "详情页缺授权位保存表单");

        // 11 个授权位在「待审核」时一个都不能少 —— 少一个就等于那项权限在小屏上根本给不了
        String reviewing = engine().process("m/dispatch-detail",
                withStatus(DispatchHeader.ST_REVIEWING, DispatchHeader.ORIGIN_HUMAN));
        for (String f : List.of("modifyCode", "commit", "push", "release", "dataWrite", "migration",
                "credential", "prodConfig", "securityGroup", "firewall", "spend")) {
            assertTrue(reviewing.contains("name=\"" + f + "\""), "待审核详情页缺授权位复选框：" + f);
        }

        // 每个状态下该有的动作 / 不该有的动作。写死成表，比逐个 if 更难糊过去。
        record Case(String status, String origin, List<String> must, List<String> mustNot) {}
        List<Case> cases = List.of(
                new Case(DispatchHeader.ST_REVIEWING, DispatchHeader.ORIGIN_AI,
                        List.of("approve", "reject-review", "cancel"),
                        // 待审核还没开始干，谈不上验收和回复
                        List.of("verify-pass", "verify-reject", "reply")),
                new Case(DispatchHeader.ST_CONFIRMING, DispatchHeader.ORIGIN_HUMAN,
                        List.of("reply", "cancel"),
                        // 人工单在待确认时还没到验收环节
                        List.of("approve", "verify-pass", "verify-reject")),
                // AI 单的「待确认」球在创建人手里，人在这页不该看到回复框 —— 回复走对话
                new Case(DispatchHeader.ST_CONFIRMING, DispatchHeader.ORIGIN_AI,
                        List.of("cancel"),
                        List.of("reply", "approve", "verify-pass")),
                new Case(DispatchHeader.ST_VERIFYING, DispatchHeader.ORIGIN_AI,
                        List.of("verify-pass", "verify-reject", "cancel"),
                        List.of("approve", "reject-review", "reply")),
                new Case(DispatchHeader.ST_READY, DispatchHeader.ORIGIN_AI,
                        List.of("cancel"),
                        List.of("approve", "verify-pass", "verify-reject", "reply")),
                // 终态：一个写操作都不该有，包括取消
                new Case(DispatchHeader.ST_DONE, DispatchHeader.ORIGIN_AI,
                        List.of(),
                        List.of("approve", "reject-review", "reply", "verify-pass", "verify-reject", "cancel")),
                new Case(DispatchHeader.ST_CANCELLED, DispatchHeader.ORIGIN_AI,
                        List.of(),
                        List.of("approve", "reject-review", "reply", "verify-pass", "verify-reject", "cancel")));

        for (Case c : cases) {
            String html = engine().process("m/dispatch-detail", withStatus(c.status(), c.origin()));
            String tag = c.status() + "/" + c.origin();
            for (String a : c.must()) {
                assertTrue(html.contains("value=\"" + a + "\""),
                        tag + " 状态下缺动作：" + a + " —— 界面上就没有这个按钮，人做不了这件事");
            }
            for (String a : c.mustNot()) {
                assertFalse(html.contains("value=\"" + a + "\""),
                        tag + " 状态下不该出现动作：" + a + " —— service 会拒，但按钮在就等于骗人点");
            }
        }
    }

    @Test
    @DisplayName("新建派单页要有 11 个授权位，缺一个就等于那项权限手机上给不了")
    void dispatchNewHasAllAuthCheckboxes() {
        String html = engine().process("m/dispatch-new", context());

        assertTrue(html.contains("/m/dispatch/create"), "新建页没有提交地址");
        for (String f : List.of("modifyCode", "commit", "push", "release", "dataWrite", "migration",
                "credential", "prodConfig", "securityGroup", "firewall", "spend")) {
            assertTrue(html.contains("name=\"" + f + "\""), "新建页缺授权位复选框：" + f);
        }
        // 指派对象必须来自后端角色列表，不能自己写死几个人名
        for (String r : DispatchService.ASSIGNABLE_ROLES) {
            assertTrue(html.contains(">" + r + "</option>"), "指派下拉缺角色：" + r);
        }
        assertTrue(html.contains("不填则按项目当前版本末尾 +1"),
                "版本号提示没渲染出来（不填时的规则）");
        assertTrue(html.contains("data-suggest-url"),
                "建单页没接按项目实时推算的入口");
        assertTrue(html.contains("…9999"), "建单页没显示归属 Key —— 人工单写没写进 Key，建单的人只能靠猜");
    }

    @Test
    @DisplayName("users 页两类用户各走各的接口，且助手不能走会返回明文 Key 的详情接口")
    void usersHasWorkingScript() {
        String html = engine().process("m/users", context());

        // 门户用户走新建的只读接口
        assertTrue(html.contains("/api/mobile/users"), "门户用户没走 /api/mobile/users");
        // 助手列表复用现成接口（它已经做了 apiKey 掩码）
        assertTrue(html.contains("/api/assistants?page=1"), "助手列表没走 /api/assistants");

        // 安全红线：/api/assistants/{id} 返回完整实体，含 apiKey 与 apiKeySecret 明文。
        // 移动端一旦调它，密钥就直接画在屏幕上了 —— 这条断言就是防这个。
        assertFalse(html.contains("/api/assistants/' +"),
                "助手详情不能调 /api/assistants/{id}：它返回明文 apiKey 与 apiKeySecret");
        assertFalse(html.contains("apiKeySecret"),
                "模板里不应该出现 apiKeySecret 这个词，出现就说明有人把密钥字段接进来了");

        assertTrue(html.contains("id=\"userList\""), "门户用户列表容器不存在");
        assertTrue(html.contains("id=\"assistantList\""), "助手列表容器不存在");
        assertTrue(html.contains("id=\"userSearch\""), "缺少用户搜索框");
    }

    @Test
    @DisplayName("底部导航必须五项齐全（少一项等于那个入口没了）")
    void tabbarHasAllFiveTabs() {
        String html = engine().process("m/dashboard", context());

        for (String href : List.of("/m/dashboard", "/m/pending", "/m/dispatch", "/m/users", "/m/me")) {
            assertTrue(html.contains(href), "底部导航缺入口：" + href);
        }
    }

    @Test
    @DisplayName("problems 页必须覆盖 5 类，且看板的「问题文档」卡要能点进来")
    void problemsCoverAllTypes() {
        String html = engine().process("m/problems", context());

        assertTrue(html.contains("/api/docs/problems"), "问题文档页没有调用 /api/docs/problems");
        // 5 类一个都不能少：少一类就等于那类问题又变成「看得见点不进去」
        for (String t : List.of("pending_delete", "broken", "superseded", "flagged", "stale")) {
            assertTrue(html.contains("'" + t + "'"), "问题文档页缺分类：" + t);
        }

        String dash = engine().process("m/dashboard", context());
        // 断言渲染后的 URL，不是 th:href 原文 —— 模板渲染过之后属性已经变成普通 href 了
        assertTrue(dash.contains("/m/problems"),
                "看板的「问题文档」卡没有链接到 /m/problems —— 那 105 就还是个死数字");
    }

    @Test
    @DisplayName("模板里禁止再出现 #httpServletRequest / #request / #session（本项目 Thymeleaf 3.1 下为 null）")
    void noServletObjectsInTemplates() throws IOException {
        Path dir = Path.of("src/main/resources/templates/m");
        assertTrue(Files.isDirectory(dir), "找不到移动端模板目录：" + dir.toAbsolutePath());

        try (var files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (String bad : List.of("#httpServletRequest", "#httpSession", "${#request.", "${#session.")) {
                    assertFalse(src.contains(bad), p.getFileName() + " 里出现 " + bad
                            + " —— 本项目 Thymeleaf 3.1 下该对象为 null，会在渲染中途抛异常，"
                            + "导致「200 状态码 + 半截页面」。取 contextPath 请用 @{/}。");
                }
            }
        }
    }

    /** 每次都给一份**全新**的上下文 —— context() 里的对象是可变的，共用会让上一个用例改到下一个。 */
    private static WebContext withStatus(String status, String origin) {
        WebContext c = context();
        DispatchHeader o = (DispatchHeader) c.getVariable("order");
        o.setStatus(status);
        o.setOrigin(origin);
        // 球在谁手里：人工单球在越哥，AI 单球在创建人
        c.setVariable("ballOwner", DispatchHeader.ORIGIN_HUMAN.equals(origin) ? "越哥" : "白泽");
        return c;
    }

    private static String head(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
