'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import {
  Activity,
  Building2,
  Check,
  CheckCircle2,
  CircleStop,
  Clock,
  ExternalLink,
  FileSpreadsheet,
  FileText,
  Info,
  Link as LinkIcon,
  Loader2,
  RefreshCw,
  Save,
  Search,
  Send,
  SquareCheck,
  Stethoscope,
  AlertTriangle,
  UserRound,
  XCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import PageHeader from '@/app/components/PageHeader'

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8888'

type DiscoveredJob = {
  id: string
  company: string
  title: string
  location: string
  url: string
  matchReason: string
  source: string
  checkedAt: string
  confidence: string
  evidenceText: string
  channelType: 'ats' | 'company_site' | 'job_board' | 'manual_required'
  deliveryDifficulty: 'auto' | 'login_required' | 'high_risk' | 'manual'
  verificationStatus?: 'page_verified' | 'browser_verified' | 'unverified'
  verificationNote?: string
  targetCompany?: string
  officialWebsite?: string
  careersPage?: string
  companySearchStatus?: string
}

type JobApplyStatus = 'pending' | 'applying' | 'running' | 'submitted' | 'waiting_confirm' | 'failed'

type JobApplyRecord = {
  job: DiscoveredJob
  status: JobApplyStatus
  runId?: string
  error?: string
  skyvernStatus?: string
  appUrl?: string
  recordingUrl?: string
  screenshotUrls?: string[]
  runStatusUrl?: string
  browserDebugUrl?: string
  browserHint?: string
}

type ApplicationHistoryRecord = {
  id?: string
  company?: string
  title?: string
  url?: string
  source?: string
  status?: string
  runId?: string
  skyvernStatus?: string
  message?: string
  error?: string
  appUrl?: string
  runStatusUrl?: string
  browserDebugUrl?: string
  autoSubmit?: boolean
  targetCompany?: string
  officialWebsite?: string
  careersPage?: string
  companySearchStatus?: string
  createdAt?: string
  updatedAt?: string
}

type SkyvernRun = {
  run_id?: string
  status?: string
  output?: unknown
  failure_reason?: string | null
  app_url?: string | null
  recording_url?: string | null
  screenshot_urls?: string[] | null
  runStatusUrl?: string | null
  browserDebugUrl?: string | null
  browserHint?: string | null
  resumeUploaded?: boolean
  resumeFileUrl?: string
  effectivePrompt?: string
  skyvernBaseUrl?: string
}

type FormState = {
  goal: string
  companyName: string
  companyNames: string[]
  targetUrl: string
  resumePath: string
  skyvernBaseUrl: string
  skyvernApiKey: string
  engine: string
  modelName: string
  aiBaseUrl: string
  maxApplications: number
  maxSteps: number
  uploadResume: boolean
  autoSubmit: boolean
  browserSessionId: string
  browserAddress: string
  extraNotes: string
}

type ApplicantProfile = {
  fullName: string
  email: string
  phone: string
  currentCity: string
  school: string
  major: string
  degree: string
  graduationDate: string
  wechat: string
  portfolio: string
  expectedRole: string
  skills: string
  availability: string
  internshipDuration: string
  weeklyAvailability: string
  expectedSalary: string
  preferredLocations: string
  workPreference: string
  selfIntroduction: string
  coverLetter: string
}

type DiagItem = {
  name: string
  status: 'ok' | 'warning' | 'error'
  message: string
}

type ReadinessResult = {
  ready: boolean
  warningCount: number
  message: string
  items: DiagItem[]
}

type DiscoveryTask = {
  id: string
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled'
  message: string
  error?: string
  jobs?: DiscoveredJob[]
  jobCount?: number
  createdAt?: string
  updatedAt?: string
  skyvernRunId?: string
  skyvernStatus?: string
  skyvernAppUrl?: string
  runStatusUrl?: string
}

const terminalStatuses = new Set(['completed', 'failed', 'terminated', 'timed_out', 'canceled', 'cancelled'])

const emptyApplicantProfile: ApplicantProfile = {
  fullName: '',
  email: '',
  phone: '',
  currentCity: '',
  school: '',
  major: '',
  degree: '',
  graduationDate: '',
  wechat: '',
  portfolio: '',
  expectedRole: '',
  skills: '',
  availability: '',
  internshipDuration: '',
  weeklyAvailability: '',
  expectedSalary: '',
  preferredLocations: '',
  workPreference: '',
  selfIntroduction: '',
  coverLetter: '',
}

export default function AiApplyPage() {
  const [form, setForm] = useState<FormState>({
    goal: '我想找 AI 产品经理 / AIGC 运营 / 数据分析相关实习，优先远程或上海，能上传我的附件简历。',
    companyName: '',
    companyNames: [],
    targetUrl: '',
    resumePath: '',
    skyvernBaseUrl: 'http://127.0.0.1:8001',
    skyvernApiKey: '',
    engine: 'skyvern-2.0',
    modelName: '',
    aiBaseUrl: '',
    maxApplications: 0,
    maxSteps: 0,
    uploadResume: true,
    autoSubmit: false,
    browserSessionId: '',
    browserAddress: '',
    extraNotes: '',
  })
  const [run, setRun] = useState<SkyvernRun | null>(null)
  const [loadingDefaults, setLoadingDefaults] = useState(true)
  const [starting, setStarting] = useState(false)
  const [polling, setPolling] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [companyFileName, setCompanyFileName] = useState('')
  const [companyParseMessage, setCompanyParseMessage] = useState('')
  const [aiApiKey, setAiApiKey] = useState('')
  const [aiApiKeyConfigured, setAiApiKeyConfigured] = useState(false)
  const [resumeUploadMessage, setResumeUploadMessage] = useState('')
  const [resumeUploading, setResumeUploading] = useState(false)
  const [applicantProfile, setApplicantProfile] = useState<ApplicantProfile>(emptyApplicantProfile)
  const [profileMessage, setProfileMessage] = useState('')
  const [profileSaving, setProfileSaving] = useState(false)
  const [profileExtracting, setProfileExtracting] = useState(false)
  const [profileDirty, setProfileDirty] = useState(false)
  const companyFileInputRef = useRef<HTMLInputElement | null>(null)
  const resumeFileInputRef = useRef<HTMLInputElement | null>(null)
  const pollTimer = useRef<NodeJS.Timeout | null>(null)
  const discoveryPollTimer = useRef<NodeJS.Timeout | null>(null)

  const [discovering, setDiscovering] = useState(false)
  const [cancellingDiscovery, setCancellingDiscovery] = useState(false)
  const [discoveryTask, setDiscoveryTask] = useState<DiscoveryTask | null>(null)
  const [discoveredJobs, setDiscoveredJobs] = useState<DiscoveredJob[]>([])
  const [selectedJobIds, setSelectedJobIds] = useState<Set<string>>(new Set())
  const [jobRecords, setJobRecords] = useState<JobApplyRecord[]>([])
  const [applying, setApplying] = useState(false)
  const [saving, setSaving] = useState(false)
  const [diagResults, setDiagResults] = useState<DiagItem[]>([])
  const [diagRunning, setDiagRunning] = useState(false)
  const [readiness, setReadiness] = useState<ReadinessResult | null>(null)
  const [readinessLoading, setReadinessLoading] = useState(false)
  const [applicationHistory, setApplicationHistory] = useState<ApplicationHistoryRecord[]>([])
  const [historyMessage, setHistoryMessage] = useState('')

  function normalizeHistoryUrl(value: string) {
    let normalized = String(value || '').trim().toLowerCase()
    while (normalized.endsWith('/') || normalized.endsWith('#')) {
      normalized = normalized.slice(0, -1)
    }
    return normalized
  }

  const isTerminal = useMemo(() => terminalStatuses.has(String(run?.status || '').toLowerCase()), [run?.status])
  const historyByUrl = useMemo(() => {
    const map = new Map<string, ApplicationHistoryRecord>()
    applicationHistory.forEach((record) => {
      const key = normalizeHistoryUrl(record.url || '')
      if (key && !map.has(key)) map.set(key, record)
    })
    return map
  }, [applicationHistory])

  useEffect(() => {
    const loadDefaults = async () => {
      try {
        const response = await fetch(`${API_BASE_URL}/api/skyvern/defaults`)
        const result = await response.json()
        if (result.success && result.data) {
          setForm((current) => ({
            ...current,
            skyvernBaseUrl: result.data.skyvernBaseUrl || current.skyvernBaseUrl,
            resumePath: result.data.resumePath || current.resumePath,
            engine: result.data.engine || current.engine,
            modelName: result.data.modelName ?? current.modelName,
            browserAddress: result.data.browserAddress || current.browserAddress,
            maxApplications: Number(result.data.maxApplications ?? current.maxApplications),
            maxSteps: Number(result.data.maxSteps ?? current.maxSteps),
          }))
        }
      } catch {
        setMessage('Skyvern 默认配置未读取，已使用本页默认值。')
      } finally {
        setLoadingDefaults(false)
      }
    }
    loadDefaults()
  }, [])

  useEffect(() => {
    const loadLlmConfig = async () => {
      try {
        const response = await fetch(`${API_BASE_URL}/api/skyvern/llm-config`)
        const result = await response.json()
        if (result.success && result.data) {
          setAiApiKeyConfigured(Boolean(result.data.apiKeyConfigured))
          setForm((current) => ({
            ...current,
            modelName: result.data.modelName || current.modelName,
            aiBaseUrl: result.data.baseUrl || current.aiBaseUrl,
          }))
        }
      } catch {
        setAiApiKeyConfigured(false)
      }
    }
    void loadLlmConfig()
  }, [])

  useEffect(() => {
    const loadApplicantProfile = async () => {
      try {
        const response = await fetch(`${API_BASE_URL}/api/ai-apply/profile`)
        const result = await response.json()
        if (result.success && result.data) {
          setApplicantProfile(normalizeApplicantProfile(result.data))
          setProfileDirty(false)
        }
      } catch {
        // Profile is optional; the user can upload a resume or fill it later.
      }
    }
    void loadApplicantProfile()
  }, [])

  useEffect(() => {
    void loadApplicationHistory()
  }, [])

  useEffect(() => {
    void loadReadiness(false)
  }, [])

  useEffect(() => {
    if (typeof window === 'undefined') return
    const params = new URLSearchParams(window.location.search)
    if ([...params.keys()].length === 0) return

    setForm((current) => ({
      ...current,
      goal: params.get('goal') || current.goal,
      companyName: params.get('companyName') || current.companyName,
      targetUrl: params.get('targetUrl') || current.targetUrl,
      resumePath: params.get('resumePath') || current.resumePath,
      extraNotes: params.get('extraNotes') || current.extraNotes,
    }))
  }, [])

  useEffect(() => {
    return () => {
      if (pollTimer.current) clearInterval(pollTimer.current)
      if (discoveryPollTimer.current) clearInterval(discoveryPollTimer.current)
    }
  }, [])

  useEffect(() => {
    if (!run?.run_id || isTerminal) {
      if (pollTimer.current) clearInterval(pollTimer.current)
      setPolling(false)
      return
    }

    setPolling(true)
    if (pollTimer.current) clearInterval(pollTimer.current)
    pollTimer.current = setInterval(() => {
      void refreshRun(run.run_id)
    }, 5000)
    return () => {
      if (pollTimer.current) clearInterval(pollTimer.current)
    }
  }, [run?.run_id, isTerminal])

  const updateForm = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((current) => ({ ...current, [key]: value }))
  }

  const normalizeApplicantProfile = (data: Record<string, unknown>): ApplicantProfile => ({
    fullName: String(data.fullName || ''),
    email: String(data.email || ''),
    phone: String(data.phone || ''),
    currentCity: String(data.currentCity || ''),
    school: String(data.school || ''),
    major: String(data.major || ''),
    degree: String(data.degree || ''),
    graduationDate: String(data.graduationDate || ''),
    wechat: String(data.wechat || ''),
    portfolio: String(data.portfolio || ''),
    expectedRole: String(data.expectedRole || ''),
    skills: String(data.skills || ''),
    availability: String(data.availability || ''),
    internshipDuration: String(data.internshipDuration || ''),
    weeklyAvailability: String(data.weeklyAvailability || ''),
    expectedSalary: String(data.expectedSalary || ''),
    preferredLocations: String(data.preferredLocations || ''),
    workPreference: String(data.workPreference || ''),
    selfIntroduction: String(data.selfIntroduction || ''),
    coverLetter: String(data.coverLetter || ''),
  })

  const updateApplicantProfile = <K extends keyof ApplicantProfile>(key: K, value: ApplicantProfile[K]) => {
    setApplicantProfile((current) => ({ ...current, [key]: value }))
    setProfileDirty(true)
  }

  const parseCompanyFile = async (file: File) => {
    setCompanyParseMessage('')
    setCompanyFileName(file.name)
    try {
      const body = new FormData()
      body.append('file', file)
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/company-list/upload`, {
        method: 'POST',
        body,
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '解析公司名单失败')
      }
      const companies = Array.isArray(result.data?.companies)
        ? result.data.companies.map((name: unknown) => String(name || '').trim()).filter(Boolean)
        : []
      if (companies.length === 0) throw new Error('没有识别到公司名称')

      updateForm('companyNames', companies)
      setCompanyParseMessage(result.data?.message || `已识别 ${companies.length} 家公司；查找岗位时会逐家公司找官网、招聘页和相似岗位。`)
    } catch (e) {
      updateForm('companyNames', [])
      setCompanyParseMessage(e instanceof Error ? e.message : '解析失败')
    }
  }

  const clearCompanyFile = () => {
    updateForm('companyNames', [])
    setCompanyFileName('')
    setCompanyParseMessage('')
    if (companyFileInputRef.current) {
      companyFileInputRef.current.value = ''
    }
  }

  const uploadResumeFile = async (file: File) => {
    setResumeUploadMessage('')
    setResumeUploading(true)
    try {
      const body = new FormData()
      body.append('file', file)
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/resume/upload`, {
        method: 'POST',
        body,
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '上传简历失败')
      }
      const savedPath = result.data?.savedPath || ''
      if (savedPath) {
        updateForm('resumePath', savedPath)
      }
      if (result.data?.profile) {
        setApplicantProfile(normalizeApplicantProfile(result.data.profile))
        setProfileDirty(false)
      }
      const profileText = result.data?.profileMessage ? ` ${result.data.profileMessage}` : ''
      const warningText = result.data?.profileWarning ? ` ${result.data.profileWarning}` : ''
      setResumeUploadMessage(`${result.data?.message || '简历已保存，投递时会自动上传。'}${profileText}${warningText}`)
      if (result.data?.profileMessage) {
        setProfileMessage(String(result.data.profileMessage))
      }
      void loadReadiness(false)
    } catch (e) {
      setResumeUploadMessage(e instanceof Error ? e.message : '上传简历失败')
      if (resumeFileInputRef.current) {
        resumeFileInputRef.current.value = ''
      }
    } finally {
      setResumeUploading(false)
    }
  }

  const saveQuickAiModelConfig = async (): Promise<string> => {
    if (!form.modelName.trim()) throw new Error('请先填写 AI 模型名称。')
    if (!form.aiBaseUrl.trim()) throw new Error('请先填写 API 地址。')
    const response = await fetch(`${API_BASE_URL}/api/skyvern/llm-config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        provider: 'openai-compatible',
        modelName: form.modelName.trim(),
        baseUrl: form.aiBaseUrl.trim(),
        apiKey: aiApiKey,
        supportsVision: false,
        maxTokens: 4096,
      }),
    })
    const result = await response.json()
    if (!response.ok || !result.success) throw new Error(result.message || '保存 AI 配置失败')
    setAiApiKey('')
    setAiApiKeyConfigured(true)
    return result.data?.message || 'AI配置已保存。'
  }

  const ensureConfigSaved = async (): Promise<boolean> => {
    setError('')
    if (!form.modelName.trim()) { setError('请先填写 AI 模型名称。'); return false }
    if (!form.aiBaseUrl.trim()) { setError('请先填写 API 地址。'); return false }
    if (!aiApiKeyConfigured && !aiApiKey.trim()) { setError('请先填写 API Key 并点击"保存并应用"。'); return false }
    if (aiApiKey.trim()) { setError('请先点击"保存并应用"保存 API Key。'); return false }
    return true
  }

  const handleSaveConfig = async () => {
    setError('')
    setMessage('')
    if (!form.modelName.trim()) { setError('请先填写 AI 模型名称。'); return }
    if (!form.aiBaseUrl.trim()) { setError('请先填写 API 地址。'); return }
    if (!aiApiKey.trim() && !aiApiKeyConfigured) { setError('请先填写 API Key。'); return }
    setSaving(true)
    try {
      const msg = await saveQuickAiModelConfig()
      setMessage(msg)
      void loadReadiness(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存 AI 配置失败')
    } finally {
      setSaving(false)
    }
  }

  const persistApplicantProfile = async () => {
    const response = await fetch(`${API_BASE_URL}/api/ai-apply/profile`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(applicantProfile),
    })
    const result = await response.json()
    if (!response.ok || !result.success) {
      throw new Error(result.message || '保存投递资料失败')
    }
    const normalized = normalizeApplicantProfile(result.data || {})
    setApplicantProfile(normalized)
    setProfileDirty(false)
    return { profile: normalized, message: result.data?.message || '投递资料已保存。' }
  }

  const saveApplicantProfile = async () => {
    setProfileSaving(true)
    setProfileMessage('')
    setError('')
    try {
      const saved = await persistApplicantProfile()
      setProfileMessage(saved.message)
      void loadReadiness(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存投递资料失败')
    } finally {
      setProfileSaving(false)
    }
  }

  const extractProfileFromCurrentResume = async () => {
    if (!form.resumePath.trim()) {
      setError('请先上传简历，或填写本地简历文件路径。')
      return
    }
    setProfileExtracting(true)
    setProfileMessage('')
    setError('')
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/resume/extract`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ resumePath: form.resumePath.trim() }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '识别简历资料失败')
      }
      if (result.data?.profile) {
        setApplicantProfile(normalizeApplicantProfile(result.data.profile))
        setProfileDirty(false)
      }
      const warning = result.data?.warning ? ` ${result.data.warning}` : ''
      setProfileMessage(`${result.data?.message || '已根据简历自动填入投递资料。'}${warning}`)
      void loadReadiness(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : '识别简历资料失败')
    } finally {
      setProfileExtracting(false)
    }
  }

  const loadApplicationHistory = async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/history?limit=80`)
      const result = await response.json()
      if (response.ok && result.success) {
        setApplicationHistory(Array.isArray(result.data) ? result.data : [])
      }
    } catch {
      // History is best-effort; application flow can continue without it.
    }
  }

  const saveApplicationHistory = async (
    record: JobApplyRecord,
    overrides: Partial<ApplicationHistoryRecord> = {}
  ) => {
    try {
      const payload = {
        company: record.job.company,
        title: record.job.title,
        url: record.job.url,
        source: record.job.source,
        status: record.status,
        runId: record.runId,
        skyvernStatus: record.skyvernStatus,
        error: record.error,
        appUrl: record.appUrl,
        runStatusUrl: record.runStatusUrl,
        browserDebugUrl: record.browserDebugUrl,
        autoSubmit: form.autoSubmit,
        checkedAt: record.job.checkedAt,
        targetCompany: record.job.targetCompany,
        officialWebsite: record.job.officialWebsite,
        careersPage: record.job.careersPage,
        companySearchStatus: record.job.companySearchStatus,
        ...overrides,
      }
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/history`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      const result = await response.json()
      if (response.ok && result.success) {
        setApplicationHistory((current) => {
          const saved: ApplicationHistoryRecord = result.data
          const savedKey = normalizeHistoryUrl(saved.url || '')
          return [
            saved,
            ...current.filter((item) => normalizeHistoryUrl(item.url || '') !== savedKey && item.id !== saved.id),
          ].slice(0, 80)
        })
      }
    } catch {
      setHistoryMessage('投递记录暂时没有保存成功，但投递任务会继续执行。')
    }
  }

  const historyStatusLabel = (status?: string) => {
    if (status === 'submitted') return '已完成'
    if (status === 'waiting_confirm') return '等待确认'
    if (status === 'running' || status === 'applying') return '执行中'
    if (status === 'failed') return '失败'
    return status || '已记录'
  }

  const isDuplicateHistory = (record?: ApplicationHistoryRecord) => {
    return ['submitted', 'waiting_confirm', 'running', 'applying'].includes(String(record?.status || ''))
  }

  const deleteApplicationHistory = async (record: ApplicationHistoryRecord) => {
    const id = record.id || record.url
    if (!id) return
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/history/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '删除投递记录失败')
      }
      setApplicationHistory((current) => current.filter((item) => item.id !== record.id && item.url !== record.url))
    } catch (e) {
      setHistoryMessage(e instanceof Error ? e.message : '删除投递记录失败')
    }
  }

  const runDiagnostics = async () => {
    setDiagRunning(true)
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/diagnostics`)
      const result = await response.json()
      if (response.ok && result.success) {
        setDiagResults(result.data)
      } else {
        setDiagResults([{ name: '诊断服务', status: 'error', message: result.message || '诊断失败' }])
      }
    } catch {
      setDiagResults([{ name: '诊断服务', status: 'error', message: '无法连接到后端服务' }])
    } finally {
      setDiagRunning(false)
    }
  }

  const loadReadiness = async (showLoading = true): Promise<ReadinessResult | null> => {
    if (showLoading) setReadinessLoading(true)
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/readiness`)
      const result = await response.json()
      if (response.ok && result.success) {
        const data = result.data as ReadinessResult
        setReadiness(data)
        return data
      }
      const fallback: ReadinessResult = {
        ready: false,
        warningCount: 0,
        message: result.message || '投递前体检失败',
        items: [{ name: '投递前体检', status: 'error', message: result.message || '投递前体检失败' }],
      }
      setReadiness(fallback)
      return fallback
    } catch {
      const fallback: ReadinessResult = {
        ready: false,
        warningCount: 0,
        message: '无法连接到后端服务',
        items: [{ name: '投递前体检', status: 'error', message: '无法连接到后端服务' }],
      }
      setReadiness(fallback)
      return fallback
    } finally {
      if (showLoading) setReadinessLoading(false)
    }
  }

  const ensureReadyForApplication = async (): Promise<boolean> => {
    setError('')
    try {
      if (profileDirty) {
        setProfileSaving(true)
        const saved = await persistApplicantProfile()
        setProfileMessage(`${saved.message} 已用于本次投递。`)
      }
      const status = await loadReadiness(false)
      if (!status?.ready) {
        const errors = status?.items.filter((item) => item.status === 'error') || []
        setError(errors.length > 0
          ? `请先补齐：${errors.map((item) => `${item.name}（${item.message}）`).join('；')}`
          : (status?.message || '投递资料还没准备好。'))
        return false
      }
      return true
    } catch (e) {
      setError(e instanceof Error ? e.message : '投递资料保存失败')
      return false
    } finally {
      setProfileSaving(false)
    }
  }

  const finishDiscovery = (task: DiscoveryTask) => {
    const jobs = task.jobs || []
    setDiscoveredJobs(jobs)
    setSelectedJobIds(new Set(jobs
      .filter((j) => j.confidence !== 'low' && j.deliveryDifficulty !== 'manual')
      .filter((j) => !isDuplicateHistory(historyByUrl.get(normalizeHistoryUrl(j.url))))
      .map((j) => j.id)))
    const duplicateCount = jobs.filter((j) => isDuplicateHistory(historyByUrl.get(normalizeHistoryUrl(j.url)))).length
    setMessage(jobs.length > 0
      ? `已找到 ${jobs.length} 个候选岗位${duplicateCount ? `，其中 ${duplicateCount} 个近期已有投递记录，已默认不勾选` : ''}，请确认后点击"投递选中岗位"。`
      : (task.message || '暂时没有找到可靠岗位，可以放宽城市或岗位关键词后重试。'))
  }

  const pollDiscoveryStatus = async (id: string) => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/discover/status/${id}`)
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '刷新查找状态失败')
      }
      const task: DiscoveryTask = result.data
      setDiscoveryTask(task)
      if (task.message) setMessage(task.message)

      if (task.status === 'completed') {
        if (discoveryPollTimer.current) clearInterval(discoveryPollTimer.current)
        discoveryPollTimer.current = null
        setDiscovering(false)
        finishDiscovery(task)
      } else if (task.status === 'failed') {
        if (discoveryPollTimer.current) clearInterval(discoveryPollTimer.current)
        discoveryPollTimer.current = null
        setDiscovering(false)
        setError(task.error || task.message || '查找岗位失败')
      } else if (task.status === 'cancelled') {
        if (discoveryPollTimer.current) clearInterval(discoveryPollTimer.current)
        discoveryPollTimer.current = null
        setDiscovering(false)
        setMessage(task.message || '已停止真实查找任务。')
      }
    } catch (e) {
      if (discoveryPollTimer.current) clearInterval(discoveryPollTimer.current)
      discoveryPollTimer.current = null
      setDiscovering(false)
      setError(e instanceof Error ? e.message : '刷新查找状态失败')
    }
  }

  const cancelDiscovery = async () => {
    if (!discoveryTask?.id) return
    setCancellingDiscovery(true)
    setError('')
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/discover/cancel/${discoveryTask.id}`, {
        method: 'POST',
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '停止查找失败')
      }
      const task: DiscoveryTask = result.data
      setDiscoveryTask(task)
      setDiscovering(false)
      setMessage(task.message || '已停止真实查找任务。')
      if (discoveryPollTimer.current) clearInterval(discoveryPollTimer.current)
      discoveryPollTimer.current = null
    } catch (e) {
      setError(e instanceof Error ? e.message : '停止查找失败')
    } finally {
      setCancellingDiscovery(false)
    }
  }

  const discoverJobs = async () => {
    setError('')
    setMessage('')
    const canProceed = await ensureConfigSaved()
    if (!canProceed) return

    setDiscovering(true)
    setDiscoveryTask(null)
    setDiscoveredJobs([])
    setSelectedJobIds(new Set())
    setJobRecords([])
    const allCompanies = [...new Set([
      ...form.companyNames,
      ...(form.companyName.trim() ? [form.companyName.trim()] : []),
    ])]
    setMessage(allCompanies.length > 0
      ? `正在按 ${allCompanies.length} 家目标公司逐家查找官网、招聘页和相似岗位，可能需要几分钟。`
      : '正在用真实浏览器查找岗位，可能需要几分钟。只会返回已验证的岗位详情页。')
    try {
      const response = await fetch(`${API_BASE_URL}/api/ai-apply/discover/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          goal: form.goal.trim(),
          resumePath: form.resumePath.trim() || undefined,
          companyNames: allCompanies.length > 0 ? allCompanies : undefined,
          extraNotes: form.extraNotes.trim() || undefined,
        }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) {
        throw new Error(result.message || '查找岗位失败')
      }
      const task: DiscoveryTask = result.data
      setDiscoveryTask(task)
      if (task.message) setMessage(task.message)
      if (discoveryPollTimer.current) clearInterval(discoveryPollTimer.current)
      discoveryPollTimer.current = setInterval(() => {
        void pollDiscoveryStatus(task.id)
      }, 5000)
      void pollDiscoveryStatus(task.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : '查找岗位失败')
      setDiscovering(false)
    }
  }

  const toggleJobSelection = (jobId: string) => {
    setSelectedJobIds((prev) => {
      const next = new Set(prev)
      if (next.has(jobId)) next.delete(jobId)
      else next.add(jobId)
      return next
    })
  }

  const toggleAllJobs = () => {
    const selectable = discoveredJobs
      .filter((j) => j.deliveryDifficulty !== 'manual')
      .filter((j) => !isDuplicateHistory(historyByUrl.get(normalizeHistoryUrl(j.url))))
    if (selectedJobIds.size === selectable.length) {
      setSelectedJobIds(new Set())
    } else {
      setSelectedJobIds(new Set(selectable.map((j) => j.id)))
    }
  }

  const applySelected = async () => {
    setError('')
    setMessage('')
    const canProceed = await ensureConfigSaved()
    if (!canProceed) return

    const selected = discoveredJobs.filter((j) => selectedJobIds.has(j.id))
    if (selected.length === 0) {
      setError('请至少勾选一个岗位。')
      return
    }
    const readyForApplication = await ensureReadyForApplication()
    if (!readyForApplication) return

    setApplying(true)
    const records: JobApplyRecord[] = selected.map((job) => ({ job, status: 'pending' as const }))
    setJobRecords(records)
    let startedCount = 0

    for (let i = 0; i < records.length; i++) {
      const record = records[i]

      // Skip only platforms that require fully manual handling.
      if (record.job.deliveryDifficulty === 'manual') {
        setJobRecords((prev) => prev.map((r, idx) => idx === i ? { ...r, status: 'failed', error: '需人工确认，已跳过' } : r))
        void saveApplicationHistory(record, { status: 'failed', error: '需人工确认，已跳过' })
        continue
      }

      setJobRecords((prev) => prev.map((r, idx) => idx === i ? { ...r, status: 'applying' } : r))
      void saveApplicationHistory(record, { status: 'applying', message: '正在启动投递任务' })

      try {
        // Route 智联招聘 to native worker instead of Skyvern
        const isZhilian = record.job.source === '智联招聘' || record.job.url?.includes('zhaopin.com')

        if (isZhilian) {
          const response = await fetch(`${API_BASE_URL}/api/zhilian/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              keyword: record.job.title,
              companyName: record.job.company,
              autoSubmit: form.autoSubmit,
            }),
          })
          const result = await response.json()
          if (!response.ok || !result.success) {
            throw new Error(result.message || '智联招聘投递启动失败')
          }
          setJobRecords((prev) => prev.map((r, idx) =>
            idx === i ? { ...r, status: 'running', runId: 'zhilian-native' } : r
          ))
          void saveApplicationHistory(record, { status: 'running', runId: 'zhilian-native', message: '智联招聘投递任务已启动' })
          startedCount++
        } else {
          const applyBody = {
            ...form,
            goal: `请投递这个已选岗位：${record.job.title}，公司：${record.job.company}。用户原始需求：${form.goal}。匹配理由：${record.job.matchReason || '该岗位来自已确认的候选列表。'}`,
            companyName: record.job.company,
            companyNames: record.job.company ? [record.job.company] : [],
            targetUrl: record.job.url || form.targetUrl,
            extraNotes: [
              form.extraNotes.trim(),
              record.job.source ? `候选来源：${record.job.source}` : '',
              record.job.evidenceText ? `岗位证据：${record.job.evidenceText}` : '',
              record.job.targetCompany ? `目标公司名单匹配：${record.job.targetCompany}` : '',
              record.job.officialWebsite ? `程序找到的官网：${record.job.officialWebsite}` : '',
              record.job.careersPage ? `程序找到的招聘页：${record.job.careersPage}` : '',
              record.job.companySearchStatus ? `公司查找状态：${record.job.companySearchStatus}` : '',
            ].filter(Boolean).join('\n'),
          }
          const response = await fetch(`${API_BASE_URL}/api/skyvern/apply`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(applyBody),
          })
          const result = await response.json()
          if (!response.ok || !result.success) {
            throw new Error(result.message || '投递失败')
          }
          setJobRecords((prev) => prev.map((r, idx) =>
            idx === i ? {
              ...r,
              status: 'running',
              runId: result.data?.run_id,
              appUrl: result.data?.app_url,
              recordingUrl: result.data?.recording_url,
              screenshotUrls: result.data?.screenshot_urls,
              runStatusUrl: result.data?.runStatusUrl,
              browserDebugUrl: result.data?.browserDebugUrl,
              browserHint: result.data?.browserHint,
            } : r
          ))
          void saveApplicationHistory(record, {
            status: 'running',
            runId: result.data?.run_id,
            appUrl: result.data?.app_url,
            runStatusUrl: result.data?.runStatusUrl,
            browserDebugUrl: result.data?.browserDebugUrl,
            message: 'Skyvern 投递任务已启动',
          })
          setRun(result.data)
          startedCount++
        }
      } catch (e) {
        const errMsg = e instanceof Error ? e.message : '投递失败'
        setJobRecords((prev) => prev.map((r, idx) => idx === i ? { ...r, status: 'failed', error: errMsg } : r))
        void saveApplicationHistory(record, { status: 'failed', error: errMsg })
      }
    }

    setApplying(false)
    setMessage(`已启动 ${startedCount}/${records.length} 个岗位的投递任务，正在等待执行结果...`)
  }

  const pollJobStatuses = async () => {
    const runningRecords = jobRecords.filter((r) => r.status === 'running' && r.runId)
    if (runningRecords.length === 0) return

    for (const record of runningRecords) {
      try {
        // Handle 智联招聘 native worker status
        if (record.runId === 'zhilian-native') {
          const response = await fetch(`${API_BASE_URL}/api/zhilian/status`)
          const result = await response.json()
          if (result.success) {
            const isRunning = result.isRunning
            if (!isRunning) {
              if (result.loginRequired) {
                setJobRecords((prev) => prev.map((r) =>
                  r.runId === 'zhilian-native' ? { ...r, status: 'failed', error: '智联招聘需要先登录，请在打开的浏览器中完成登录后再继续投递' } : r
                ))
                void saveApplicationHistory(record, {
                  status: 'failed',
                  error: '智联招聘需要先登录，请在打开的浏览器中完成登录后再继续投递',
                  skyvernStatus: 'login_required',
                })
              } else {
                const nextStatus = form.autoSubmit ? 'submitted' : 'waiting_confirm'
                setJobRecords((prev) => prev.map((r) =>
                  r.runId === 'zhilian-native'
                    ? { ...r, status: nextStatus, skyvernStatus: 'completed' }
                    : r
                ))
                void saveApplicationHistory(record, {
                  status: nextStatus,
                  skyvernStatus: 'completed',
                  message: form.autoSubmit ? '智联招聘投递已完成' : '智联招聘申请表已准备好，等待手动确认',
                })
              }
            }
          }
          continue
        }

        const response = await fetch(`${API_BASE_URL}/api/skyvern/runs/status`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            runId: record.runId,
            skyvernBaseUrl: form.skyvernBaseUrl,
            skyvernApiKey: form.skyvernApiKey,
          }),
        })
        const result = await response.json()
        if (!response.ok || !result.success) continue

        const skyvernStatus = result.data?.status
        const failureReason = result.data?.failure_reason
        const appUrl = result.data?.app_url
        const recordingUrl = result.data?.recording_url
        const screenshotUrls = result.data?.screenshot_urls
        const runStatusUrl = result.data?.runStatusUrl
        const browserDebugUrl = result.data?.browserDebugUrl
        const browserHint = result.data?.browserHint

        if (skyvernStatus === 'completed') {
          const nextStatus = form.autoSubmit ? 'submitted' : 'waiting_confirm'
          setJobRecords((prev) => prev.map((r) =>
            r.runId === record.runId
              ? { ...r, status: nextStatus, skyvernStatus, appUrl, recordingUrl, screenshotUrls, runStatusUrl, browserDebugUrl, browserHint }
              : r
          ))
          void saveApplicationHistory(record, {
            status: nextStatus,
            skyvernStatus,
            appUrl,
            runStatusUrl,
            browserDebugUrl,
            message: form.autoSubmit ? 'Skyvern 投递任务已完成' : 'Skyvern 已填好申请，等待手动确认提交',
          })
        } else if (skyvernStatus === 'failed' || skyvernStatus === 'cancelled' || skyvernStatus === 'canceled' || skyvernStatus === 'timed_out' || skyvernStatus === 'terminated') {
          setJobRecords((prev) => prev.map((r) =>
            r.runId === record.runId ? { ...r, status: 'failed', skyvernStatus, error: failureReason || `任务${skyvernStatus}`, appUrl, recordingUrl, screenshotUrls, runStatusUrl, browserDebugUrl, browserHint } : r
          ))
          void saveApplicationHistory(record, {
            status: 'failed',
            skyvernStatus,
            error: failureReason || `任务${skyvernStatus}`,
            appUrl,
            runStatusUrl,
            browserDebugUrl,
          })
        } else {
          setJobRecords((prev) => prev.map((r) =>
            r.runId === record.runId ? { ...r, skyvernStatus, appUrl, recordingUrl, screenshotUrls, runStatusUrl, browserDebugUrl, browserHint } : r
          ))
          void saveApplicationHistory(record, {
            status: 'running',
            skyvernStatus,
            appUrl,
            runStatusUrl,
            browserDebugUrl,
          })
        }
      } catch {
        // ignore polling errors
      }
    }
  }

  useEffect(() => {
    const hasRunning = jobRecords.some((r) => r.status === 'running')
    if (!hasRunning) return

    const timer = setInterval(() => { void pollJobStatuses() }, 8000)
    return () => { clearInterval(timer) }
  }, [jobRecords])

  const refreshRun = async (runId = run?.run_id) => {
    if (!runId) return
    try {
      const response = await fetch(`${API_BASE_URL}/api/skyvern/runs/status`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          runId,
          skyvernBaseUrl: form.skyvernBaseUrl,
          skyvernApiKey: form.skyvernApiKey,
        }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) throw new Error(result.message || '刷新失败')
      setRun((current) => ({ ...(current || {}), ...result.data }))
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新失败')
    }
  }

  const cancelRun = async () => {
    if (!run?.run_id) return
    setError('')
    try {
      const response = await fetch(`${API_BASE_URL}/api/skyvern/runs/cancel`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          runId: run.run_id,
          skyvernBaseUrl: form.skyvernBaseUrl,
          skyvernApiKey: form.skyvernApiKey,
        }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) throw new Error(result.message || '停止失败')
      await refreshRun(run.run_id)
      setMessage('已请求停止当前任务。')
    } catch (e) {
      setError(e instanceof Error ? e.message : '停止失败')
    }
  }

  const statusTone = (() => {
    const status = String(run?.status || '').toLowerCase()
    if (status === 'completed') return 'text-emerald-600 bg-emerald-500/10 border-emerald-500/20'
    if (['failed', 'terminated', 'timed_out', 'canceled', 'cancelled'].includes(status)) return 'text-red-600 bg-red-500/10 border-red-500/20'
    if (status) return 'text-blue-600 bg-blue-500/10 border-blue-500/20'
    return 'text-muted-foreground bg-muted/40 border-border'
  })()

  const outputText = run?.output ? JSON.stringify(run.output, null, 2) : ''

  const configMissing = !form.modelName.trim() || !form.aiBaseUrl.trim() || !aiApiKeyConfigured
  const readinessErrors = readiness?.items.filter((item) => item.status === 'error') || []
  const readinessWarnings = readiness?.items.filter((item) => item.status === 'warning') || []

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<Send className="h-6 w-6" />}
        title="AI投递助手"
        subtitle="先用 AI 发现匹配岗位，确认后再自动投递"
        actions={
          <Link
            href="/ai-config"
            className="inline-flex h-9 items-center rounded-full border border-border bg-background px-4 text-sm font-medium hover:bg-muted"
          >
            AI配置
          </Link>
        }
      />

      {configMissing && (
        <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 p-4 flex items-start gap-3">
          <Info className="h-5 w-5 text-amber-600 mt-0.5 shrink-0" />
          <div className="space-y-1">
            <div className="text-sm font-medium text-amber-800 dark:text-amber-200">请先完成 AI 配置</div>
            <div className="text-sm text-amber-700 dark:text-amber-300">
              {!form.modelName.trim() && <span className="inline-block mr-3">· AI模型名称</span>}
              {!form.aiBaseUrl.trim() && <span className="inline-block mr-3">· API 地址</span>}
              {!aiApiKeyConfigured && <span className="inline-block mr-3">· API Key</span>}
              <div className="mt-1">请在下方填写以上信息，保存后即可开始投递。</div>
            </div>
          </div>
        </div>
      )}

      {/* 诊断面板 */}
      <div className="rounded-lg border border-border bg-white/40 dark:bg-white/5 p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <Stethoscope className="h-4 w-4 text-muted-foreground" />
            <span className="text-sm font-medium">运行诊断</span>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void runDiagnostics()}
            disabled={diagRunning}
          >
            {diagRunning ? <Loader2 className="h-3 w-3 animate-spin mr-1" /> : <RefreshCw className="h-3 w-3 mr-1" />}
            {diagRunning ? '检测中...' : '开始检测'}
          </Button>
        </div>
        {diagResults.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
            {diagResults.map((item, i) => (
              <div key={i} className="flex items-start gap-2 rounded-md border border-border/50 px-3 py-2 text-sm">
                {item.status === 'ok' && <CheckCircle2 className="h-4 w-4 text-emerald-500 mt-0.5 shrink-0" />}
                {item.status === 'warning' && <AlertTriangle className="h-4 w-4 text-amber-500 mt-0.5 shrink-0" />}
                {item.status === 'error' && <XCircle className="h-4 w-4 text-red-500 mt-0.5 shrink-0" />}
                <div className="min-w-0">
                  <div className="font-medium text-xs">{item.name}</div>
                  <div className="text-xs text-muted-foreground mt-0.5 break-words">{item.message}</div>
                </div>
              </div>
            ))}
          </div>
        )}
        {diagResults.length === 0 && !diagRunning && (
          <div className="text-xs text-muted-foreground">点击"开始检测"检查所有组件是否正常</div>
        )}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1.2fr)_minmax(360px,0.8fr)] gap-6">
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-xl">
                <Activity className="h-5 w-5 text-emerald-500" />
                投递任务
              </CardTitle>
              <CardDescription>描述求职目标，AI 先帮你发现匹配岗位，确认后再自动投递</CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="space-y-2">
                <Label htmlFor="goal" className="flex items-center gap-2">
                  <Search className="h-4 w-4" />
                  我想要的工作或实习
                </Label>
                <Textarea
                  id="goal"
                  value={form.goal}
                  onChange={(e) => updateForm('goal', e.target.value)}
                  className="min-h-32 resize-y bg-white/60 dark:bg-white/5"
                />
              </div>

              <div className="space-y-4 rounded-md border border-emerald-500/20 bg-emerald-500/5 p-4">
                <div>
                  <div className="text-sm font-medium">可选线索</div>
                  <div className="text-xs text-muted-foreground mt-1">不填也可以，AI 会根据求职目标自己搜索。</div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label htmlFor="companyName" className="flex items-center gap-2">
                      <Building2 className="h-4 w-4" />
                      指定公司
                    </Label>
                    <Input
                      id="companyName"
                      value={form.companyName}
                      onChange={(e) => updateForm('companyName', e.target.value)}
                      placeholder="可选，例如：字节跳动"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="targetUrl" className="flex items-center gap-2">
                      <LinkIcon className="h-4 w-4" />
                      指定官网或招聘页
                    </Label>
                    <Input
                      id="targetUrl"
                      value={form.targetUrl}
                      onChange={(e) => updateForm('targetUrl', e.target.value)}
                      placeholder="可选，https://..."
                    />
                  </div>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="companyFile" className="flex items-center gap-2">
                    <FileSpreadsheet className="h-4 w-4" />
                    公司名单 Excel
                  </Label>
                  <Input
                    id="companyFile"
                    ref={companyFileInputRef}
                    type="file"
                    accept=".xlsx,.xls,.csv"
                    onChange={(e) => {
                      const file = e.target.files?.[0]
                      if (file) void parseCompanyFile(file)
                    }}
                  />
                  <div className="flex items-start justify-between gap-2">
                    <div className="text-xs text-muted-foreground">
                      {companyFileName ? `${companyFileName} · ` : ''}{companyParseMessage || '可上传含公司名的 Excel/CSV；查找时会逐家公司找官网、招聘页和相似岗位。'}
                    </div>
                    {(companyFileName || form.companyNames.length > 0) && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={clearCompanyFile}
                        className="h-7 shrink-0 px-2 text-xs"
                      >
                        <XCircle className="h-3.5 w-3.5 mr-1" />
                        清除
                      </Button>
                    )}
                  </div>
                  {form.companyNames.length > 0 && (
                    <div className="max-h-24 overflow-auto rounded-md border border-white/20 bg-white/40 dark:bg-white/5 p-2 text-xs">
                      {form.companyNames.slice(0, 30).join('、')}
                      {form.companyNames.length > 30 ? ` 等 ${form.companyNames.length} 家` : ''}
                    </div>
                  )}
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="resumePath" className="flex items-center gap-2">
                  <FileText className="h-4 w-4" />
                  本地简历文件
                </Label>
                <Input
                  ref={resumeFileInputRef}
                  type="file"
                  accept=".pdf,.doc,.docx"
                  disabled={resumeUploading}
                  onChange={(e) => {
                    const file = e.target.files?.[0]
                    if (file) void uploadResumeFile(file)
                  }}
                />
                <Input
                  id="resumePath"
                  value={form.resumePath}
                  onChange={(e) => updateForm('resumePath', e.target.value)}
                  placeholder="E:\我的简历\resume.pdf"
                />
                <div className="text-xs text-muted-foreground">
                  {resumeUploading ? '正在保存简历...' : (resumeUploadMessage || '选择 PDF/DOC/DOCX 后会保存到本地运行目录，并在投递时自动上传。')}
                </div>
              </div>

              <div className="space-y-4 rounded-md border border-sky-500/20 bg-sky-500/5 p-4">
                <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                  <div>
                    <div className="flex items-center gap-2 text-sm font-medium">
                      <UserRound className="h-4 w-4" />
                      投递资料
                    </div>
                    <div className="text-xs text-muted-foreground mt-1">
                      上传简历后会自动识别，也可以手动修改；这些资料只保存在本机，投递表单需要时才会填写。
                    </div>
                  </div>
                  <div className="flex shrink-0 flex-wrap gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => void extractProfileFromCurrentResume()}
                      disabled={profileExtracting || resumeUploading || !form.resumePath.trim()}
                    >
                      {profileExtracting ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : <RefreshCw className="h-4 w-4 mr-2" />}
                      从简历识别
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => void saveApplicantProfile()}
                      disabled={profileSaving}
                    >
                      {profileSaving ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : <Save className="h-4 w-4 mr-2" />}
                      保存投递资料
                    </Button>
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label htmlFor="profileFullName">姓名</Label>
                    <Input
                      id="profileFullName"
                      value={applicantProfile.fullName}
                      onChange={(e) => updateApplicantProfile('fullName', e.target.value)}
                      placeholder="从简历自动识别"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileEmail">邮箱</Label>
                    <Input
                      id="profileEmail"
                      value={applicantProfile.email}
                      onChange={(e) => updateApplicantProfile('email', e.target.value)}
                      placeholder="you@example.com"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profilePhone">手机</Label>
                    <Input
                      id="profilePhone"
                      value={applicantProfile.phone}
                      onChange={(e) => updateApplicantProfile('phone', e.target.value)}
                      placeholder="用于招聘表单联系"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileCity">所在城市</Label>
                    <Input
                      id="profileCity"
                      value={applicantProfile.currentCity}
                      onChange={(e) => updateApplicantProfile('currentCity', e.target.value)}
                      placeholder="例如：广州"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileSchool">学校</Label>
                    <Input
                      id="profileSchool"
                      value={applicantProfile.school}
                      onChange={(e) => updateApplicantProfile('school', e.target.value)}
                      placeholder="学校名称"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileMajor">专业</Label>
                    <Input
                      id="profileMajor"
                      value={applicantProfile.major}
                      onChange={(e) => updateApplicantProfile('major', e.target.value)}
                      placeholder="专业名称"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileDegree">学历</Label>
                    <Input
                      id="profileDegree"
                      value={applicantProfile.degree}
                      onChange={(e) => updateApplicantProfile('degree', e.target.value)}
                      placeholder="本科 / 硕士 / 博士"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileGraduationDate">毕业时间</Label>
                    <Input
                      id="profileGraduationDate"
                      value={applicantProfile.graduationDate}
                      onChange={(e) => updateApplicantProfile('graduationDate', e.target.value)}
                      placeholder="例如：2027-06"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileWechat">微信/备用联系方式</Label>
                    <Input
                      id="profileWechat"
                      value={applicantProfile.wechat}
                      onChange={(e) => updateApplicantProfile('wechat', e.target.value)}
                      placeholder="可选"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profilePortfolio">作品集/个人主页</Label>
                    <Input
                      id="profilePortfolio"
                      value={applicantProfile.portfolio}
                      onChange={(e) => updateApplicantProfile('portfolio', e.target.value)}
                      placeholder="https://..."
                    />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="profileExpectedRole">求职方向</Label>
                  <Input
                    id="profileExpectedRole"
                    value={applicantProfile.expectedRole}
                    onChange={(e) => updateApplicantProfile('expectedRole', e.target.value)}
                    placeholder="例如：AI 数据分析实习 / AIGC 运营实习"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="profileSkills">技能摘要</Label>
                  <Textarea
                    id="profileSkills"
                    value={applicantProfile.skills}
                    onChange={(e) => updateApplicantProfile('skills', e.target.value)}
                    className="min-h-20 resize-y bg-white/60 dark:bg-white/5"
                    placeholder="例如：Python、SQL、数据分析、AIGC 内容运营、市场研究"
                  />
                </div>
                <div className="rounded-md border border-white/30 bg-white/30 dark:bg-white/5 p-4 space-y-4">
                  <div>
                    <div className="text-sm font-medium">常用申请回答</div>
                    <div className="text-xs text-muted-foreground mt-1">
                      官网和 ATS 表单常问这些问题，保存后 Skyvern 填表时会优先使用。
                    </div>
                  </div>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div className="space-y-2">
                      <Label htmlFor="profileAvailability">到岗时间</Label>
                      <Input
                        id="profileAvailability"
                        value={applicantProfile.availability}
                        onChange={(e) => updateApplicantProfile('availability', e.target.value)}
                        placeholder="例如：一周内 / 2026-07 可到岗"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="profileInternshipDuration">实习周期</Label>
                      <Input
                        id="profileInternshipDuration"
                        value={applicantProfile.internshipDuration}
                        onChange={(e) => updateApplicantProfile('internshipDuration', e.target.value)}
                        placeholder="例如：3个月以上 / 6个月"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="profileWeeklyAvailability">每周可实习</Label>
                      <Input
                        id="profileWeeklyAvailability"
                        value={applicantProfile.weeklyAvailability}
                        onChange={(e) => updateApplicantProfile('weeklyAvailability', e.target.value)}
                        placeholder="例如：每周4-5天"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="profileExpectedSalary">期望薪资</Label>
                      <Input
                        id="profileExpectedSalary"
                        value={applicantProfile.expectedSalary}
                        onChange={(e) => updateApplicantProfile('expectedSalary', e.target.value)}
                        placeholder="例如：面议 / 150-200元/天"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="profilePreferredLocations">期望城市</Label>
                      <Input
                        id="profilePreferredLocations"
                        value={applicantProfile.preferredLocations}
                        onChange={(e) => updateApplicantProfile('preferredLocations', e.target.value)}
                        placeholder="例如：广州、深圳、上海、北京"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="profileWorkPreference">工作方式偏好</Label>
                      <Input
                        id="profileWorkPreference"
                        value={applicantProfile.workPreference}
                        onChange={(e) => updateApplicantProfile('workPreference', e.target.value)}
                        placeholder="例如：可到岗 / 可远程 / 混合办公"
                      />
                    </div>
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileSelfIntroduction">自我介绍</Label>
                    <Textarea
                      id="profileSelfIntroduction"
                      value={applicantProfile.selfIntroduction}
                      onChange={(e) => updateApplicantProfile('selfIntroduction', e.target.value)}
                      className="min-h-20 resize-y bg-white/60 dark:bg-white/5"
                      placeholder="简短说明你的背景、优势和适合的方向"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profileCoverLetter">通用申请说明</Label>
                    <Textarea
                      id="profileCoverLetter"
                      value={applicantProfile.coverLetter}
                      onChange={(e) => updateApplicantProfile('coverLetter', e.target.value)}
                      className="min-h-20 resize-y bg-white/60 dark:bg-white/5"
                      placeholder="用于开放题，例如为什么想申请、你能带来什么"
                    />
                  </div>
                </div>
                <div className="text-xs text-muted-foreground">
                  {profileMessage || '不会自动填写身份证、银行卡、密码等高敏感信息；遇到必须人工确认的页面会暂停。'}
                </div>
              </div>

              <div className={`rounded-md border p-4 space-y-3 ${
                readiness?.ready
                  ? 'border-emerald-500/25 bg-emerald-500/5'
                  : readinessErrors.length > 0
                    ? 'border-red-500/25 bg-red-500/5'
                    : 'border-amber-500/25 bg-amber-500/5'
              }`}>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex items-start gap-2">
                    {readiness?.ready ? (
                      <CheckCircle2 className="h-4 w-4 text-emerald-600 mt-0.5 shrink-0" />
                    ) : readinessErrors.length > 0 ? (
                      <XCircle className="h-4 w-4 text-red-600 mt-0.5 shrink-0" />
                    ) : (
                      <AlertTriangle className="h-4 w-4 text-amber-600 mt-0.5 shrink-0" />
                    )}
                    <div>
                      <div className="text-sm font-medium">投递前体检</div>
                      <div className="text-xs text-muted-foreground mt-0.5">
                        {readiness?.message || '检查简历、投递资料和浏览器执行服务是否准备好。'}
                        {profileDirty ? ' 当前投递资料有修改，投递前会自动保存。' : ''}
                      </div>
                    </div>
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => void loadReadiness()}
                    disabled={readinessLoading}
                    className="shrink-0"
                  >
                    {readinessLoading ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : <RefreshCw className="h-4 w-4 mr-2" />}
                    重新检查
                  </Button>
                </div>
                {readiness?.items?.length ? (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                    {readiness.items.map((item, i) => (
                      <div key={i} className="flex items-start gap-2 rounded-md border border-white/30 bg-white/35 dark:bg-white/5 px-3 py-2">
                        {item.status === 'ok' && <CheckCircle2 className="h-4 w-4 text-emerald-500 mt-0.5 shrink-0" />}
                        {item.status === 'warning' && <AlertTriangle className="h-4 w-4 text-amber-500 mt-0.5 shrink-0" />}
                        {item.status === 'error' && <XCircle className="h-4 w-4 text-red-500 mt-0.5 shrink-0" />}
                        <div className="min-w-0">
                          <div className="text-xs font-medium">{item.name}</div>
                          <div className="text-xs text-muted-foreground break-words mt-0.5">{item.message}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="text-xs text-muted-foreground">页面打开后会自动检查，也可以手动点击重新检查。</div>
                )}
                {readinessWarnings.length > 0 && readinessErrors.length === 0 && (
                  <div className="text-xs text-amber-700 dark:text-amber-300">
                    黄色项目不会阻止投递，但补齐后官网开放题会填得更稳。
                  </div>
                )}
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="modelName">AI模型名称</Label>
                  <Input
                    id="modelName"
                    value={form.modelName}
                    onChange={(e) => updateForm('modelName', e.target.value)}
                    placeholder="例如 deepseek-chat、gpt-4o、qwen-plus"
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="aiBaseUrl">API 地址</Label>
                  <Input
                    id="aiBaseUrl"
                    value={form.aiBaseUrl}
                    onChange={(e) => updateForm('aiBaseUrl', e.target.value)}
                    placeholder="例如 https://api.deepseek.com/v1"
                  />
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="aiApiKey">API Key</Label>
                  <Input
                    id="aiApiKey"
                    type="password"
                    value={aiApiKey}
                    onChange={(e) => setAiApiKey(e.target.value)}
                    placeholder={aiApiKeyConfigured ? '已保存，留空继续使用' : '请输入 API Key'}
                  />
                </div>
                <div className="flex items-end">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => void handleSaveConfig()}
                    disabled={saving || (!aiApiKey.trim() && !aiApiKeyConfigured)}
                    className="w-full"
                  >
                    {saving ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : <Save className="h-4 w-4 mr-2" />}
                    保存并应用
                  </Button>
                </div>
              </div>

              <div className="flex items-center justify-between rounded-md border border-border bg-white/40 dark:bg-white/5 px-4 py-3">
                <div>
                  <div className="text-sm font-medium">自动提交申请</div>
                  <div className="text-xs text-muted-foreground mt-0.5">
                    {form.autoSubmit
                      ? '找到匹配岗位后自动填写并提交申请'
                      : '填写申请后会暂停，等你确认再提交'}
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={form.autoSubmit}
                  onClick={() => updateForm('autoSubmit', !form.autoSubmit)}
                  className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors ${
                    form.autoSubmit ? 'bg-emerald-600' : 'bg-gray-300 dark:bg-gray-600'
                  }`}
                >
                  <span
                    className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition-transform ${
                      form.autoSubmit ? 'translate-x-5' : 'translate-x-0'
                    }`}
                  />
                </button>
              </div>

              <Button
                onClick={() => void discoverJobs()}
                disabled={discovering || loadingDefaults || !form.goal.trim() || configMissing}
                className="w-full rounded-full bg-emerald-600 hover:bg-emerald-700 text-white"
              >
                {discovering ? <Loader2 className="h-4 w-4 animate-spin mr-2" /> : <Search className="h-4 w-4 mr-2" />}
                {discovering ? '真实浏览查找中' : '先查找岗位'}
              </Button>
              {discoveryTask && (
                <div className="rounded-md border border-emerald-500/20 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-800 dark:text-emerald-200 space-y-2">
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-medium">真实查找任务</span>
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs">{discoveryTask.status}</span>
                      {(discoveryTask.status === 'queued' || discoveryTask.status === 'running') && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => void cancelDiscovery()}
                          disabled={cancellingDiscovery}
                          className="h-7 px-2 text-xs"
                        >
                          {cancellingDiscovery ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1" /> : <CircleStop className="h-3.5 w-3.5 mr-1" />}
                          停止
                        </Button>
                      )}
                    </div>
                  </div>
                  <div className="text-xs opacity-90">{discoveryTask.message}</div>
                  {(discoveryTask.skyvernStatus || discoveryTask.skyvernRunId) && (
                    <div className="text-xs opacity-90">
                      Skyvern：{discoveryTask.skyvernStatus || '已启动'}{discoveryTask.jobCount ? ` · 已整理 ${discoveryTask.jobCount} 个` : ''}
                    </div>
                  )}
                  <div className="flex flex-wrap gap-3 text-xs">
                    {discoveryTask.skyvernAppUrl && (
                      <Link href={discoveryTask.skyvernAppUrl} target="_blank" className="inline-flex items-center gap-1 text-sky-700 hover:text-sky-800">
                        查看真实浏览任务 <ExternalLink className="h-3 w-3" />
                      </Link>
                    )}
                    {discoveryTask.runStatusUrl && (
                      <Link href={discoveryTask.runStatusUrl} target="_blank" className="inline-flex items-center gap-1 text-sky-700 hover:text-sky-800">
                        查看任务接口 <ExternalLink className="h-3 w-3" />
                      </Link>
                    )}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {discoveredJobs.length > 0 && (
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <div>
                    <CardTitle className="flex items-center gap-2 text-xl">
                      <SquareCheck className="h-5 w-5 text-indigo-500" />
                      候选岗位
                    </CardTitle>
                    <CardDescription>勾选你想投递的岗位，然后点击"投递选中岗位"</CardDescription>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button variant="outline" size="sm" onClick={toggleAllJobs}>
                      {selectedJobIds.size === discoveredJobs.length ? '取消全选' : '全选'}
                    </Button>
                    <Button
                      onClick={() => void applySelected()}
                      disabled={applying || selectedJobIds.size === 0}
                      className="rounded-full bg-indigo-600 hover:bg-indigo-700 text-white"
                      size="sm"
                    >
                      {applying ? <Loader2 className="h-4 w-4 animate-spin mr-1" /> : <Send className="h-4 w-4 mr-1" />}
                      投递选中岗位 ({selectedJobIds.size})
                    </Button>
                  </div>
                </div>
              </CardHeader>
              <CardContent>
                <div className="space-y-2">
                  {discoveredJobs.map((job) => {
                    const isSelected = selectedJobIds.has(job.id)
                    const record = jobRecords.find((r) => r.job.id === job.id)
                    const historyRecord = historyByUrl.get(normalizeHistoryUrl(job.url))
                    return (
                      <div
                        key={job.id}
                        className={`flex items-start gap-3 rounded-md border p-3 transition-colors cursor-pointer ${
                          isSelected
                            ? 'border-indigo-500/30 bg-indigo-500/5'
                            : 'border-border bg-white/40 dark:bg-white/5 opacity-60'
                        }`}
                        onClick={() => toggleJobSelection(job.id)}
                      >
                        <div className="mt-0.5 shrink-0">
                          <div
                            className={`h-5 w-5 rounded border-2 flex items-center justify-center transition-colors ${
                              isSelected
                                ? 'bg-indigo-600 border-indigo-600'
                                : 'border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800'
                            }`}
                          >
                            {isSelected && <Check className="h-3 w-3 text-white" />}
                          </div>
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="font-medium text-sm">{job.title || '未知岗位'}</span>
                            <span className="text-xs text-muted-foreground">@ {job.company || '未知公司'}</span>
                            {job.location && (
                              <span className="text-xs text-muted-foreground">· {job.location}</span>
                            )}
                            {job.confidence === 'high' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-700 border border-emerald-500/20">高可信</span>
                            )}
                            {job.confidence === 'medium' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-700 border border-blue-500/20">中可信</span>
                            )}
                            {job.confidence === 'low' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-700 border border-amber-500/20">待确认</span>
                            )}
                            {job.deliveryDifficulty === 'auto' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-green-500/10 text-green-700 border border-green-500/20">可自动投递</span>
                            )}
                            {job.deliveryDifficulty === 'login_required' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-700 border border-blue-500/20">可能需要登录</span>
                            )}
                            {job.deliveryDifficulty === 'high_risk' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-red-500/10 text-red-700 border border-red-500/20">高风控，需人工配合</span>
                            )}
                            {job.deliveryDifficulty === 'manual' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-gray-500/10 text-gray-700 border border-gray-500/20">需人工确认</span>
                            )}
                            {job.verificationStatus === 'page_verified' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-700 border border-emerald-500/20">页面已校验</span>
                            )}
                            {job.verificationStatus === 'browser_verified' && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-sky-500/10 text-sky-700 border border-sky-500/20">浏览器已验证</span>
                            )}
                            {job.targetCompany && (
                              <span className="text-xs px-1.5 py-0.5 rounded bg-purple-500/10 text-purple-700 border border-purple-500/20">目标公司：{job.targetCompany}</span>
                            )}
                            {historyRecord && (
                              <span className={`text-xs px-1.5 py-0.5 rounded border ${
                                isDuplicateHistory(historyRecord)
                                  ? 'bg-amber-500/10 text-amber-700 border-amber-500/20'
                                  : 'bg-slate-500/10 text-slate-600 border-slate-500/20'
                              }`}>
                                记录：{historyStatusLabel(historyRecord.status)}
                              </span>
                            )}
                          </div>
                          {job.matchReason && (
                            <div className="text-xs text-muted-foreground mt-1">{job.matchReason}</div>
                          )}
                          {job.evidenceText && (
                            <div className="text-xs text-slate-500 mt-1 italic">"{job.evidenceText}"</div>
                          )}
                          {job.verificationNote && (
                            <div className="text-xs text-muted-foreground mt-1">{job.verificationNote}</div>
                          )}
                          {(job.officialWebsite || job.careersPage || job.companySearchStatus) && (
                            <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                              {job.companySearchStatus && <span>公司查找：{job.companySearchStatus}</span>}
                              {job.officialWebsite && (
                                <a
                                  href={job.officialWebsite}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  className="text-sky-600 hover:text-sky-700 inline-flex items-center gap-0.5"
                                  onClick={(e) => e.stopPropagation()}
                                >
                                  官网 <ExternalLink className="h-3 w-3" />
                                </a>
                              )}
                              {job.careersPage && (
                                <a
                                  href={job.careersPage}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  className="text-sky-600 hover:text-sky-700 inline-flex items-center gap-0.5"
                                  onClick={(e) => e.stopPropagation()}
                                >
                                  招聘页 <ExternalLink className="h-3 w-3" />
                                </a>
                              )}
                            </div>
                          )}
                          <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                            {job.source && (
                              <span className="inline-block text-xs px-1.5 py-0.5 rounded bg-muted text-muted-foreground">{job.source}</span>
                            )}
                            {job.checkedAt && (
                              <span className="text-xs text-muted-foreground">验证于 {job.checkedAt}</span>
                            )}
                            {job.url && (
                              <a
                                href={job.url}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="text-xs text-sky-600 hover:text-sky-700 inline-flex items-center gap-0.5"
                                onClick={(e) => e.stopPropagation()}
                              >
                                查看链接 <ExternalLink className="h-3 w-3" />
                              </a>
                            )}
                          </div>
                        </div>
                        {record && (
                          <div className="shrink-0 mt-0.5">
                            {record.status === 'applying' && (
                              <span className="inline-flex items-center gap-1 text-xs text-blue-600">
                                <Loader2 className="h-3 w-3 animate-spin" /> 提交中
                              </span>
                            )}
                            {record.status === 'running' && (
                              <span className="inline-flex items-center gap-1 text-xs text-blue-600">
                                <Loader2 className="h-3 w-3 animate-spin" /> 执行中
                              </span>
                            )}
                            {record.status === 'submitted' && (
                              <span className="inline-flex items-center gap-1 text-xs text-emerald-600">
                                <CheckCircle2 className="h-3 w-3" /> 已完成
                              </span>
                            )}
                            {record.status === 'waiting_confirm' && (
                              <span className="inline-flex items-center gap-1 text-xs text-amber-600">
                                <Clock className="h-3 w-3" /> 等待确认
                              </span>
                            )}
                            {record.status === 'failed' && (
                              <span className="inline-flex items-center gap-1 text-xs text-red-600" title={record.error}>
                                <XCircle className="h-3 w-3" /> 失败
                              </span>
                            )}
                            {(record.status === 'running' || record.status === 'submitted' || record.status === 'failed') && (
                              <div className="flex items-center gap-1.5 mt-1">
                                {record.appUrl && (
                                  <a href={record.appUrl} target="_blank" rel="noopener noreferrer"
                                    className="text-xs text-sky-600 hover:text-sky-700 underline" onClick={(e) => e.stopPropagation()}>
                                    查看任务详情
                                  </a>
                                )}
                                {record.recordingUrl && (
                                  <a href={record.recordingUrl} target="_blank" rel="noopener noreferrer"
                                    className="text-xs text-sky-600 hover:text-sky-700 underline" onClick={(e) => e.stopPropagation()}>
                                    查看录屏
                                  </a>
                                )}
                                {record.runStatusUrl && (
                                  <a href={record.runStatusUrl} target="_blank" rel="noopener noreferrer"
                                    className="text-xs text-sky-600 hover:text-sky-700 underline" onClick={(e) => e.stopPropagation()}>
                                    查看任务接口
                                  </a>
                                )}
                                {record.browserDebugUrl && (
                                  <a href={record.browserDebugUrl} target="_blank" rel="noopener noreferrer"
                                    className="text-xs text-sky-600 hover:text-sky-700 underline" onClick={(e) => e.stopPropagation()}>
                                    查看浏览器
                                  </a>
                                )}
                                {record.screenshotUrls && record.screenshotUrls.length > 0 && (
                                  <a href={record.screenshotUrls[0]} target="_blank" rel="noopener noreferrer"
                                    className="text-xs text-sky-600 hover:text-sky-700 underline" onClick={(e) => e.stopPropagation()}>
                                    查看截图
                                  </a>
                                )}
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    )
                  })}
                </div>
              </CardContent>
            </Card>
          )}
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-xl">
              <Clock className="h-5 w-5 text-sky-500" />
              运行状态
            </CardTitle>
            <CardDescription>Skyvern 当前任务</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className={`flex items-center justify-between rounded-md border px-4 py-3 ${statusTone}`}>
              <span className="text-sm font-medium">{run?.status || '未开始'}</span>
              {polling && <RefreshCw className="h-4 w-4 animate-spin" />}
              {run?.status === 'completed' && <CheckCircle2 className="h-4 w-4" />}
              {run?.status && terminalStatuses.has(run.status) && run.status !== 'completed' && <XCircle className="h-4 w-4" />}
            </div>

            {message && <div className="rounded-md border border-emerald-500/20 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-700">{message}</div>}
            {error && <div className="rounded-md border border-red-500/20 bg-red-500/10 px-4 py-3 text-sm text-red-700 break-words">{error}</div>}

            {jobRecords.length > 0 && (
              <div className="space-y-1.5">
                <div className="text-sm font-medium text-muted-foreground">投递进度</div>
                {jobRecords.map((record) => (
                  <div key={record.job.id} className="flex items-center justify-between text-sm py-1">
                    <span className="truncate mr-2">{record.job.company} - {record.job.title}</span>
                    {record.status === 'pending' && <span className="text-xs text-muted-foreground shrink-0">等待中</span>}
                    {record.status === 'applying' && (
                      <span className="text-xs text-blue-600 inline-flex items-center gap-1 shrink-0">
                        <Loader2 className="h-3 w-3 animate-spin" /> 提交中
                      </span>
                    )}
                    {record.status === 'running' && (
                      <span className="text-xs text-blue-600 inline-flex items-center gap-1 shrink-0">
                        <Loader2 className="h-3 w-3 animate-spin" /> 执行中
                      </span>
                    )}
                    {record.status === 'submitted' && (
                      <span className="text-xs text-emerald-600 inline-flex items-center gap-1 shrink-0">
                        <CheckCircle2 className="h-3 w-3" /> 已完成
                      </span>
                    )}
                    {record.status === 'waiting_confirm' && (
                      <span className="text-xs text-amber-600 inline-flex items-center gap-1 shrink-0">
                        <Clock className="h-3 w-3" /> 等待确认
                      </span>
                    )}
                    {record.status === 'failed' && (
                      <span className="text-xs text-red-600 shrink-0" title={record.error}>失败</span>
                    )}
                  </div>
                ))}
              </div>
            )}

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <div className="text-sm font-medium text-muted-foreground">最近投递记录</div>
                <Button type="button" variant="ghost" size="sm" onClick={() => void loadApplicationHistory()} className="h-7 px-2 text-xs">
                  <RefreshCw className="h-3.5 w-3.5 mr-1" />
                  刷新
                </Button>
              </div>
              {historyMessage && (
                <div className="rounded-md border border-amber-500/20 bg-amber-500/10 px-3 py-2 text-xs text-amber-700">
                  {historyMessage}
                </div>
              )}
              {applicationHistory.length > 0 ? (
                <div className="max-h-52 overflow-auto rounded-md border border-border/60 divide-y divide-border/60 bg-white/30 dark:bg-white/5">
                  {applicationHistory.slice(0, 8).map((item) => (
                    <div key={item.id || item.url} className="px-3 py-2 text-xs">
                      <div className="flex items-center justify-between gap-2">
                        <span className="min-w-0 truncate font-medium">{item.company || '未知公司'} - {item.title || '未知岗位'}</span>
                        <div className="flex shrink-0 items-center gap-1.5">
                          <span className="text-muted-foreground">{historyStatusLabel(item.status)}</span>
                          <button
                            type="button"
                            className="text-muted-foreground hover:text-red-600"
                            onClick={() => void deleteApplicationHistory(item)}
                            title="删除记录"
                          >
                            <XCircle className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      </div>
                      <div className="mt-1 flex items-center justify-between gap-2 text-muted-foreground">
                        <span className="truncate">{item.updatedAt || item.createdAt || '-'}</span>
                        {item.url && (
                          <a href={item.url} target="_blank" rel="noopener noreferrer" className="shrink-0 text-sky-600 hover:text-sky-700">
                            岗位链接
                          </a>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-xs text-muted-foreground">暂无本地投递记录。</div>
              )}
            </div>

            <div className="space-y-2 text-sm">
              <div className="flex items-center justify-between gap-3">
                <span className="text-muted-foreground">Run ID</span>
                <span className="font-mono text-xs break-all text-right">{run?.run_id || '-'}</span>
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-muted-foreground">简历上传</span>
                <span>{run?.resumeUploaded ? '已上传' : '-'}</span>
              </div>
              {run?.app_url && (
                <Link
                  href={run.app_url}
                  target="_blank"
                  className="inline-flex items-center gap-2 text-sm text-sky-600 hover:text-sky-700"
                >
                  <ExternalLink className="h-4 w-4" />
                  打开 Skyvern 详情
                </Link>
              )}
              {run?.recording_url && (
                <Link
                  href={run.recording_url}
                  target="_blank"
                  className="inline-flex items-center gap-2 text-sm text-sky-600 hover:text-sky-700"
                >
                  <ExternalLink className="h-4 w-4" />
                  查看录屏
                </Link>
              )}
              {run?.runStatusUrl && (
                <Link
                  href={run.runStatusUrl}
                  target="_blank"
                  className="inline-flex items-center gap-2 text-sm text-sky-600 hover:text-sky-700"
                >
                  <ExternalLink className="h-4 w-4" />
                  查看任务接口
                </Link>
              )}
              {run?.browserDebugUrl && (
                <Link
                  href={run.browserDebugUrl}
                  target="_blank"
                  className="inline-flex items-center gap-2 text-sm text-sky-600 hover:text-sky-700"
                >
                  <ExternalLink className="h-4 w-4" />
                  查看浏览器
                </Link>
              )}
              {run?.browserHint && (
                <div className="rounded-md border border-sky-500/20 bg-sky-500/10 px-3 py-2 text-xs text-sky-700">
                  {run.browserHint}
                </div>
              )}
            </div>

            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => void refreshRun()}
                disabled={!run?.run_id}
              >
                <RefreshCw className="h-4 w-4" />
                刷新
              </Button>
              <Button
                type="button"
                variant="destructive"
                size="sm"
                onClick={() => void cancelRun()}
                disabled={!run?.run_id || isTerminal}
              >
                <CircleStop className="h-4 w-4" />
                停止
              </Button>
            </div>

            {run?.failure_reason && (
              <div className="rounded-md border border-red-500/20 bg-red-500/10 p-4 text-sm text-red-700 break-words">
                {run.failure_reason}
              </div>
            )}

            {outputText && (
              <pre className="max-h-96 overflow-auto rounded-md border border-white/20 bg-black/80 p-4 text-xs text-white whitespace-pre-wrap">
                {outputText}
              </pre>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
