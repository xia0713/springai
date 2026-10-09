---
name: feedback-keep-useful-comments
description: 不要删除代码中的有用备注
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 54e5c16f-0d12-4757-8e09-8712245fa290
---

不要删除代码中的有用备注，包括 yaml 中参数含义、调参说明、平台配置说明等。只删除过时/错误的备注，不要"清理"掉有价值的注释。

**Why:** 用户在学习 Spring AI，代码中的备注（如温度参数建议、调参口诀、平台配置说明）是他的学习笔记，删除后丢失了知识积累。

**How to apply:** 编辑文件时，只改实际需要改的配置值/代码逻辑，保留行内注释和文档注释。不要为了"整洁"而删除备注。
