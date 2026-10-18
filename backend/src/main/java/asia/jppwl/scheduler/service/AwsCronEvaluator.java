package asia.jppwl.scheduler.service;

import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.CronPreviewResponse;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-reflection, GraalVM Native friendly evaluator for AWS EventBridge Scheduler
 * expressions: {@code rate(value unit)} and 6-field {@code cron(min hour dom month dow year)}.
 */
@ApplicationScoped
public class AwsCronEvaluator {

    private static final Pattern RATE_PATTERN =
            Pattern.compile("^rate\\(\\s*(\\d+)\\s+(minute|minutes|hour|hours|day|days)\\s*\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRON_PATTERN =
            Pattern.compile("^cron\\(\\s*([^\\s]+)\\s+([^\\s]+)\\s+([^\\s]+)\\s+([^\\s]+)\\s+([^\\s]+)\\s+([^\\s]+)\\s*\\)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3),
            Map.entry("APR", 4), Map.entry("MAY", 5), Map.entry("JUN", 6),
            Map.entry("JUL", 7), Map.entry("AUG", 8), Map.entry("SEP", 9),
            Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12)
    );

    private static final Map<String, Integer> DOW_NAMES = Map.of(
            "SUN", 1, "MON", 2, "TUE", 3, "WED", 4, "THU", 5, "FRI", 6, "SAT", 7
    );

    private static final String[] DOW_LABELS = {
            "", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };

    public ZoneId parseZoneId(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception e) {
            throw ApiException.badRequest(40001, "Invalid IANA timezone: " + timezone);
        }
    }

    public CronPreviewResponse preview(String expression, String timezone, Instant referenceNow) {
        try {
            ZoneId zoneId = parseZoneId(timezone);
            String normalizedTz = zoneId.getId();
            List<Instant> nextFive = computeNextFireTimes(expression, normalizedTz, referenceNow, 5);
            String human = toHumanReadable(expression, normalizedTz);
            List<String> isoTimes = nextFive.stream()
                    .map(i -> i.truncatedTo(ChronoUnit.SECONDS).toString())
                    .toList();
            return new CronPreviewResponse(true, human, isoTimes, null);
        } catch (ApiException e) {
            return new CronPreviewResponse(false, null, List.of(), e.getMessage());
        } catch (Exception e) {
            return new CronPreviewResponse(false, null, List.of(), "Invalid schedule expression: " + e.getMessage());
        }
    }

    public void validateOrThrow(String expression, String timezone) {
        parseZoneId(timezone);
        if (expression == null || expression.isBlank()) {
            throw ApiException.badRequest(40001, "Schedule expression must not be blank");
        }
        String trimmed = expression.trim();
        Matcher rateMatcher = RATE_PATTERN.matcher(trimmed);
        if (rateMatcher.matches()) {
            int value = Integer.parseInt(rateMatcher.group(1));
            if (value <= 0) {
                throw ApiException.badRequest(40001, "Rate value must be positive: " + expression);
            }
            return;
        }
        ParsedCron.parse(trimmed);
    }

    public String toHumanReadable(String expression, String timezone) {
        ZoneId zoneId = parseZoneId(timezone);
        String tz = zoneId.getId();
        if (expression == null || expression.isBlank()) {
            return "Unknown schedule";
        }
        String trimmed = expression.trim();
        Matcher rateMatcher = RATE_PATTERN.matcher(trimmed);
        if (rateMatcher.matches()) {
            int val = Integer.parseInt(rateMatcher.group(1));
            String unit = rateMatcher.group(2).toLowerCase(Locale.ROOT);
            if (val == 1 && unit.endsWith("s")) {
                unit = unit.substring(0, unit.length() - 1);
            }
            return "Every " + val + " " + unit + " (" + tz + ")";
        }

        ParsedCron cron = ParsedCron.parse(trimmed);
        String timePart;
        if (cron.rawHour.contains(",") || cron.rawHour.contains("/") || "*".equals(cron.rawHour)) {
            timePart = String.format("At minute %s of hours [%s]", cron.rawMinute, cron.rawHour);
        } else if (cron.rawMinute.matches("\\d+") && cron.rawHour.matches("\\d+")) {
            timePart = String.format("%02d:%02d",
                    Integer.parseInt(cron.rawHour), Integer.parseInt(cron.rawMinute));
        } else {
            timePart = cron.rawHour + ":" + cron.rawMinute;
        }

        if ("*".equals(cron.rawDom) && "?".equals(cron.rawDow) && "*".equals(cron.rawMonth)) {
            if (timePart.startsWith("At ")) {
                return timePart + " every day (" + tz + ")";
            }
            return "Every day at " + timePart + " (" + tz + ")";
        }
        if ("?".equals(cron.rawDom) && !"*".equals(cron.rawDow) && !"?".equals(cron.rawDow)) {
            String dowText = formatDowLabel(cron.rawDow);
            return "Every " + dowText + " at " + timePart + " (" + tz + ")";
        }
        if (!"*".equals(cron.rawDom) && !"?".equals(cron.rawDom) && "*".equals(cron.rawMonth)) {
            return "On day " + cron.rawDom + " of every month at " + timePart + " (" + tz + ")";
        }
        return trimmed + " (" + tz + ")";
    }

    private String formatDowLabel(String rawDow) {
        String upper = rawDow.toUpperCase(Locale.ROOT);
        if (DOW_NAMES.containsKey(upper)) {
            return DOW_LABELS[DOW_NAMES.get(upper)];
        }
        if (upper.matches("[1-7]")) {
            return DOW_LABELS[Integer.parseInt(upper)];
        }
        return upper;
    }

    public List<Instant> computeNextFireTimes(String expression, String timezone, Instant afterTime, int count) {
        validateOrThrow(expression, timezone);
        ZoneId zoneId = parseZoneId(timezone);
        String trimmed = expression.trim();
        Matcher rateMatcher = RATE_PATTERN.matcher(trimmed);
        if (rateMatcher.matches()) {
            long amount = Long.parseLong(rateMatcher.group(1));
            String unit = rateMatcher.group(2).toLowerCase(Locale.ROOT);
            Duration step = toDuration(amount, unit);
            long stepMillis = step.toMillis();
            long baseEpochMillis = afterTime.toEpochMilli();
            long nextAlignedMillis = ((baseEpochMillis / stepMillis) + 1) * stepMillis;
            List<Instant> results = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                results.add(Instant.ofEpochMilli(nextAlignedMillis + i * stepMillis));
            }
            return results;
        }

        ParsedCron cron = ParsedCron.parse(trimmed);
        List<Instant> results = new ArrayList<>(count);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(afterTime, zoneId)
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1);

        int maxIterations = 525_960 * 2; // up to 2 years in minutes
        int iterations = 0;
        while (results.size() < count && iterations < maxIterations) {
            iterations++;
            if (!cron.years.get(cursor.getYear())) {
                cursor = cursor.plusYears(1).withDayOfYear(1).withHour(0).withMinute(0);
                continue;
            }
            if (!cron.months.get(cursor.getMonthValue())) {
                cursor = cursor.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }
            if (!cron.matchesDay(cursor)) {
                cursor = cursor.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            if (!cron.hours.get(cursor.getHour())) {
                cursor = cursor.plusHours(1).withMinute(0);
                continue;
            }
            if (!cron.minutes.get(cursor.getMinute())) {
                cursor = cursor.plusMinutes(1);
                continue;
            }
            results.add(cursor.toInstant());
            cursor = cursor.plusMinutes(1);
        }
        return results;
    }

    public Instant computePreviousFireTime(String expression, String timezone, Instant beforeOrAt) {
        validateOrThrow(expression, timezone);
        ZoneId zoneId = parseZoneId(timezone);
        String trimmed = expression.trim();
        Matcher rateMatcher = RATE_PATTERN.matcher(trimmed);
        if (rateMatcher.matches()) {
            long amount = Long.parseLong(rateMatcher.group(1));
            String unit = rateMatcher.group(2).toLowerCase(Locale.ROOT);
            long stepMillis = toDuration(amount, unit).toMillis();
            long aligned = (beforeOrAt.toEpochMilli() / stepMillis) * stepMillis;
            return Instant.ofEpochMilli(aligned);
        }

        ParsedCron cron = ParsedCron.parse(trimmed);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(beforeOrAt, zoneId).truncatedTo(ChronoUnit.MINUTES);
        int maxIterations = 2000;
        for (int i = 0; i < maxIterations; i++) {
            if (!cron.years.get(cursor.getYear())) {
                cursor = cursor.minusYears(1).withMonth(12).withDayOfMonth(31).withHour(23).withMinute(59);
                continue;
            }
            if (!cron.months.get(cursor.getMonthValue())) {
                cursor = cursor.withDayOfMonth(1).withHour(0).withMinute(0).minusMinutes(1);
                continue;
            }
            if (!cron.matchesDay(cursor)) {
                cursor = cursor.withHour(0).withMinute(0).minusMinutes(1);
                continue;
            }
            if (!cron.hours.get(cursor.getHour())) {
                cursor = cursor.withMinute(0).minusMinutes(1);
                continue;
            }
            if (!cron.minutes.get(cursor.getMinute())) {
                cursor = cursor.minusMinutes(1);
                continue;
            }
            return cursor.toInstant();
        }
        return null;
    }

    private Duration toDuration(long amount, String unit) {
        if (unit.startsWith("minute")) {
            return Duration.ofMinutes(amount);
        }
        if (unit.startsWith("hour")) {
            return Duration.ofHours(amount);
        }
        return Duration.ofDays(amount);
    }

    static final class ParsedCron {
        final String rawMinute;
        final String rawHour;
        final String rawDom;
        final String rawMonth;
        final String rawDow;
        final String rawYear;

        final BitSet minutes;
        final BitSet hours;
        final BitSet daysOfMonth;
        final BitSet months;
        final BitSet daysOfWeek; // 1=SUN .. 7=SAT
        final BitSet years;
        final boolean domWildcard;
        final boolean dowWildcard;
        final boolean lastDayOfMonth;

        private ParsedCron(
                String rawMinute, String rawHour, String rawDom,
                String rawMonth, String rawDow, String rawYear,
                BitSet minutes, BitSet hours, BitSet daysOfMonth,
                BitSet months, BitSet daysOfWeek, BitSet years,
                boolean domWildcard, boolean dowWildcard, boolean lastDayOfMonth) {
            this.rawMinute = rawMinute;
            this.rawHour = rawHour;
            this.rawDom = rawDom;
            this.rawMonth = rawMonth;
            this.rawDow = rawDow;
            this.rawYear = rawYear;
            this.minutes = minutes;
            this.hours = hours;
            this.daysOfMonth = daysOfMonth;
            this.months = months;
            this.daysOfWeek = daysOfWeek;
            this.years = years;
            this.domWildcard = domWildcard;
            this.dowWildcard = dowWildcard;
            this.lastDayOfMonth = lastDayOfMonth;
        }

        static ParsedCron parse(String expression) {
            Matcher m = CRON_PATTERN.matcher(expression);
            if (!m.matches()) {
                throw ApiException.badRequest(40001,
                        "Invalid AWS expression syntax (expected 'rate(value unit)' or 6-field 'cron(min hour dom month dow year)'): " + expression);
            }
            String minField = m.group(1);
            String hourField = m.group(2);
            String domField = m.group(3);
            String monthField = m.group(4);
            String dowField = m.group(5);
            String yearField = m.group(6);

            // AWS EventBridge rule: cannot specify both Day-of-Month and Day-of-Week without one being '?'
            boolean domIsQ = "?".equals(domField);
            boolean dowIsQ = "?".equals(dowField);
            if (!domIsQ && !dowIsQ) {
                throw ApiException.badRequest(40001,
                        "AWS 6-field cron requires either Day-of-Month or Day-of-Week to be '?': " + expression);
            }
            if (domIsQ && dowIsQ) {
                throw ApiException.badRequest(40001,
                        "AWS 6-field cron cannot have '?' in both Day-of-Month and Day-of-Week: " + expression);
            }

            BitSet minutes = parseField(minField, 0, 59, Map.of());
            BitSet hours = parseField(hourField, 0, 23, Map.of());
            boolean isLastDom = "L".equalsIgnoreCase(domField);
            boolean domWild = "*".equals(domField) || domIsQ;
            BitSet doms = (domWild || isLastDom) ? allBits(1, 31) : parseField(domField, 1, 31, Map.of());
            BitSet months = parseField(monthField, 1, 12, MONTH_NAMES);
            boolean dowWild = "*".equals(dowField) || dowIsQ;
            BitSet dows = dowWild ? allBits(1, 7) : parseField(dowField, 1, 7, DOW_NAMES);
            BitSet years = parseField(yearField, 1970, 2199, Map.of());

            return new ParsedCron(
                    minField, hourField, domField, monthField, dowField, yearField,
                    minutes, hours, doms, months, dows, years,
                    domWild, dowWild, isLastDom
            );
        }

        boolean matchesDay(ZonedDateTime dt) {
            if (lastDayOfMonth) {
                int maxDom = dt.toLocalDate().lengthOfMonth();
                return dt.getDayOfMonth() == maxDom;
            }
            if (!domWildcard) {
                return daysOfMonth.get(dt.getDayOfMonth());
            }
            if (!dowWildcard) {
                int awsDow = toAwsDayOfWeek(dt.getDayOfWeek());
                return daysOfWeek.get(awsDow);
            }
            return true;
        }

        private static int toAwsDayOfWeek(DayOfWeek dow) {
            // Java DayOfWeek: MON=1 .. SUN=7 -> AWS: SUN=1, MON=2 .. SAT=7
            return dow == DayOfWeek.SUNDAY ? 1 : dow.getValue() + 1;
        }

        private static BitSet allBits(int min, int max) {
            BitSet bs = new BitSet(max + 1);
            bs.set(min, max + 1);
            return bs;
        }

        private static BitSet parseField(String field, int min, int max, Map<String, Integer> aliases) {
            if ("*".equals(field) || "?".equals(field)) {
                return allBits(min, max);
            }
            BitSet result = new BitSet(max + 1);
            String[] parts = field.split(",");
            for (String rawPart : parts) {
                String part = rawPart.trim().toUpperCase(Locale.ROOT);
                if (part.isEmpty()) {
                    throw ApiException.badRequest(40001, "Empty cron field segment in: " + field);
                }
                int step = 1;
                String rangePart = part;
                int slashIdx = part.indexOf('/');
                if (slashIdx >= 0) {
                    rangePart = part.substring(0, slashIdx);
                    String stepStr = part.substring(slashIdx + 1);
                    step = parseNumber(stepStr, 1, max, Map.of(), field);
                    if (step <= 0) {
                        throw ApiException.badRequest(40001, "Invalid cron step value in: " + field);
                    }
                }

                int start;
                int end;
                if ("*".equals(rangePart)) {
                    start = min;
                    end = max;
                } else if (rangePart.contains("-")) {
                    String[] bounds = rangePart.split("-", 2);
                    start = parseNumber(bounds[0], min, max, aliases, field);
                    end = parseNumber(bounds[1], min, max, aliases, field);
                    if (start > end) {
                        throw ApiException.badRequest(40001, "Invalid cron range " + rangePart + " in: " + field);
                    }
                } else {
                    start = parseNumber(rangePart, min, max, aliases, field);
                    end = slashIdx >= 0 ? max : start;
                }

                for (int v = start; v <= end; v += step) {
                    result.set(v);
                }
            }
            if (result.isEmpty()) {
                throw ApiException.badRequest(40001, "Cron field matched no values: " + field);
            }
            return result;
        }

        private static int parseNumber(String token, int min, int max, Map<String, Integer> aliases, String fullField) {
            Integer aliasVal = aliases.get(token.toUpperCase(Locale.ROOT));
            int value;
            if (aliasVal != null) {
                value = aliasVal;
            } else {
                try {
                    value = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest(40001, "Invalid cron token '" + token + "' in field '" + fullField + "'");
                }
            }
            if (value < min || value > max) {
                throw ApiException.badRequest(40001,
                        "Cron value " + value + " out of range [" + min + "-" + max + "] in field '" + fullField + "'");
            }
            return value;
        }
    }
}
