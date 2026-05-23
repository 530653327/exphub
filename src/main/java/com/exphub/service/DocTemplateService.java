package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.exphub.entity.DocTemplate;
import com.exphub.mapper.DocTemplateMapper;
import org.springframework.stereotype.Service;

@Service
public class DocTemplateService extends ServiceImpl<DocTemplateMapper, DocTemplate> {

    /**
     * 获取所有可用模板
     */
    public java.util.List<DocTemplate> listAll() {
        return list(new LambdaQueryWrapper<DocTemplate>().orderByAsc(DocTemplate::getId));
    }

    /**
     * 根据类型标识获取模板
     */
    public DocTemplate getByType(String type) {
        return getOne(new LambdaQueryWrapper<DocTemplate>()
                .eq(DocTemplate::getType, type));
    }

    /**
     * 创建模板
     */
    public DocTemplate create(DocTemplate template) {
        save(template);
        return template;
    }
}
