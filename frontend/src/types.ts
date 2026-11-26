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
  sourceTags: string[]
  minLevel: number
  maxLevel: number
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
  handle: string
  mode: string
  pages: number
  readCount: number
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
    fullHistory: boolean
    lastFullSyncAt: string | null
    lastSubmissionId: number
  }
  learningHints: Record<string, number>
  assessments: Assessment[]
  challenges: ChallengeResult[]
}

export interface AssessmentItem {
  questionId: string
  topic: string
  chosen: number
  answer: number
  correct: boolean
  hintLevel: number
  explanation: string
}
export interface Assessment {
  id: string
  route: string
  correct: number
  total: number
  createdAt: string
  items: AssessmentItem[]
  suggestedTopics: string[]
}
export interface ChallengeResult {
  id: string
  challengeId: string
  numbers: number[]
  target: number | null
  buggyOutput: string
  referenceOutput: string
  counterexample: boolean
  correctFix: boolean
  explanation: string
  createdAt: string
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
  topics: Topic[]
  catalogTotal: number
  cfSolvedIds: string[]
}

export interface Topic {
  name: string
  category: string
  summary: string
  prerequisites: string[]
  steps: string[]
  examples: string[]
}
export interface ProblemRow {
  problem: Problem
  completion: string
  firstAcceptedAt: number | null
  lastAcceptedAt: number | null
  acceptedSubmissions: number
}
export interface ProblemPage {
  items: ProblemRow[]
  total: number
  page: number
  pageSize: number
  pages: number
}
