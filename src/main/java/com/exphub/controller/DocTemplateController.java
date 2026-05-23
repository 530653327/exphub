package com.exphub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.DocTemplate;
import com.exphub.service.DocTemplateService;
import com.exphub.common.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
public class DocTemplateController {

    @Autowired
    private DocTemplateService templateService;

    /**
     * 获取所有模板（分页）
     */
    @GetMapping
    public R<Page<DocTemplate>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size) {
        Page<DocTemplate> p = new Page<>(page, size);
        return R.ok(templateService.page(p));
    }

    /**
     * 获取全部模板（不分页，供 MCP 工具调用）
     */
    @GetMapping("/all")
    public R<List<DocTemplate>> listAll() {
        return R.ok(templateService.list());
    }

    /**
     * 获取默认模板（AI创建经验时调用）
     */
    @GetMapping("/default")
    public R<DocTemplate> getDefault() {
        DocTemplate template = templateService.getDefault();
        if (template == null) {
            return R.fail(404, "未找到默认模板");
        }
        return R.ok(template);
    }

    /**
     * 获取单个模板
     */
    @GetMapping("/{id}")
    public R<DocTemplate> getById(@PathVariable Long id) {
        DocTemplate template = templateService.getById(id);
        if (template == null) {
            return R.fail(404, "模板不存在");
        }
        return R.ok(template);
    }

    /**
     * 创建模板
     */
    @PostMapping
    public R<DocTemplate> create(@RequestBody DocTemplate template) {
        if (template.getName() == null || template.getName().trim().isEmpty()) {
            return R.fail(400, "模板名称不能为空");
        }
        if (template.getType() == null || template.getType().trim().isEmpty()) {
            return R.fail(400, "类型标识不能为空");
        }
        if (template.getDescription() == null || template.getDescription().trim().isEmpty()) {
            return R.fail(400, "适用场景不能为空");
        }
        if (template.getInstruction() == null || template.getInstruction().trim().isEmpty()) {
            return R.fail(400, "填写指南不能为空");
        }
        if (template.getTemplateContent() == null || template.getTemplateContent().trim().isEmpty()) {
            return R.fail(400, "模板内容不能为空");
        }
        if (template.getPlatformField() == null) {
            template.setPlatformField("操作系统");
        }
        return R.ok(templateService.create(template));
    }

    /**
     * 更新模板
     */
    @PutMapping("/{id}")
    public R<DocTemplate> update(@PathVariable Long id, @RequestBody DocTemplate template) {
        DocTemplate existing = templateService.getById(id);
        if (existing == null) {
            return R.fail(404, "模板不存在");
        }
        if (template.getName() != null) {
            existing.setName(template.getName());
        }
        if (template.getType() != null) {
            existing.setType(template.getType());
        }
        if (template.getPlatformField() != null) {
            existing.setPlatformField(template.getPlatformField());
        }
        if (template.getDescription() != null) {
            existing.setDescription(template.getDescription());
        }
        if (template.getInstruction() != null) {
            existing.setInstruction(template.getInstruction());
        }
        if (template.getTemplateContent() != null) {
            existing.setTemplateContent(template.getTemplateContent());
        }
        templateService.updateById(existing);
        return R.ok(existing);
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        long count = templateService.count();
        if (count <= 1) {
            return R.fail(400, "至少保留一个模板");
        }
        boolean removed = templateService.removeById(id);
        if (!removed) {
            return R.fail(404, "模板不存在");
        }
        // 如果删除的是默认模板，将剩下的第一个设为默认
        DocTemplate template = templateService.getDefault();
        if (template == null) {
            DocTemplate first = templateService.getOne(null);
            if (first != null) {
                first.setIsDefault(true);
                templateService.updateById(first);
            }
        }
        return R.ok();
    }

    /**
     * 设为默认
     */
    @PostMapping("/{id}/set-default")
    public R<Void> setDefault(@PathVariable Long id) {
        DocTemplate template = templateService.getById(id);
        if (template == null) {
            return R.fail(404, "模板不存在");
        }
        templateService.setDefault(id);
        return R.ok();
    }
}
