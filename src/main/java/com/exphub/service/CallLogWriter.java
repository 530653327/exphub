package com.exphub.service;

import com.exphub.entity.CallLog;
import com.exphub.mapper.CallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调用日志的**唯一写入通道**。
 *
 * 为什么单独抽出来（2026-10-25 P0）：
 * `call_logs.detail` 是 varchar(500)，而 logSearch 会把命中前 10 条文档标题拼进去
 * （标题都不超过 200 字，10 条 + 分隔符轻而易举超 500）。实测库里已有 480 字符的记录，
 * 再加一条标题就爆 → MySQL 抛 `Data too long for column 'detail'`。
 * 而 logSearch 被 @Transactional 包着、insert 也没 catch，异常直接冒泡，
 * **整个 search_experience 请求失败**——用户要的搜索结果没了，日志也没落库。
 *
 * 三层防御，缺一不可：
 * 1. **列宽**：detail 已从 varchar(500) 改成 TEXT（见 sql/ 下的 DDL 脚本），根上放宽；
 * 2. **截断**：每个字符串字段写入前按目标列宽截断。光改 TEXT 不够——下次换个字段、
 *    或标题本身异常长，同样的坑会从别的列冒出来。截断是唯一不依赖 DDL 的兜底；
 * 3. **不上抛**：本类所有异常一律吞掉只记日志。**日志可以丢，业务结果不能丢。**
 *
 * 为什么用 REQUIRES_NEW：日志写入与业务事务彻底解耦。
 * 若与业务同事务，即便这里 catch 住异常，MySQL 某些错误仍可能让外层事务处于
 * 不可提交状态，最终把业务一起拖死。独立事务保证「日志这一段失败 = 只回滚日志这一段」。
 * 代价是并发时多占一个连接（Hikari maximum-pool-size=10，日志事务极短，可接受）。
 */
@Component
public class CallLogWriter {

    private static final Logger log = LoggerFactory.getLogger(CallLogWriter.class);

    /** 与 call_logs 表结构保持一致；改列宽时同步改这里 */
    private static final int API_KEY_MAX = 64;
    private static final int CALLER_NAME_MAX = 100;
    private static final int ACTION_MAX = 20;
    private static final int TOOL_NAME_MAX = 50;
    private static final int KEYWORD_MAX = 500;
    private static final int DOC_TITLE_MAX = 200;

    /**
     * detail 已是 TEXT（上限约 2 万字符），但仍设业务上限 2000：
     * 单条日志超长既没人看，也会把慢查询和导出拖重。
     */
    private static final int DETAIL_MAX = 2000;

    @Autowired
    private CallLogMapper callLogMapper;

    /**
     * 写入一条日志。**永不抛异常。**
     *
     * @return 写入成功返回 true；被截断后写入或写入失败返回 false（调用方可忽略）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean write(CallLog entry) {
        if (entry == null) {
            return false;
        }
        sanitize(entry);
        try {
            callLogMapper.insert(entry);
            return true;
        } catch (Exception e) {
            // 只降级不上抛：日志丢了无所谓，业务必须正常返回。
            // 用 warn 而不是 error——这类失败是设计上允许发生的，不该触发告警风暴。
            log.warn("call_logs 写入失败（已忽略，不影响业务）: action={} tool={} err={}",
                    entry.getAction(), entry.getToolName(), e.getMessage());
            return false;
        }
    }

    /** 按列宽截断所有文本字段，防止任何一个字段超长把整条 insert 打挂 */
    private void sanitize(CallLog entry) {
        entry.setApiKey(cut(entry.getApiKey(), API_KEY_MAX));
        entry.setCallerName(cut(entry.getCallerName(), CALLER_NAME_MAX));
        entry.setAction(cut(entry.getAction(), ACTION_MAX));
        entry.setToolName(cut(entry.getToolName(), TOOL_NAME_MAX));
        entry.setKeyword(cut(entry.getKeyword(), KEYWORD_MAX));
        entry.setDocTitle(cut(entry.getDocTitle(), DOC_TITLE_MAX));
        entry.setDetail(cut(entry.getDetail(), DETAIL_MAX));
    }

    /**
     * 截断到 max 个字符。超长时留出省略标记的位置，并在末尾标注原长度，
     * 这样看到日志的人知道「这条是被截过的」，不会把残缺内容当成全部。
     */
    static String cut(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        String mark = "…(截断,原" + s.length() + "字)";
        int keep = Math.max(0, max - mark.length());
        return s.substring(0, keep) + mark;
    }
}
