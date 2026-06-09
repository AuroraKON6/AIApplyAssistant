# 🤖 AI 求职投递助手 (AIApplyAssistant)

> 基于 Skyvern 浏览器 Agent 的智能自动求职投递工具。AI 先帮你搜索真实岗位，确认后再自动填写申请表单。

![Version](https://img.shields.io/badge/version-v1.4.2--beta-blue)
![License](https://img.shields.io/badge/license-GETJOBS--NC--1.0-green)
![Platform](https://img.shields.io/badge/platform-Windows-lightgrey)

---

## 📋 项目简介

AIApplyAssistant 是一个基于 **Skyvern 浏览器 Agent** 的自动求职投递工具。它能够：

1. **智能搜索岗位**：通过 AI 联网搜索真实招聘信息
2. **可信度评估**：每个岗位显示可信度等级和来源证据
3. **自动填写表单**：AI 驱动的浏览器自动化，自动填写申请表单
4. **安全可控**：默认在提交前暂停，等待用户确认

## ✨ 核心功能

### 🔍 岗位发现 Agent
- 输入求职目标（如"上海 Python 后端实习"），AI 联网搜索真实岗位
- 通过 Bing / DuckDuckGo 搜索引擎获取真实岗位链接
- 返回带可信度和来源证据的候选列表
- URL 交叉验证，过滤编造链接

### 📝 自动投递
- 支持 Greenhouse、Lever、Ashby 等主流 ATS 平台
- 支持智联招聘、猎聘、前程无忧等国内招聘平台
- 自动填写申请表单、上传简历
- 默认关闭自动提交，AI 填写后暂停等用户确认

### 🔧 运行诊断
- 一键检查 AI 配置、API 连接
- 检测 Skyvern 服务、Playwright 引擎状态
- 验证简历文件、网页搜索功能

### 🛡️ 安全特性
- API Key 仅保存在本地 `.env` 文件中
- 简历文件仅保存在本地磁盘
- 运行数据保存在本地 SQLite 数据库
- 不上传任何数据到第三方服务器

## 🚀 快速开始

### 系统要求
- Windows 10/11
- Java Runtime Environment (JRE) 17+
- 稳定的网络连接

### 1. 下载与解压
从 [Releases](https://github.com/AuroraKON6/AIApplyAssistant/releases) 页面下载最新版本的 ZIP 文件，解压到任意目录。

### 2. 启动应用
```bash
# 方式一：双击启动脚本
start_ai_apply.bat

# 方式二：命令行启动
java -jar app.jar
```

启动后浏览器会自动打开管理页面（默认 http://localhost:8888/ai-apply）。

### 3. 配置 AI 模型
在页面顶部的 **AI 配置** 区域填写：

| 字段 | 说明 | 示例 |
|------|------|------|
| AI模型名称 | 要使用的模型 | `deepseek-chat`、`gpt-4o`、`mimo-v2.5-pro` |
| API 地址 | OpenAI 兼容的 API 地址 | `https://api.deepseek.com/v1` |
| API Key | 你的 API 密钥 | `sk-xxx` |

> ⚠️ API Key 只保存在本地 `runtime/skyvern/.env` 文件中，不会上传到任何服务器。

### 4. 运行诊断
点击页面上的 **开始检测** 按钮，检查以下组件：
- ✅ AI 模型配置
- ✅ AI 模型连接
- ✅ 浏览器执行服务 (Skyvern)
- ✅ Playwright 浏览器引擎
- ✅ 简历文件
- ✅ 网页搜索

### 5. 查找与投递岗位
1. 在 **求职目标** 文本框中描述你想找的工作
2. （可选）在 **公司名单** 中粘贴感兴趣的公司名称
3. 点击 **先查找岗位**，AI 会联网搜索真实岗位
4. 勾选想投递的岗位，点击 **投递选中岗位**

## 📊 平台支持情况

| 平台 | 通道类型 | 投递难度 | 说明 |
|------|----------|----------|------|
| Greenhouse | ATS | ✅ 可自动投递 | 多数海外科技公司使用 |
| Lever | ATS | ✅ 可自动投递 | 初创公司常用 |
| Ashby | ATS | ✅ 可自动投递 | 现代 ATS，表单结构清晰 |
| 公司官网招聘页 | 公司官网 | ⚠️ 可能需要登录 | 取决于具体网站的反爬策略 |
| 智联招聘 | 招聘平台 | ⚠️ 可能需要登录 | 国内主流平台，可能触发验证码 |
| 猎聘 | 招聘平台 | ⚠️ 可能需要登录 | 国内主流平台 |
| 前程无忧/51job | 招聘平台 | ⚠️ 可能需要登录 | 国内主流平台 |
| Boss直聘 | 招聘平台 | ❌ 高风控 | 反自动化检测严格，不支持自动投递 |

## ⚠️ 已知限制

- 部分网站可能检测到自动化操作并阻止访问
- 需要手机验证码或人脸验证的网站无法自动完成
- 网页搜索依赖外部搜索引擎，可能受网络环境影响
- 本工具不保证投递成功率
- Skyvern 任务可能因页面复杂、登录、验证码或风控而需要人工接管

## 🏗️ 技术架构

```
┌─────────────────────────────────────────────────────┐
│                    前端 (Next.js)                     │
│              http://localhost:8888/ai-apply           │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│                 后端 (Java Spring Boot)              │
│                    app.jar                           │
│  ┌─────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │ AI 模型  │  │ 岗位搜索  │  │  投递任务管理      │  │
│  │ 调用接口  │  │   引擎   │  │                    │  │
│  └─────────┘  └──────────┘  └────────────────────┘  │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│            Skyvern 浏览器 Agent (Python)              │
│         http://localhost:8001 (默认)                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│  │  Playwright  │  │  Chromium   │  │  浏览器自动化 │  │
│  │   引擎      │  │  浏览器     │  │  执行引擎    │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────┘
```

### 技术栈
- **前端**：Next.js + React
- **后端**：Java Spring Boot
- **浏览器 Agent**：Skyvern (Python + Playwright)
- **AI 模型**：支持 OpenAI 兼容接口（DeepSeek、GPT-4、Mimo 等）
- **搜索引擎**：Bing / DuckDuckGo
- **数据库**：SQLite (本地存储)

## 📁 项目结构

```
AIApplyAssistant/
├── app.jar                    # 主应用程序（Java Spring Boot）
├── start_ai_apply.bat         # Windows 启动脚本
├── start_ai_apply.ps1         # PowerShell 启动脚本
├── README.md                  # 项目说明文档
├── LICENSE                    # 许可证文件
├── PRIVACY.md                 # 隐私说明
├── RELEASE_NOTES.md           # 版本更新日志
├── src/                       # 前端资源文件
│   └── main/resources/dist/   # Next.js 构建产物
└── runtime/                   # 运行时环境
    └── skyvern/               # Skyvern 浏览器 Agent
        ├── .env.example       # 环境变量示例
        ├── docker-compose.yml # Docker 配置
        ├── pyproject.toml     # Python 项目配置
        └── ...
```

## 🔒 隐私与安全

- **API Key**：保存在本地 `runtime/skyvern/.env`，不会上传到任何服务器
- **简历文件**：保存在本地磁盘，仅在用户主动投递时上传到招聘网站
- **运行数据**：投递记录、Cookie 等保存在本地 SQLite 数据库
- **网络请求**：所有请求均在用户本地发起，不经过任何中间服务器

详细隐私说明请参阅 [PRIVACY.md](PRIVACY.md)。

## ⚖️ 许可证

- 本项目：[GETJOBS-NC-1.0](LICENSE)（非商业许可证）
- 内嵌 Skyvern：[GNU AGPL v3](runtime/skyvern/LICENSE)

## ⚠️ 免责声明

本工具仅供学习和个人使用。用户需自行确认所使用招聘网站的规则和条款。自动投递行为可能违反某些网站的使用条款，用户需自行承担风险。

## 📞 联系方式

如有问题或建议，欢迎通过 GitHub Issues 反馈。

---

**⭐ 如果这个项目对你有帮助，请给个 Star 支持一下！**
