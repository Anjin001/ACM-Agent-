export interface Profile {
  name: string
  handle: string
  goal: string
  topics: string[]
  level: number
  dailyMinutes: number[]
}
export interface Problem {
  id: string
  platform: 'CF' | 'LG'
  code: string
  title: string
  url: string
  tags: string[]
  rating: number | null
  difficulty: string
  minutes: number
  provenance: string
}
export interface Task {
  id: string
  problemId: string
  day: number
  minutes: number
  status: string
  reason: string
}
export interface Plan {
  weekStart: string
  dailyMinutes: number[]
  version: number
  engine: string
  explanation: string
  basis?: string[]
  timeEcho?: string
  tasks: Task[]
}
export interface Feedback {
  id: string
  taskId: string
  problemId: string
  outcome: string
  minutes: number
  note: string
  createdAt: string
  causes?: string[]
}
export interface Proposal {
  id: string
  baseVersion: number
  plan: Plan
  reason: string
  engine: string
  modelMessage: string
  basis?: string[]
}
export interface SyncJob {
  status: string
  message: string
}
export interface Workspace {
  profile: Profile
  plan: Plan | null
  feedback: Feedback[]
  history: Plan[]
  proposal: Proposal | null
  sync: {
    handle: string
    syncedAt: string | null
    submissionCount: number
    solvedCount: number
    truncated: boolean
    rating: number | null
  }
}
export interface AppState {
  revision: number
  workspace: Workspace
  username: string
  demo: boolean
  problems: Problem[]
  solvedIds: string[]
  aiConfigured: boolean
  aiSettings: { baseUrl: string; model: string; hasLocalKey: boolean; unreadable: boolean }
  syncJob: SyncJob
}

export interface Observation {
  id: string
  kind: 'goal' | 'error_pattern' | 'code_issue' | 'strength' | 'constraint' | string
  statement: string
  detail: string | null
  sourceType: 'feedback' | 'review' | 'profile' | 'sync' | 'manual' | string
  sourceId: string | null
  weight: number
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface MemoryEvent {
  id: string
  type: string
  summary: string
  createdAt: string
}

export interface MemoryPayload {
  observations: Observation[]
  events: MemoryEvent[]
}

export interface Evidence {
  problemId: string
  code: string
  title: string
  outcome: string
  minutes: number
  estimate: number
  date: string
  tags: string[]
}

export interface TopicStat {
  name: string
  level: number | null
  samples: number
  independent: number
  editorial: number
  stuck: number
  independentRate: number
  avgRating: number | null
  avgMinutes: number
  avgEstimate: number
  bias: number
  evidence: Evidence[]
}

export interface BucketStat {
  label: string
  minRating: number | null
  maxRating: number | null
  attempts: number
  independent: number
  rate: number
}

export interface ProfileModel {
  topics: TopicStat[]
  buckets: BucketStat[]
  samples: number
  independentRate: number
  trainedMinutes: number
  strengths: string[]
  weaknesses: string[]
  summary: string
  engine: string
  basis: string[]
}

export interface ReviewedFile {
  name: string
  lines: number
  chars: number
}

export interface ReviewFinding {
  file: string
  line: number
  severity: 'high' | 'medium' | 'low' | string
  kind: string
  message: string
  suggestion: string
  excerpt: string
}

export interface CodeReview {
  id: string
  taskId: string | null
  problemId: string | null
  problemTitle: string | null
  engine: string
  createdAt: string
  files: ReviewedFile[]
  findings: ReviewFinding[]
  summary: string
  high: number
  medium: number
  low: number
}
