package cn.xunlian;

import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() {}

    public static class Profile {
        public String name = "新同学";
        public String handle = "";
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
    }

    public static class Proposal {
        public String id;
        public int baseVersion;
        public Plan plan;
        public String reason;
        public String engine;
        public String modelMessage;
    }

    public static class SyncInfo {
        public String handle = "";
        public String syncedAt;
        public int submissionCount;
        public int solvedCount;
        public boolean truncated;
        public Integer rating;
    }

    public static class Workspace {
        public Profile profile = new Profile();
        public Plan plan;
        public List<Feedback> feedback = new ArrayList<>();
        public List<Plan> history = new ArrayList<>();
        public Proposal proposal;
        public SyncInfo sync = new SyncInfo();
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
}
