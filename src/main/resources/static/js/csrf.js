/**
 * 全局 CSRF token 注入（2026-10-24 随 CSRF 保护启用）
 *
 * 为什么需要：后台页面有 20+ 处 fetch / jQuery ajax 写请求散落在 9 个模板里，
 * 逐个加 header 必漏。这里统一拦截，页面只需在 <head> 放两个 meta：
 *   <meta name="_csrf" th:content="${_csrf.token}">
 *   <meta name="_csrf_header" th:content="${_csrf.headerName}">
 *
 * 只对非幂等方法注入（GET/HEAD/OPTIONS 不注入），与 Spring Security 默认策略一致。
 */
(function () {
    'use strict';

    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (!tokenMeta || !headerMeta) {
        return; // 页面没放 meta（如公开分享页），不处理
    }
    var TOKEN = tokenMeta.getAttribute('content');
    var HEADER = headerMeta.getAttribute('content');
    if (!TOKEN || !HEADER) {
        return;
    }

    function isWriteMethod(m) {
        if (!m) return false;
        return !/^(GET|HEAD|OPTIONS|TRACE)$/i.test(m);
    }

    // 1) 拦截原生 fetch
    if (window.fetch) {
        var origFetch = window.fetch;
        window.fetch = function (input, init) {
            init = init || {};
            if (isWriteMethod(init.method)) {
                var headers = init.headers;
                if (headers instanceof Headers) {
                    headers.set(HEADER, TOKEN);
                } else if (Array.isArray(headers)) {
                    headers.push([HEADER, TOKEN]);
                } else {
                    init.headers = Object.assign({}, headers || {}, {});
                    init.headers[HEADER] = TOKEN;
                }
            }
            return origFetch.call(this, input, init);
        };
    }

    // 2) jQuery ajax（页面里还在用 $.ajax 的地方）
    // ⚠️ 各模板的 jQuery 在 body 末尾才加载，晚于本文件，所以要延迟挂载
    function hookJQuery() {
        if (!window.jQuery) return;
        window.jQuery(document).ajaxSend(function (event, xhr, settings) {
            if (isWriteMethod(settings.type)) {
                xhr.setRequestHeader(HEADER, TOKEN);
            }
        });
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', hookJQuery);
    } else {
        hookJQuery();
    }
})();
