'use client'

import { useEffect, useState } from 'react'
import { BiBrain, BiInfoCircle, BiSave } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import PageHeader from '@/app/components/PageHeader'

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8888'

type ProviderKey = 'openai-compatible' | 'deepseek' | 'openai' | 'mimo'

type LlmConfig = {
  provider: ProviderKey
  modelName: string
  baseUrl: string
  apiKey: string
  supportsVision: boolean
  maxTokens: number
  apiKeyConfigured: boolean
}

const providerPresets: Record<ProviderKey, { label: string; modelName: string; baseUrl: string; supportsVision: boolean }> = {
  'openai-compatible': {
    label: '自定义 OpenAI 兼容',
    modelName: '',
    baseUrl: '',
    supportsVision: false,
  },
  deepseek: {
    label: 'DeepSeek',
    modelName: 'deepseek-chat',
    baseUrl: 'https://api.deepseek.com/v1',
    supportsVision: false,
  },
  openai: {
    label: 'OpenAI',
    modelName: 'gpt-4o',
    baseUrl: 'https://api.openai.com/v1',
    supportsVision: true,
  },
  mimo: {
    label: 'MiMo',
    modelName: 'mimo-v2.5-pro',
    baseUrl: 'http://127.0.0.1:8002/v1',
    supportsVision: false,
  },
}

export default function AiConfigPage() {
  const [aiConfig, setAiConfig] = useState({
    introduce: '',
    prompt: '',
  })
  const [llmConfig, setLlmConfig] = useState<LlmConfig>({
    provider: 'openai-compatible',
    modelName: '',
    baseUrl: '',
    apiKey: '',
    supportsVision: false,
    maxTokens: 4096,
    apiKeyConfigured: false,
  })

  const [loadingPersona, setLoadingPersona] = useState(false)
  const [savingModel, setSavingModel] = useState(false)
  const [modelMessage, setModelMessage] = useState('')
  const [modelError, setModelError] = useState('')
  const [enableAi, setEnableAi] = useState<number>(0)

  useEffect(() => {
    void fetchAiConfig()
    void fetchEnableAi()
    void fetchLlmConfig()
  }, [])

  const fetchAiConfig = async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai/config`)
      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`)

      const result = await response.json()
      if (result.success && result.data) {
        setAiConfig({
          introduce: result.data.introduce || '',
          prompt: result.data.prompt || '',
        })
      }
    } catch (error) {
      console.error('加载AI配置失败:', error)
    }
  }

  const fetchLlmConfig = async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/skyvern/llm-config`)
      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`)

      const result = await response.json()
      if (result.success && result.data) {
        const provider = normalizeProvider(result.data.provider)
        setLlmConfig({
          provider,
          modelName: result.data.modelName || providerPresets[provider].modelName,
          baseUrl: result.data.baseUrl || providerPresets[provider].baseUrl,
          apiKey: '',
          supportsVision: Boolean(result.data.supportsVision),
          maxTokens: Number(result.data.maxTokens || 4096),
          apiKeyConfigured: Boolean(result.data.apiKeyConfigured),
        })
      }
    } catch (error) {
      console.error('加载模型配置失败:', error)
    }
  }

  const fetchEnableAi = async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/boss/config`)
      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`)

      const result = await response.json()
      const raw = result?.config?.enableAi
      const val = String(raw ?? '').trim().toLowerCase()
      setEnableAi(val === '1' || val === 'true' || val === 'on' ? 1 : Number(raw) === 1 ? 1 : 0)
    } catch (e) {
      console.error('加载enable_ai失败:', e)
    }
  }

  const normalizeProvider = (value: string): ProviderKey => {
    return value === 'deepseek' || value === 'openai' || value === 'mimo' ? value : 'openai-compatible'
  }

  const updateLlmConfig = <K extends keyof LlmConfig>(key: K, value: LlmConfig[K]) => {
    setLlmConfig((current) => ({ ...current, [key]: value }))
  }

  const handleProviderChange = (provider: ProviderKey) => {
    const preset = providerPresets[provider]
    setLlmConfig((current) => ({
      ...current,
      provider,
      modelName: preset.modelName || current.modelName,
      baseUrl: preset.baseUrl || current.baseUrl,
      supportsVision: preset.supportsVision,
      apiKey: '',
    }))
  }

  const toggleEnableAi = async () => {
    try {
      const next = enableAi ? 0 : 1
      setEnableAi(next)
      const response = await fetch(`${API_BASE_URL}/api/boss/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enableAi: next }),
      })
      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`)
    } catch (e) {
      console.error('更新enable_ai失败:', e)
      setEnableAi((prev) => (prev ? 0 : 1))
      alert('切换失败，请检查后端服务连接')
    }
  }

  const handleSaveModel = async () => {
    setSavingModel(true)
    setModelMessage('')
    setModelError('')
    try {
      const response = await fetch(`${API_BASE_URL}/api/skyvern/llm-config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          provider: llmConfig.provider,
          modelName: llmConfig.modelName,
          baseUrl: llmConfig.baseUrl,
          apiKey: llmConfig.apiKey,
          supportsVision: llmConfig.supportsVision,
          maxTokens: llmConfig.maxTokens,
        }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '保存失败')
      }

      setLlmConfig((current) => ({
        ...current,
        apiKey: '',
        apiKeyConfigured: true,
      }))
      setModelMessage('模型配置已保存。重启 AI投递助手 后，Skyvern 会使用新的模型。')
    } catch (error) {
      setModelError(error instanceof Error ? error.message : '保存失败')
    } finally {
      setSavingModel(false)
    }
  }

  const handleSavePersona = async () => {
    setLoadingPersona(true)
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai/config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(aiConfig),
      })

      const result = await response.json()
      if (result.success) {
        alert('AI配置已保存！')
      } else {
        alert('保存失败: ' + result.message)
      }
    } catch (error) {
      console.error('保存AI配置失败:', error)
      alert('保存失败，请检查服务器连接！')
    } finally {
      setLoadingPersona(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBrain className="text-2xl" />}
        title="AI配置"
        subtitle="配置模型接入和个性化求职文本"
        iconClass="text-white"
        accentBgClass="bg-purple-500"
        actions={
          <Button
            onClick={handleSavePersona}
            size="sm"
            className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105"
            type="button"
            disabled={loadingPersona}
          >
            <BiSave className="mr-1" /> 保存个性化
          </Button>
        }
      />

      <div className="space-y-6">
        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiBrain className="text-primary" />
              模型接入
            </CardTitle>
            <CardDescription>用户可以自己填模型名、接口地址和 API Key；支持 OpenAI 兼容接口</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="provider">模型供应商</Label>
                <select
                  id="provider"
                  value={llmConfig.provider}
                  onChange={(e) => handleProviderChange(normalizeProvider(e.target.value))}
                  className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                >
                  {Object.entries(providerPresets).map(([key, preset]) => (
                    <option key={key} value={key}>
                      {preset.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-2">
                <Label htmlFor="modelName">模型名</Label>
                <Input
                  id="modelName"
                  value={llmConfig.modelName}
                  onChange={(e) => updateLlmConfig('modelName', e.target.value)}
                  placeholder="例如：deepseek-chat / gpt-4o / mimo-v2.5-pro"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="baseUrl">API URL</Label>
                <Input
                  id="baseUrl"
                  value={llmConfig.baseUrl}
                  onChange={(e) => updateLlmConfig('baseUrl', e.target.value)}
                  placeholder="https://api.example.com/v1"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="apiKey">API Key</Label>
                <Input
                  id="apiKey"
                  type="password"
                  value={llmConfig.apiKey}
                  onChange={(e) => updateLlmConfig('apiKey', e.target.value)}
                  placeholder={llmConfig.apiKeyConfigured ? '已保存，留空则继续使用原 Key' : '请输入 API Key'}
                />
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <label className="flex items-center justify-between rounded-md border border-white/20 bg-white/20 dark:bg-white/5 px-4 py-3">
                <span className="text-sm font-medium">模型支持图片/视觉</span>
                <input
                  type="checkbox"
                  checked={llmConfig.supportsVision}
                  onChange={(e) => updateLlmConfig('supportsVision', e.target.checked)}
                  className="h-4 w-4"
                />
              </label>
              <div className="space-y-2">
                <Label htmlFor="maxTokens">输出 Token 上限</Label>
                <Input
                  id="maxTokens"
                  type="number"
                  min={1024}
                  max={128000}
                  value={llmConfig.maxTokens}
                  onChange={(e) => updateLlmConfig('maxTokens', Number(e.target.value))}
                />
              </div>
            </div>

            {modelMessage && <div className="rounded-md border border-emerald-500/20 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-700">{modelMessage}</div>}
            {modelError && <div className="rounded-md border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-700">{modelError}</div>}

            <div className="flex justify-end">
              <Button type="button" onClick={handleSaveModel} disabled={savingModel}>
                <BiSave className="mr-1" />
                保存模型接入
              </Button>
            </div>
          </CardContent>
        </Card>

        <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
          <CardHeader className="flex items-start gap-4">
            <div className="min-w-0 space-y-2">
              <CardTitle className="flex items-center gap-2">
                <BiBrain className="text-primary" />
                个性化求职文本
              </CardTitle>
              <CardDescription>配置技能介绍和提示词，用于生成个性化求职内容</CardDescription>
            </div>
            <div>
              <button
                type="button"
                aria-label="AI启用开关"
                onClick={toggleEnableAi}
                className={`relative inline-flex h-7 w-14 rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400/40 border border-white/30 shadow-[inset_0_1px_0_rgba(255,255,255,.25)] ${enableAi ? 'bg-emerald-500/80 hover:bg-emerald-500' : 'bg-white/10 hover:bg-white/15'}`}
              >
                <span
                  className={`absolute top-1 left-1 h-5 w-5 rounded-full bg-white shadow transition-transform ${enableAi ? 'translate-x-7' : 'translate-x-0'}`}
                />
              </button>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-6">
              <div className="space-y-2">
                <Label htmlFor="introduce">技能介绍</Label>
                <Textarea
                  id="introduce"
                  value={aiConfig.introduce}
                  onChange={(e) => setAiConfig({ ...aiConfig, introduce: e.target.value })}
                  placeholder="请输入您的技能介绍，例如：我熟练使用Java、Python等语言进行开发..."
                  className="min-h-[150px] resize-y"
                />
                <p className="text-xs text-muted-foreground">
                  详细描述您的技能、经验和专业背景，AI将使用这些信息生成个性化的求职文本
                </p>
              </div>

              <div className="space-y-2">
                <Label htmlFor="prompt">AI提示词</Label>
                <Textarea
                  id="prompt"
                  value={aiConfig.prompt}
                  onChange={(e) => setAiConfig({ ...aiConfig, prompt: e.target.value })}
                  placeholder="请输入AI提示词模板，例如：我目前在找工作，%s，我期望的岗位方向是【%s】..."
                  className="min-h-[150px] resize-y"
                />
                <p className="text-xs text-muted-foreground">
                  AI使用的提示词模板，支持使用 %s 作为占位符，用于动态插入内容
                </p>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="border-primary/20 bg-primary/5 animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardContent className="pt-6">
            <div className="flex gap-3">
              <BiInfoCircle className="h-5 w-5 text-primary flex-shrink-0 mt-0.5" />
              <div className="text-sm text-muted-foreground space-y-2">
                <p>
                  <strong className="font-semibold text-foreground">模型说明：</strong>
                  DeepSeek 这类不支持图片的模型也能用，但在复杂页面、验证码或纯图片按钮场景会更依赖 Skyvern 的页面结构解析。
                </p>
                <p>保存模型后会写入本地 Skyvern 环境文件；正在运行的 Skyvern 需要重启后才会切换到新模型。</p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
