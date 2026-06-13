# ExpHub 项目长期记忆

## Thymeleaf Layout 模式注意事项

- ExpHub 模板使用 `<html th:replace="~{layout/base}">` 的 layout 模式
- 所有 JavaScript 代码和样式**必须**放在 `th:fragment` 内部（如 `content` 或 `head` 片段），否则会被 `th:replace` 丢弃
- 后端通过 `addContextPath()` 传入的 `contextPath` 模板变量，需要在 JS 中用 `th:inline="javascript"` 和 `/*[[${contextPath}]]*/` 方式注入，不能直接引用为 JS 全局变量

## 调试排查经验

- Spring Boot + DataTables + MyBatis 项目，前端报 `Cannot read properties of undefined (reading 'length')` 时：查应用日志 → 检查 Mapper XML 的 Base_Column_List → ALTER TABLE 同步数据库 → 重启应用
- 应用页面打不开时先用 `lsof -i:8090` 和 `curl` 确认服务是否在运行
- 启动失败常见原因是端口被旧进程占用，需要先 `kill -9` 再重试
