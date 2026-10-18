package asia.jppwl.scheduler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.CronPreviewResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AwsCronEvaluatorTest {

    private final AwsCronEvaluator evaluator = new AwsCronEvaluator();

    @Test
    void shouldEvaluateAllFiveProductionSchedulesCorrectly() {
        Instant ref = Instant.parse("2026-10-18T08:05:00Z"); // Sunday

        // 1. test-sms-flink-cron: rate(10 minutes)
        List<Instant> rateNext = evaluator.computeNextFireTimes("rate(10 minutes)", "UTC", ref, 3);
        assertThat(rateNext).containsExactly(
                Instant.parse("2026-10-18T08:10:00Z"),
                Instant.parse("2026-10-18T08:20:00Z"),
                Instant.parse("2026-10-18T08:30:00Z")
        );
        assertThat(evaluator.toHumanReadable("rate(10 minutes)", "UTC"))
                .isEqualTo("Every 10 minutes (UTC)");
        assertThat(evaluator.toHumanReadable("rate(1 hour)", "UTC"))
                .isEqualTo("Every 1 hour (UTC)");
        assertThat(evaluator.toHumanReadable("rate(1 days)", "UTC"))
                .isEqualTo("Every 1 day (UTC)");

        // 2. sms-flink-report-daily: cron(50 23 * * ? *) Asia/Shanghai (15:50 UTC)
        List<Instant> dailyNext = evaluator.computeNextFireTimes("cron(50 23 * * ? *)", "Asia/Shanghai", ref, 2);
        assertThat(dailyNext).containsExactly(
                Instant.parse("2026-10-18T15:50:00Z"),
                Instant.parse("2026-10-19T15:50:00Z")
        );
        assertThat(evaluator.toHumanReadable("cron(50 23 * * ? *)", "Asia/Shanghai"))
                .isEqualTo("Every day at 23:50 (Asia/Shanghai)");

        // 3. sms-flink-report-weekly: cron(0 9 ? * MON *) Asia/Shanghai (01:00 UTC Monday)
        List<Instant> weeklyNext = evaluator.computeNextFireTimes("cron(0 9 ? * MON *)", "Asia/Shanghai", ref, 2);
        assertThat(weeklyNext).containsExactly(
                Instant.parse("2026-10-19T01:00:00Z"),
                Instant.parse("2026-10-26T01:00:00Z")
        );
        assertThat(evaluator.toHumanReadable("cron(0 9 ? * MON *)", "Asia/Shanghai"))
                .isEqualTo("Every Monday at 09:00 (Asia/Shanghai)");

        // 4. sms-flink-report-monthly: cron(30 9 1 * ? *) Asia/Shanghai
        List<Instant> monthlyNext = evaluator.computeNextFireTimes("cron(30 9 1 * ? *)", "Asia/Shanghai", ref, 2);
        assertThat(monthlyNext).containsExactly(
                Instant.parse("2026-11-01T01:30:00Z"),
                Instant.parse("2026-12-01T01:30:00Z")
        );
        assertThat(evaluator.toHumanReadable("cron(30 9 1 * ? *)", "Asia/Shanghai"))
                .isEqualTo("On day 1 of every month at 09:30 (Asia/Shanghai)");

        // 5. jppwl-tls-cert-renew-weekly: cron(0 4 ? * SUN *) Asia/Shanghai (2026-10-25 04:00 CST = 2026-10-24 20:00 UTC)
        List<Instant> sunNext = evaluator.computeNextFireTimes("cron(0 4 ? * SUN *)", "Asia/Shanghai", ref, 1);
        assertThat(sunNext).containsExactly(Instant.parse("2026-10-24T20:00:00Z"));
    }

    @Test
    void shouldSupportStepRangesLastDayOfMonthAndPreviousFireTime() {
        Instant ref = Instant.parse("2026-10-18T15:52:00Z");

        Instant prevDaily = evaluator.computePreviousFireTime("cron(50 23 * * ? *)", "Asia/Shanghai", ref);
        assertThat(prevDaily).isEqualTo(Instant.parse("2026-10-18T15:50:00Z"));

        Instant prevRate = evaluator.computePreviousFireTime("rate(10 minutes)", "UTC", ref);
        assertThat(prevRate).isEqualTo(Instant.parse("2026-10-18T15:50:00Z"));

        // Multi-hour list & step syntax: cron(0 0,4,8,12,16,20 * * ? *)
        String multiHour = "cron(0 0,4,8,12,16,20 * * ? *)";
        assertThat(evaluator.toHumanReadable(multiHour, "Asia/Shanghai"))
                .contains("At minute 0 of hours [0,4,8,12,16,20]");

        // Range and step syntax + Last day of month 'L'
        List<Instant> lastDomNext = evaluator.computeNextFireTimes("cron(0/30 8-10 L JAN-DEC ? 2026-2027)", "UTC", ref, 2);
        assertThat(lastDomNext).containsExactly(
                Instant.parse("2026-10-31T08:00:00Z"),
                Instant.parse("2026-10-31T08:30:00Z")
        );

        // Numeric day-of-week label
        assertThat(evaluator.toHumanReadable("cron(0 9 ? * 2 *)", "UTC"))
                .isEqualTo("Every Monday at 09:00 (UTC)");
    }

    @Test
    void shouldValidateAndPreviewValidAndInvalidExpressions() {
        Instant ref = Instant.parse("2026-10-18T08:00:00Z");
        CronPreviewResponse validPreview = evaluator.preview("cron(50 23 * * ? *)", "Asia/Shanghai", ref);
        assertThat(validPreview.valid()).isTrue();
        assertThat(validPreview.upcomingFireTimes()).hasSize(5);
        assertThat(validPreview.errorMessage()).isNull();

        CronPreviewResponse invalidPreview = evaluator.preview("cron(50 23 * * * *)", "Asia/Shanghai", ref);
        assertThat(invalidPreview.valid()).isFalse();
        assertThat(invalidPreview.errorMessage()).contains("requires either Day-of-Month or Day-of-Week to be '?'");

        assertThatThrownBy(() -> evaluator.validateOrThrow("cron(0 0 ? * ? *)", "UTC"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot have '?' in both");

        assertThatThrownBy(() -> evaluator.validateOrThrow("rate(0 minutes)", "UTC"))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> evaluator.validateOrThrow("invalid-expr", "UTC"))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> evaluator.validateOrThrow("cron(99 23 * * ? *)", "UTC"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("out of range");

        assertThatThrownBy(() -> evaluator.validateOrThrow("cron(10-5 23 * * ? *)", "UTC"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid cron range");

        assertThatThrownBy(() -> evaluator.validateOrThrow("cron(abc 23 * * ? *)", "UTC"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid cron token");

        assertThatThrownBy(() -> evaluator.parseZoneId("Invalid/Timezone"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid IANA timezone");
    }
}
