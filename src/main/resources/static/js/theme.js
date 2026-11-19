/* ============================================================================
 * PC 后台主题：白天 / 夜间，按时间自动切换（2026-11-19）
 *
 * 规则（只有这一份运行时实现）：
 *   · 没手动选过 → 06:00–18:00 用白天（data-theme="light"），其余用夜间（"dark"）
 *   · 手动选过 → 固定，直到用户再点回「自动」
 *   · 每分钟检查一次 → 跨过 6:00 / 18:00 时自动换
 *   · 回到前台也检查一次 → 手机/笔记本休眠时定时器会停，只靠 setInterval 换不过去
 *
 * 首帧防闪：fragments/theme.html 里有一份同样规则的**内联**脚本，先于 CSS 设置
 * data-theme，否则夜间打开会先亮一下再变黑。改时段规则时两处一起改
 * （ThemeAutoSwitchTest 会比对两处的边界数字）。
 *
 * localStorage 键与移动端（m/layout.html 的 exphub-theme）分开：同一域名下两个
 * 前端用不同的主题名（PC 是 light/dark，移动是 explight/exp），共用一个键会互相覆盖。
 * ========================================================================== */
(function () {
    'use strict';

    var KEY = 'exphub-pc-theme';
    var root = document.documentElement;

    /** 用户手动选的值；没选或存了非法值都返回 null（= 自动） */
    function saved() {
        try {
            var m = localStorage.getItem(KEY);
            return (m === 'light' || m === 'dark') ? m : null;
        } catch (e) {
            return null; // 隐私模式禁用 localStorage：按自动处理，不影响页面
        }
    }

    /** 时段规则：6:00–17:59 白天。与内联脚本同一口径。 */
    function byHour() {
        var h = new Date().getHours();
        return (h >= 6 && h < 18) ? 'light' : 'dark';
    }

    function resolved() {
        return saved() || byHour();
    }

    function syncButton() {
        var label = document.querySelector('[data-theme-label]');
        if (!label) return;
        var m = saved();
        label.textContent = m === null ? '外观：自动'
                : (m === 'light' ? '外观：白天' : '外观：夜间');
        var btn = document.querySelector('[data-theme-cycle]');
        if (btn) {
            btn.title = '当前' + (m === null ? '跟随时间（6:00–18:00 白天）' : '已手动固定')
                    + '，点击切换：自动 → 白天 → 夜间';
        }
    }

    function apply(t) {
        root.setAttribute('data-theme', t);
        syncButton();
    }

    function resolve() {
        apply(resolved());
    }

    /** 设置模式：'auto' | 'light' | 'dark' */
    function set(mode) {
        try {
            if (mode === 'light' || mode === 'dark') localStorage.setItem(KEY, mode);
            else localStorage.removeItem(KEY);
        } catch (e) { /* 存不了就本次生效，不抛错 */ }
        resolve();
    }

    /** 侧栏按钮：自动 → 白天 → 夜间 → 自动 */
    function cycle() {
        var m = saved();
        set(m === null ? 'light' : (m === 'light' ? 'dark' : 'auto'));
    }

    resolve();

    // 跨时段：每分钟 + 回前台
    setInterval(resolve, 60 * 1000);
    document.addEventListener('visibilitychange', function () {
        if (!document.hidden) resolve();
    });

    // 侧栏按钮用事件委托，页面里不需要再绑定
    document.addEventListener('click', function (e) {
        var btn = e.target && e.target.closest ? e.target.closest('[data-theme-cycle]') : null;
        if (!btn) return;
        e.preventDefault();
        cycle();
    });

    /* 给页面脚本读当前主题用（图表要在创建时按主题取色） */
    window.ExpHubTheme = {
        current: function () { return root.getAttribute('data-theme') || 'light'; },
        set: set,
        resolve: resolve,
        /** 图表默认色：Chart.js 12px 灰色文字，深色下用同一套变量，避免网格线发白 */
        chartDefaults: function () {
            var cs = getComputedStyle(root);
            var text = cs.getPropertyValue('--text-mute').trim() || '#646b7d';
            var line = cs.getPropertyValue('--line').trim() || '#eceef4';
            return { color: text, grid: line };
        }
    };
})();
