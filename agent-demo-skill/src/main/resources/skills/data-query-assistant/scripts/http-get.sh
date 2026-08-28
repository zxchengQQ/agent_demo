#!/bin/bash
# 数据查询助手自带脚本：通过 HTTP GET 获取公开数据
# 参数经环境变量注入（SkillScriptExecutor 护栏传递，避免 shell 注入）
curl -s --max-time 8 "${SKILL_PARAM_URL}"
