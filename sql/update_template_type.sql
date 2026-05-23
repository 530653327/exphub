-- 将没有 template_type 的经验统一设置为 problem_solution
UPDATE docs SET template_type = 'problem_solution' WHERE template_type IS NULL OR template_type = '';
