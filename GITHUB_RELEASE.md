# AI Apply Assistant Release Notes

This project can be published as a local desktop-style app package.

## Build a release zip

Run from the project root on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\packaging\build_release.ps1 -Version 0.1.0
```

The script creates:

```text
dist\AIApplyAssistant-0.1.0.zip
```

The package includes the backend jar, static frontend, embedded Skyvern runtime, and startup scripts.
It excludes local secrets, `.env`, databases, logs, browser cache, and temporary files.

## User model settings

Users configure their own model in `AI配置 -> 模型接入`.

Supported shape:

- Model name, for example `deepseek-chat`, `gpt-4o`, `mimo-v2.5-pro`
- OpenAI-compatible API URL, for example `https://api.deepseek.com/v1`
- API Key
- Whether the model supports vision/image input

Saving the model writes `runtime\skyvern\.env` locally. This file is ignored by Git and should not be committed.
Restart the app after changing model settings.

## Desktop shortcut

The user can run:

```text
start_ai_apply.bat
```

The script starts the backend, embedded Skyvern, and the frontend page. If a release jar exists, it starts `app.jar`; otherwise it falls back to the development startup path.
