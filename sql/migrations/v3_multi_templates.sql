-- ExpHub v3 升级：支持多模板，AI 助手可按场景选择
-- 1. doc_templates 表增加 type 和 description 列
-- 2. docs 表增加 template_type 列
-- 3. 预置 3 种模板

USE exphub;

-- 1. doc_templates 加列
ALTER TABLE doc_templates ADD COLUMN type VARCHAR(32) COMMENT '模板类型标识' AFTER id;
ALTER TABLE doc_templates ADD COLUMN description VARCHAR(500) COMMENT '适用场景说明' AFTER name;

-- 2. docs 加列
ALTER TABLE docs ADD COLUMN template_type VARCHAR(32) COMMENT '使用的模板类型' AFTER api_key;

-- 3. 删除旧模板数据（如果存在），插入新的 3 种模板
DELETE FROM doc_templates;

INSERT INTO doc_templates (type, name, description, instruction, template_content, is_default, created_at, updated_at) VALUES
(
    'problem_solution',
    '🛠️ 问题解决方案',
    '记录技术问题的排查过程和解决方案，方便下次遇到同类问题时快速参考。适用于：bug修复、配置问题、报错排查等。',
    '请详细记录问题现象、排查步骤和最终解决方案。关键代码用```包裹，注意事项用⚠️标记。',
    '## 环境信息\n- 操作系统：\n- 相关版本：\n\n## 问题现象\n描述你遇到了什么问题，错误信息是什么？\n\n## 排查过程\n1. 第一步做了什么...\n2. 第二步发现了什么...\n\n## 根因分析\n问题的根本原因是什么？\n\n## 解决方案\n详细的解决步骤：\n\n```\n关键代码或配置\n```\n\n## ⚠️ 注意事项\n1. 容易踩的坑\n2. 关联依赖\n3. 后续影响',
    1, NOW(), NOW()
),
(
    'knowledge_doc',
    '📚 知识文档',
    '记录技术知识点、配置参考、最佳实践等，方便以后查阅。适用于：配置模板、技术总结、工具使用指南等。',
    '以清晰的结构整理知识，便于日后检索和参考。用表格、列表等结构化形式组织信息。',
    '## 概述\n简要说明本文档涵盖的知识领域和适用场景。\n\n## 核心知识点\n### 知识点1\n- 概念说明\n- 关键配置/代码\n\n### 知识点2\n- 概念说明\n- 关键配置/代码\n\n## 最佳实践\n1. 推荐做法\n2. 避免的坑\n\n## 参考资料\n- [链接标题](URL)\n- 相关文档',
    0, NOW(), NOW()
),
(
    'todo_list',
    '✅ 待办事项',
    '记录需要跟踪处理的事项、后续任务、优化计划等。适用于：待处理的问题、计划中的改进、需要跟进的事项。',
    '用清单方式记录待办事项，标注优先级和状态，便于跟踪进展。',
    '## 背景\n为什么要记录这些待办事项？\n\n## 待办清单\n- [ ] 【高优】待办标题\n  - 详细描述\n  - 相关链接/经验ID\n  \n- [ ] 【中优】待办标题\n  - 详细描述\n  \n- [ ] 【低优】待办标题\n  - 详细描述\n\n## 已完成\n- [x] 已完成的事项\n\n## 备注\n补充说明',
    0, NOW(), NOW()
);
