package cn.xunlian;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * 轻量中文处理：时间意图槽位解析 + 复盘错因抽取。
 * 只依赖词典与规则，不引入分词库，保证离线可用、结果可解释、可单测。
 */
public final class Nlp {
    private Nlp() {}

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String WEEKDAY_CHARS = "一二三四五六日";
    private static final String[] WEEKDAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 时间意图：day -> 分钟；0 表示当天休息。 */
    public record TimeIntent(Map<Integer, Integer> overrides, List<String> notes, boolean matched) {
        public static TimeIntent empty() { return new TimeIntent(Map.of(), List.of(), false); }
    }

    /** 一条错因及其可信度（1.0 为常规表述，1.6 为“又/总是”，0.7 为“有点/稍微”）。 */
    public record Cause(String tag, double weight, String phrase) {}

    // ---------------------------------------------------------------- 时间意图

    public static TimeIntent timeIntent(String text) {
        return timeIntent(text, LocalDate.now(ZONE));
    }

    public static TimeIntent timeIntent(String text, LocalDate today) {
        if (text == null || text.isBlank()) return TimeIntent.empty();
        String s = normalize(text);
        Map<Integer, Integer> overrides = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        boolean daily = s.matches("(?s).*(每天|天天|每日).*");
        int dailyMinutes = duration(s);
        if (daily && dailyMinutes > 0) {
            for (int day = 0; day < 7; day++) overrides.put(day, dailyMinutes);
            notes.add("每天 " + dailyMinutes + " 分钟");
            return new TimeIntent(overrides, notes, true);
        }

        // “只有周三有空” → 其余日期让出时间
        Matcher only = Pattern.compile("只有(?:周|星期|礼拜)([一二三四五六日天])(?:才)?(?:有|能|可以)").matcher(s);
        if (only.find()) {
            int keep = weekdayIndex(only.group(1));
            for (int day = 0; day < 7; day++) if (day != keep) overrides.put(day, 0);
            notes.add("只保留" + WEEKDAY_LABELS[keep]);
        }

        Matcher matcher = Pattern.compile("(?:周|星期|礼拜)([一二三四五六日天])").matcher(s);
        boolean anyWeekday = false;
        while (matcher.find()) {
            anyWeekday = true;
            int day = weekdayIndex(matcher.group(1));
            String tail = segment(s, matcher.end());
            if (isRest(tail)) {
                overrides.put(day, 0);
                notes.add(WEEKDAY_LABELS[day] + "休息");
            } else {
                int minutes = duration(tail);
                if (minutes > 0) {
                    overrides.put(day, minutes);
                    notes.add(WEEKDAY_LABELS[day] + " " + minutes + " 分钟");
                }
            }
        }

        int weekend = duration(windowOf(s, "周末"));
        if (weekend > 0 && !anyWeekday && (s.contains("周末") || s.contains("双休"))) {
            overrides.put(5, weekend);
            overrides.put(6, weekend);
            notes.add("周末各 " + weekend + " 分钟");
        }

        Matcher relative = Pattern.compile("(今天|今晚|明天|明晚|后天|大后天)").matcher(s);
        if (relative.find()) {
            int day = today.getDayOfWeek().getValue() - 1;
            if (relative.group(1).startsWith("明")) day = (day + 1) % 7;
            else if (relative.group(1).startsWith("后")) day = (day + 2) % 7;
            else if (relative.group(1).startsWith("大后")) day = (day + 3) % 7;
            int minutes = duration(window(s, relative.start(), relative.end()));
            if (minutes > 0) {
                overrides.put(day, minutes);
                notes.add(WEEKDAY_LABELS[day] + " " + minutes + " 分钟");
            }
        }

        if ((s.contains("隔天") || s.contains("隔日")) && !overrides.isEmpty()) {
            int minutes = duration(s);
            if (minutes > 0) {
                for (int day = 0; day < 7; day += 2) overrides.put(day, minutes);
                notes.clear();
                notes.add("隔天一次，每次 " + minutes + " 分钟");
            }
        }

        // 整周否定：这周没时间 / 本周不能练
        if (overrides.isEmpty() && s.matches("(?s).*(这周|本周|这星期).*(没时间|没空|不能|不练|考试|忙).*")) {
            for (int day = 0; day < 7; day++) overrides.put(day, 0);
            notes.add("本周暂停训练");
        }

        return new TimeIntent(overrides, notes, !overrides.isEmpty());
    }

    public static String echo(TimeIntent intent) {
        return intent.matched() ? String.join("；", intent.notes()) : "";
    }

    // ---------------------------------------------------------------- 错因抽取

    private static final Map<String, List<String>> CAUSE_LEXICON = new LinkedHashMap<>();
    static {
        CAUSE_LEXICON.put("边界", List.of("边界", "越界", "开区间", "闭区间", "端点", "等号", "最后一组", "多组数据", "off-by-one"));
        CAUSE_LEXICON.put("复杂度", List.of("超时", "tle", "复杂度", "剪枝", "常数", "卡时限", "跑不过", "n方"));
        CAUSE_LEXICON.put("审题", List.of("题意", "读错", "看漏", "没看清", "范围", "输入格式", "理解偏"));
        CAUSE_LEXICON.put("实现", List.of("手误", "初始化", "未初始化", "打错", "写错", "变量名", "细节", "下标"));
        CAUSE_LEXICON.put("算法", List.of("不会", "没思路", "想不到", "没见过", "模板", "题解", "正解"));
        CAUSE_LEXICON.put("时间", List.of("时间不够", "来不及", "赶时间", "太慢", "没时间写"));
    }

    private static final List<String> NEGATIONS = List.of("不是", "没有", "并非", "不算", "不全是");
    private static final List<String> INTENSIFIERS = List.of("又", "还是", "总是", "经常", "老是", "反复");
    private static final List<String> SOFTENERS = List.of("有点", "稍微", "偶尔", "一次");

    public static List<Cause> causes(String note) {
        if (note == null || note.isBlank()) return List.of();
        String s = normalize(note).toLowerCase(Locale.ROOT);
        Map<String, Cause> found = new LinkedHashMap<>();
        CAUSE_LEXICON.forEach((tag, words) -> {
            for (String word : words) {
                int at = s.indexOf(word);
                while (at >= 0) {
                    String before = clauseBefore(s, at);
                    if (NEGATIONS.stream().noneMatch(before::contains)) {
                        double weight = 1.0;
                        if (INTENSIFIERS.stream().anyMatch(before::contains)) weight = 1.6;
                        else if (SOFTENERS.stream().anyMatch(before::contains)) weight = 0.7;
                        Cause current = found.get(tag);
                        if (current == null || current.weight() < weight) {
                            found.put(tag, new Cause(tag, weight, word));
                        }
                    }
                    at = s.indexOf(word, at + word.length());
                }
            }
        });
        return found.values().stream()
                .sorted(Comparator.comparingDouble(Cause::weight).reversed())
                .toList();
    }

    /** 取同一小句内、命中词之前最多 8 个字符，避免否定词跨句误伤。 */
    private static String clauseBefore(String text, int at) {
        String before = text.substring(0, at);
        int cut = -1;
        for (char stop : new char[]{'，', '。', '；', '、', ',', ';', '！', '!'}) {
            cut = Math.max(cut, before.lastIndexOf(stop));
        }
        if (cut >= 0) before = before.substring(cut + 1);
        return before.length() <= 8 ? before : before.substring(before.length() - 8);
    }

    // ---------------------------------------------------------------- 工具

    public static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            if (ch >= '０' && ch <= '９') out.append((char) (ch - '０' + '0'));
            else if (ch == '　') out.append(' ');
            else out.append(ch);
        }
        return out.toString().trim();
    }

    private static int weekdayIndex(String ch) {
        int index = WEEKDAY_CHARS.indexOf(ch);
        return index < 0 ? 0 : index;
    }

    private static String window(String s, int start, int end) {
        int from = Math.max(0, start - 8);
        int to = Math.min(s.length(), end + 16);
        return s.substring(from, to);
    }

    /** 取星期词之后、到标点或下一个星期词为止的片段，避免把相邻日期的安排算进来。 */
    private static String segment(String s, int from) {
        String tail = s.substring(from, Math.min(s.length(), from + 16));
        int cut = -1;
        for (char stop : new char[]{'，', '。', '；', ',', ';', '、', '！', '!'}) {
            int at = tail.indexOf(stop);
            if (at >= 0 && (cut < 0 || at < cut)) cut = at;
        }
        Matcher next = Pattern.compile("(?:周|星期|礼拜)[一二三四五六日天]").matcher(tail);
        if (next.find() && (cut < 0 || next.start() < cut)) cut = next.start();
        return cut >= 0 ? tail.substring(0, cut) : tail;
    }

    private static String windowOf(String s, String keyword) {
        int at = s.indexOf(keyword);
        return at < 0 ? s : window(s, at, at + keyword.length());
    }

    private static boolean isRest(String window) {
        return window.matches("(?s).*(没时间|没空|不能|不练|不训|考试|加班|有课|外出|忙|休).*") && duration(window) <= 0;
    }

    /** 从文本里解析一段时长，返回分钟；解析不到返回 0。 */
    public static int duration(String text) {
        if (text == null) return 0;
        String s = normalize(text);
        Matcher special = Pattern.compile("(一个半|一个半小时|一个半钟头|半小时|半个钟头|半个小时|一刻钟|两刻钟)").matcher(s);
        if (special.find()) {
            String hit = special.group(1);
            if (hit.startsWith("一个半") || hit.startsWith("一小")) return 90;
            if (hit.equals("一刻钟")) return 15;
            if (hit.equals("两刻钟")) return 30;
            return 30;
        }
        Matcher hours = Pattern.compile("([0-9]{1,2}|[一二三四五六七八九十两]{1,3})\\s*(?:个)?\\s*(?:小时|钟头|h)").matcher(s);
        if (hours.find()) {
            int value = number(hours.group(1));
            if (value > 0) return Math.min(600, value * 60);
        }
        Matcher minutes = Pattern.compile("([0-9]{1,3}|[一二三四五六七八九十两]{1,3})\\s*(?:分钟|分)").matcher(s);
        if (minutes.find()) {
            int value = number(minutes.group(1));
            if (value > 0) return Math.min(600, value);
        }
        return 0;
    }

    private static int number(String token) {
        if (token.matches("[0-9]+")) return Integer.parseInt(token);
        String t = token.replace("两", "二");
        int ten = t.indexOf('十');
        if (ten >= 0) {
            int before = ten == 0 ? 1 : digit(t.charAt(0));
            int after = ten == t.length() - 1 ? 0 : digit(t.charAt(ten + 1));
            return before * 10 + after;
        }
        if (t.length() == 1) return digit(t.charAt(0));
        return 0;
    }

    private static int digit(char ch) {
        return switch (ch) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }
}
