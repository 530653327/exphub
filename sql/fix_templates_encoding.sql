-- 修复模板数据编码：删除旧数据，重新插入确保 UTF-8 编码正确
DELETE FROM doc_templates;

INSERT INTO doc_templates (id, type, name, platform_field, description, instruction, template_content, is_default) VALUES 
(6, 'problem_solution', '🛠️ 问题解决方案', '操作系统', 
 '记录技术问题的排查过程和解决方案，方便下次遇到同类问题时快速参考。适用于：bug修复、配置问题、报错排查等。', 
 '请按以下格式记录问题解决过程，包括：环境信息、问题现象、排查过程、根因分析、解决方案和注意事项。', 
 '## 环境信息\n- 操作系统：\n- 相关版本：\n\n## 问题现象\n描述你遇到了什么问题，错误信息是什么？\n\n## 排查过程\n1. 第一步做了什么...\n2. 第二步发现了什么...\n\n## 根因分析\n问题的根本原因是什么？\n\n## 解决方案\n详细的解决步骤：\n\n```\n关键代码或配置\n```\n\n## ⚠️ 注意事项\n1. 容易踩的坑\n2. 关联依赖\n3. 后续影响', 
 false);

INSERT INTO doc_templates (id, type, name, platform_field, description, instruction, template_content, is_default) VALUES 
(7, 'knowledge_doc', '📚 知识文档', '操作系统', 
 '记录技术知识点、配置参考、最佳实践等，方便以后查阅。适用于：配置模板、技术总结、工具使用指南等。', 
 '请按以下格式记录知识文档，包括：概述、核心知识点、最佳实践和参考资料。', 
 '## 概述\n简要说明本文档涵盖的知识领域和适用场景。\n\n## 核心知识点\n### 知识点1\n- 概念说明\n- 关键配置/代码\n\n### 知识点2\n- 概念说明\n- 关键配置/代码\n\n## 最佳实践\n1. 推荐做法\n2. 避免的坑\n\n## 参考资料\n- [链接标题](URL)\n- 相关文档', 
 false);

INSERT INTO doc_templates (id, type, name, platform_field, description, instruction, template_content, is_default) VALUES 
(8, 'todo_list', '✅ 待办事项', '操作系统', 
 '记录需要跟踪处理的事项、后续任务、优化计划等。适用于：待处理的问题、计划中的改进、需要跟进的事项。', 
 '请按以下格式记录待办事项，包括：背景、待办清单（按优先级排列）和备注。', 
 '## 背景\n为什么要记录这些待办事项？\n\n## 待办清单\n- [ ] 【高优】待办标题\n  - 详细描述\n  - 相关链接/经验ID\n  \n- [ ] 【中优】待办标题\n  - 详细描述\n  \n- [ ] 【低优】待办标题\n  - 详细描述\n\n## 已完成\n- [x] 已完成的事项\n\n## 备注\n补充说明', 
 false);

-- 将第一个设为默认模板
UPDATE doc_templates SET is_default = true WHERE type = 'problem_solution' LIMIT 1;
