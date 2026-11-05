package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 冒烟：`check_my_todos` 默认参数不得抛异常（#1290 C79-②）。
 *
 * <p><b>坦白这条测得浅</b>：没有请求上下文时 {@code getCaller()} 拿不到 API Key，
 * 方法会在真正查库之前就返回「无法识别调用者身份」。所以它验的只是
 * 「不抛异常、返回非空」，验不到 SQL 与渲染。
 *
 * <p>为什么仍然留着：这条链路今晚刚刚被改动（待办退出主排名、入口保持可用），
 * 而它<b>恰恰没有数据库级测试</b>。宁要一条浅的、会真的调用一次方法的测试，
 * 也不要再加二十条扫源码文本的守卫 —— 后者今晚已经证明了抓不到运行期错误。
 */
@ExtendWith(MockitoExtension.class)
class CheckTodosSmokeTest {

    @InjectMocks
    private ExpHubTools tools;

    @Test
    @DisplayName("smoke: check_my_todos 不传参数，不得抛异常")
    void checkTodosWithDefaultArgsMustNotThrow() {
        assertDoesNotThrow(() -> tools.checkMyTodos(null, null), "默认调用抛异常 = 待办入口坏了");
        assertNotNull(tools.checkMyTodos(null, null), "返回不得为 null");
    }

    @Test
    @DisplayName("smoke: 指定 projectId 与 status 同样不得抛异常")
    void checkTodosWithProjectAndStatusMustNotThrow() {
        assertDoesNotThrow(() -> tools.checkMyTodos(22L, "ALL"), "带项目与状态过滤的调用同样要跑得到");
    }
}
