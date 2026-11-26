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
}
export interface Proposal {
  id: string
  baseVersion: number
  plan: Plan
  reason: string
  engine: string
  modelMessage: string
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
  syncJob: SyncJob
}
