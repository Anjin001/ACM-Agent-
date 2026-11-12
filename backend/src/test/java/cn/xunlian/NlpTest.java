package cn.xunlian;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NlpTest {

    @Test
    void parsesChineseDurations() {
        assertThat(Nlp.duration("半小时")).isEqualTo(30);
        assertThat(Nlp.duration("一个半小时")).isEqualTo(90);
        assertThat(Nlp.duration("两小时")).isEqualTo(120);
        assertThat(Nlp.duration("四十分钟")).isEqualTo(40);
        assertThat(Nlp.duration("二十五分钟")).isEqualTo(25);
        assertThat(Nlp.duration("一刻钟")).isEqualTo(15);
        assertThat(Nlp.duration("９０分钟")).isEqualTo(90);
        assertThat(Nlp.duration("随便说说")).isZero();
    }

    @Test
    void fillsWeekdaySlotsAndHandlesNegation() {
        LocalDate tuesday = LocalDate.of(2026, 11, 10);
        Nlp.TimeIntent intent = Nlp.timeIntent("这周六抽半小时，周四没时间", tuesday);
        assertThat(intent.matched()).isTrue();
        assertThat(intent.overrides()).containsEntry(5, 30).containsEntry(3, 0);
        assertThat(Nlp.echo(intent)).contains("周六 30 分钟").contains("周四休息");
    }

    @Test
    void supportsDailyWeekendAndRelativeDays() {
        LocalDate tuesday = LocalDate.of(2026, 11, 10);
        assertThat(Nlp.timeIntent("每天30分钟", tuesday).overrides()).hasSize(7);

        Nlp.TimeIntent weekend = Nlp.timeIntent("周末两小时", tuesday);
        assertThat(weekend.overrides()).containsEntry(5, 120).containsEntry(6, 120);

        Nlp.TimeIntent tomorrow = Nlp.timeIntent("明天一小时", tuesday);
        assertThat(tomorrow.overrides()).containsEntry(2, 60);
    }

    @Test
    void extractsCausesAndRespectsNegation() {
        List<String> tags = Nlp.causes("又是二分边界写错，最后还超时了").stream().map(Nlp.Cause::tag).toList();
        assertThat(tags).contains("边界", "复杂度");

        List<String> negated = Nlp.causes("不是边界问题，是确实没思路").stream().map(Nlp.Cause::tag).toList();
        assertThat(negated).contains("算法").doesNotContain("边界");
    }

    @Test
    void weightsIntensifiedCausesHigher() {
        double strong = Nlp.causes("总是边界写错").get(0).weight();
        double soft = Nlp.causes("有点边界问题").get(0).weight();
        assertThat(strong).isGreaterThan(soft);
    }
}
