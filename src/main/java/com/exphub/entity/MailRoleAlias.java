package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收件人别名 → 正名（只走一跳）。
 *
 * <p>真事故：#22 / #45 / #72 三封信的 {@code to_role} 写成了 {@code zhuolong}（多一个 o），
 * 收件箱按 {@code to_role} 精确匹配 → 信在、没人收、发件方全程无感。
 *
 * <p><b>只走一跳</b>（a=b、b=c 时 a 停在 b）：配错循环会把信吞进死胡同，而多跳归一
 * 让人猜不到最终落在哪。
 *
 * <p>别名表<b>不是</b>合法代号来源：发信时先归一再校验，指向的正名必须是
 * {@code mail_roles} 里 {@code status='ACTIVE'} 的行（迁移脚本末尾有自证 SELECT）。
 */
@Data
@TableName("mail_role_aliases")
public class MailRoleAlias {

    /** 别名 / 历史错拼（拼音，小写）。 */
    @TableId(value = "alias", type = IdType.INPUT)
    private String alias;

    /** 指向的正名（<b>汉字</b>，如「烛龙」）。 */
    private String role;

    /** 为什么有这条别名（事故号/日期）。 */
    private String note;

    private LocalDateTime createdAt;
}
