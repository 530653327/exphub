package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CallLogMapper extends BaseMapper<CallLog> {

    @Select("SELECT DATE(created_at) as date, " +
            "COUNT(*) as total, " +
            "SUM(CASE WHEN action = 'SEARCH' THEN 1 ELSE 0 END) as searchCalls, " +
            "SUM(CASE WHEN action = 'CREATE' THEN 1 ELSE 0 END) as createCalls, " +
            "SUM(CASE WHEN action = 'UPDATE' THEN 1 ELSE 0 END) as updateCalls, " +
            "SUM(CASE WHEN action = 'DELETE' THEN 1 ELSE 0 END) as deleteCalls " +
            "FROM call_logs " +
            "WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "GROUP BY DATE(created_at) " +
            "ORDER BY date ASC")
    List<Map<String, Object>> getCallTrend(@Param("days") int days);
}