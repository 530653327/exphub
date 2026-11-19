package com.exphub.notify;

import java.time.LocalDateTime;

/**
 * 「派单进入待验收」事件（2026-11-19）。
 *
 * <p>为什么用事件而不是在提交验收的代码里直接发信：
 * <ol>
 *   <li>提交验收是 {@code @Transactional}，事务里发 SMTP 一旦慢/失败，
 *       会把主业务拖住甚至回滚；</li>
 *   <li>事务回滚了就不该发信 —— 监听器用 {@code AFTER_COMMIT} 天然满足这一条。</li>
 * </ol>
 *
 * <p>{@code itemNo} 是每一轮的编号（{@code 单号-两位序号}），做邮件去重键正合适：
 * 同一轮重复触发只发一封，验收驳回后重新提交是新的一轮 → 应该再提醒一次。
 */
public record DispatchVerifyingEvent(String orderNo,
                                     String title,
                                     String assignee,
                                     String accepter,
                                     String conclusion,
                                     String content,
                                     String itemNo,
                                     LocalDateTime at) {
}
