package cn.xunlian;

import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() {}

    public static class Profile {
        public String name = "新同学";
        public String handle = "";
        public String competitionDate = "";
        public String achievements = "";
        public String goal = "巩固算法基础，形成稳定训练节奏";
        public List<String> topics = new ArrayList<>(List.of("二分", "搜索"));
        public int level = 1000;
        public List<Integer> dailyMinutes = new ArrayList<>(List.of(45, 60, 45, 60, 60, 90, 0));
    }

    public static class Problem {
        public String id;
        public String platform;
        public String code;
        public String title;
        public String url;
        public List<String> tags = new ArrayList<>();
        public Integer rating;
        public String difficulty;
        public int minutes = 40;
        public String provenance = "人工精选";
    }

    public static class Task {
        public String id;
        public String problemId;
        public int day;
        public int minutes;
        public String status = "todo";
        public String reason;
    }

    public static class Plan {
        public String weekStart;
        public List<Integer> dailyMinutes = new ArrayList<>();
        public int version = 1;
        public String engine = "rules";
        public String explanation = "根据目标专题、题目难度和可用时间安排训练。";
        /** 计划所依据的记忆条目或画像结论，逐条可在界面上核对。 */
        public List<String> basis = new ArrayList<>();
        /** 本次对自然语言时间要求的解析回显，例如「周六 30 分钟；周四休息」。 */
        public String timeEcho = "";
        public List<Task> tasks = new ArrayList<>();
    }

    public static class Feedback {
        public String id;
        public String taskId;
        public String problemId;
        public String outcome;
        public int minutes;
        public String note;
        public String createdAt;
        /** 由复盘文字抽取出的错因标签，用于画像与记忆。 */
        public List<String> causes = new ArrayList<>();
    }

    public static class Proposal {
        public String id;
        public int baseVersion;
        public Plan plan;
        public String reason;
        public String engine;
        public String modelMessage;
        public List<String> basis = new ArrayList<>();
    }

    public static class SyncInfo {
        public String handle = "";
        public String syncedAt;
        public int submissionCount;
        public int solvedCount;
        public boolean truncated;
        public Long coverageStartsAt;
        public Long fetchStartedAt;
        public Integer rating;
    }

    public static class Workspace {
        public Profile profile = new Profile();
        public Plan plan;
        public List<Feedback> feedback = new ArrayList<>();
        public List<Plan> history = new ArrayList<>();
        public Proposal proposal;
        public SyncInfo sync = new SyncInfo();
        public AssessmentService.Assessment assessment;
        public List<AssessmentService.Assessment> assessmentHistory = new ArrayList<>();
    }

    // ---------------------------------------------------------------- 教练记忆

    public static class Observation {
        public String id;
        /** goal 有效目标 / error_pattern 易错点 / code_issue 代码问题 / strength 优势 / constraint 约束 */
        public String kind;
        public String statement;
        public String detail;
        /** feedback / review / profile / sync / manual */
        public String sourceType;
        public String sourceId;
        public double weight = 1;
        public boolean active = true;
        public String createdAt;
        public String updatedAt;
    }

    public static class Event {
        public String id;
        public String type;
        public String summary;
        public String createdAt;
    }

    // ---------------------------------------------------------------- 能力画像

    public static class Evidence {
        public String problemId;
        public String code;
        public String title;
        public String outcome;
        public int minutes;
        public int estimate;
        public String date;
        public List<String> tags = new ArrayList<>();
    }

    public static class TopicStat {
        public String name;
        /** 1～5 级；样本不足时为 null。 */
        public Integer level;
        public int samples;
        public int independent;
        public int editorial;
        public int stuck;
        public double independentRate;
        public Integer avgRating;
        public int avgMinutes;
        public int avgEstimate;
        /** 实际用时 − 排期估计，正数表示比预估更慢。 */
        public int bias;
        public List<Evidence> evidence = new ArrayList<>();
    }

    public static class BucketStat {
        public String label;
        public Integer minRating;
        public Integer maxRating;
        public int attempts;
        public int independent;
        public double rate;
    }

    public record PlatformCoverage(String topic, int attempted, int accepted) {}

    public record TimedEvidence(String problemId, String title, String status, int submissions,
                                Long firstAcceptedSeconds, long assessedAt) {}

    public record AbilityArea(String name, List<String> topics, Integer level, int samples,
                              int independent, int codeforcesAttempted, int codeforcesAccepted) {}

    public static class ProfileModel {
        public List<AbilityArea> abilityAreas = new ArrayList<>();
        public List<PlatformCoverage> codeforcesCoverage = new ArrayList<>();
        public boolean codeforcesCoverageTruncated;
        public List<TimedEvidence> timedEvidence = new ArrayList<>();
        public List<TopicStat> topics = new ArrayList<>();
        public List<BucketStat> buckets = new ArrayList<>();
        public int samples;
        public double independentRate;
        public int trainedMinutes;
        public List<String> strengths = new ArrayList<>();
        public List<String> weaknesses = new ArrayList<>();
        public String summary = "";
        public String engine = "rules";
        public List<String> basis = new ArrayList<>();
    }

    // ---------------------------------------------------------------- 代码审查

    public static class SourceFile {
        public String name;
        public String content;
    }

    public static class ReviewedFile {
        public String name;
        public int lines;
        public int chars;

        public ReviewedFile() {}

        public ReviewedFile(String name, int lines, int chars) {
            this.name = name;
            this.lines = lines;
            this.chars = chars;
        }
    }

    public static class Finding {
        public String file;
        public int line;
        /** high / medium / low */
        public String severity = "low";
        public String kind;
        public String message;
        public String suggestion;
        public String excerpt;
    }

    public static class CodeReview {
        public String id;
        public String taskId;
        public String problemId;
        public String problemTitle;
        public String engine = "rules";
        public String createdAt;
        public List<ReviewedFile> files = new ArrayList<>();
        public List<Finding> findings = new ArrayList<>();
        public String summary = "";
        public int high;
        public int medium;
        public int low;
    }

    public record Account(long id, String username, String passwordHash, boolean demo) {}
    public record Snapshot(long revision, Workspace workspace) {}
    public record Credentials(String username, String password) {}
    public record ProfileRequest(long revision, Profile profile) {}
    public record RevisionRequest(long revision) {}
    public record FeedbackRequest(long revision, String taskId, String outcome, int minutes, String note) {}
    public record AdjustRequest(long revision, String instruction) {}
    public record AcceptRequest(long revision, String proposalId) {}
    public record Submission(long id, String problemId, String verdict, long createdAt) {}
    public record CodeReviewRequest(String problemId, String taskId, List<SourceFile> files) {}
    public record MemoryRequest(String kind, String statement, String detail) {}
    public record MemoryCorrection(String statement, Boolean active) {}
}
