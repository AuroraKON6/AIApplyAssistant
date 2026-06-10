# AI Apply Assistant

基于 Playwright 浏览器自动化的智能求职投递工具。

## 功能特性

- **AI 岗位搜索**：输入求职目标，AI 联网搜索真实岗位
- **一键跨平台投递**：支持 BOSS直聘、智联招聘、前程无忧、猎聘
- **反检测体系**：内置隐身脚本、uBlock 扩展、30+ Chromium 参数
- **AI 模板系统**：YAML 模板 + Mustache 渲染，结构化 Prompt
- **新手引导**：简历解析 + 求职者画像生成

## 技术栈

- **后端**: Spring Boot + MyBatis + SQLite
- **前端**: Next.js + Tailwind CSS
- **浏览器自动化**: Playwright (Java)
- **AI**: OpenAI 兼容 API（支持 DeepSeek、GPT-4o 等）

## 快速开始

1. 从 Release 页面下载最新版本 zip
2. 解压到任意目录
3. 双击 `start_ai_apply.bat` 启动
4. 浏览器自动打开管理页面（默认 http://localhost:8888/ai-apply）
5. 在 AI 配置中填写模型信息
6. 运行诊断检查环境
7. 开始使用

## 构建发布包

```powershell
powershell -ExecutionPolicy Bypass -File .\packaging\build_release.ps1 -Version 1.5.0
```

## 配置说明

用户在页面 `AI配置 -> 模型接入` 中配置：

- **模型名称**：如 `deepseek-chat`、`gpt-4o`、`mimo-v2.5-pro`
- **API 地址**：OpenAI 兼容的 API 地址
- **API Key**：你的 API 密钥
- **视觉支持**：模型是否支持图片输入

配置保存在本地，重启后自动加载。

## 许可证

- 本项目：GETJOBS-NC-1.0（非商业许可证）
