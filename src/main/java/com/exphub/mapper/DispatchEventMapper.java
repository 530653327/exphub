package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.DispatchEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 派单操作流水表。
 *
 * <p>运行期只有 {@code insert} 一种写操作（BaseMapper 自带的 update/delete 一律不许在业务代码里调）。
 * 历史回填按 order_no + event_type + created_at 做幂等，靠 DDL 里的唯一索引兜底。
 */
@Mapper
public interface DispatchEventMapper extends BaseMapper<DispatchEvent> {
}
