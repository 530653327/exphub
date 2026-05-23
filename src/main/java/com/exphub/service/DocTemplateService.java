package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.exphub.entity.DocTemplate;
import com.exphub.mapper.DocTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocTemplateService extends ServiceImpl<DocTemplateMapper, DocTemplate> {

    /**
     * 获取默认模板
     */
    public DocTemplate getDefault() {
        DocTemplate template = getOne(new LambdaQueryWrapper<DocTemplate>()
                .eq(DocTemplate::getIsDefault, true));
        if (template == null) {
            // 如果没有默认模板，返回第一个
            template = getOne(null);
        }
        return template;
    }

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
     * 设置默认模板
     */
    @Transactional
    public void setDefault(Long id) {
        // 取消所有默认
        list().forEach(t -> {
            t.setIsDefault(false);
            updateById(t);
        });
        // 设置新的默认
        DocTemplate template = getById(id);
        if (template != null) {
            template.setIsDefault(true);
            updateById(template);
        }
    }

    /**
     * 创建模板（如果是第一个，自动设为默认）
     */
    public DocTemplate create(DocTemplate template) {
        if (this.count() == 0) {
            template.setIsDefault(true);
        } else {
            template.setIsDefault(false);
        }
        save(template);
        return template;
    }
}
