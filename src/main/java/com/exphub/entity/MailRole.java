package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可投递角色（信箱收件人白名单）。<b>DB 是唯一真源</b>，不再是 {@code application.yml}。
 *
 * <p>2026-11-09 阁主 T261109053740-701（P9 定案）：代号校验<b>只在 ExpHub 做</b>，
 * 忆阁不取、不存、不校验、也不做拼错纠正。
 *
 * <p>三条语义（改动前先读 {@link com.exphub.service.MailRoleCatalog}）：
 * <ol>
 *   <li>只有 {@code status='ACTIVE'} 才算可投递。{@code PENDING_REVIEW}（历史脏代号）与
 *       {@code DISABLED}（停用）都<b>不是</b>合法来源 —— 旧实现「配置 ∪ 库历史」的实时并集
 *       会让停用永远停不掉，那是 #1459 的另一面。</li>
 *   <li>停用/改名都<b>不删行、不改主键</b>：名单本身是审计证据（改名 = 新行 + 旧行 DISABLED + 别名）。</li>
 *   <li>改名与历史错拼走 {@link MailRoleAlias}，发信时归一、读信时并查（历史 to_role 只追加不改写）。</li>
 * </ol>
 */
@Data
@TableName("mail_roles")
public class MailRole {

    /**
     * 角色正名（<b>汉字</b>，如「烛龙」「重明鸟」），与 {@code mail_messages.to_role VARCHAR(32)} 对齐。
     * 主键，不是自增 ID。
     *
     * <p>2026-11-09 阁主 T261109054938-679：正名改成汉字，拼音（zhulong）降为别名。
     * 兼容期发信入参两种都收，入口统一归一到汉字后入库。
     */
    @TableId(value = "role", type = IdType.INPUT)
    private String role;

    /** 给人看的名字；正名已是汉字后与 {@link #role} 同值，保留列只为兼容后台旧数据。 */
    private String displayName;

    /** ACTIVE=可投递 / PENDING_REVIEW=历史脏代号等人工复核 / DISABLED=停用（不删行）。 */
    private String status;

    /** MANUAL=后台新增 / SEEDED=随产物种子 / OBSERVED=从历史信一次性导入。 */
    private String source;

    /** 负责的项目ID；NULL=不绑定。 */
    private Long projectId;

    private String note;

    private LocalDateTime createdAt;

    private String createdBy;

    /** 声明了 ON UPDATE，UPDATE 时必须显式写回 {@code updated_at = updated_at}（OnUpdateColumnGuardTest）。 */
    private LocalDateTime updatedAt;

    private String updatedBy;
}
