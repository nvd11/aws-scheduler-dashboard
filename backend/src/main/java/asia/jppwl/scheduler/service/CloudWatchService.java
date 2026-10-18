package asia.jppwl.scheduler.service;

import asia.jppwl.scheduler.model.SchedulerDtos.DailyMetricBucket;
import asia.jppwl.scheduler.model.SchedulerDtos.Metrics7dView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;

@ApplicationScoped
public class CloudWatchService {

    private final CloudWatchClient cloudWatchClient;
    private final Map<String, CachedMetrics> metricsCache = new ConcurrentHashMap<>();

    private record CachedMetrics(Metrics7dView view, Instant expiresAt) {
    }

    @Inject
    public CloudWatchService(CloudWatchClient cloudWatchClient) {
        this.cloudWatchClient = cloudWatchClient;
    }

    public void evictCache() {
        metricsCache.clear();
    }

    public Metrics7dView fetchRuleMetrics7d(String ruleName) {
        List<LocalDate> last7Days = new ArrayList<>(7);
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        for (int i = 6; i >= 0; i--) {
            last7Days.add(todayUtc.minusDays(i));
        }

        if (ruleName == null || ruleName.isBlank()) {
            return emptyMetrics(last7Days);
        }

        Instant now = Instant.now();
        CachedMetrics cached = metricsCache.get(ruleName);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.view();
        }

        try {
            Instant endTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant startTime = todayUtc.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC);

            Dimension ruleDim = Dimension.builder().name("RuleName").value(ruleName).build();
            MetricDataQuery invocationsQuery = MetricDataQuery.builder()
                    .id("m_invocations")
                    .metricStat(MetricStat.builder()
                            .metric(Metric.builder()
                                    .namespace("AWS/Events")
                                    .metricName("Invocations")
                                    .dimensions(ruleDim)
                                    .build())
                            .period(86400)
                            .stat("Sum")
                            .build())
                    .returnData(true)
                    .build();

            MetricDataQuery failedQuery = MetricDataQuery.builder()
                    .id("m_failed")
                    .metricStat(MetricStat.builder()
                            .metric(Metric.builder()
                                    .namespace("AWS/Events")
                                    .metricName("FailedInvocations")
                                    .dimensions(ruleDim)
                                    .build())
                            .period(86400)
                            .stat("Sum")
                            .build())
                    .returnData(true)
                    .build();

            GetMetricDataResponse response = cloudWatchClient.getMetricData(
                    GetMetricDataRequest.builder()
                            .startTime(startTime)
                            .endTime(endTime)
                            .metricDataQueries(invocationsQuery, failedQuery)
                            .build()
            );

            Map<LocalDate, Long> invByDay = new HashMap<>();
            Map<LocalDate, Long> failByDay = new HashMap<>();

            if (response.metricDataResults() != null) {
                for (MetricDataResult r : response.metricDataResults()) {
                    List<Instant> timestamps = r.timestamps();
                    List<Double> values = r.values();
                    if (timestamps == null || values == null) {
                        continue;
                    }
                    for (int i = 0; i < Math.min(timestamps.size(), values.size()); i++) {
                        LocalDate day = timestamps.get(i).atZone(ZoneOffset.UTC).toLocalDate();
                        long v = Math.round(values.get(i));
                        if ("m_invocations".equals(r.id())) {
                            invByDay.merge(day, v, Long::sum);
                        } else if ("m_failed".equals(r.id())) {
                            failByDay.merge(day, v, Long::sum);
                        }
                    }
                }
            }

            long totalInv = 0;
            long totalFail = 0;
            List<DailyMetricBucket> buckets = new ArrayList<>(7);
            for (LocalDate d : last7Days) {
                long inv = invByDay.getOrDefault(d, 0L);
                long fail = failByDay.getOrDefault(d, 0L);
                totalInv += inv;
                totalFail += fail;
                buckets.add(new DailyMetricBucket(d.toString(), inv, fail));
            }
            long totalSuccess = Math.max(0L, totalInv - totalFail);
            Metrics7dView result = new Metrics7dView(totalInv, totalSuccess, totalFail, buckets);
            metricsCache.put(ruleName, new CachedMetrics(result, now.plusSeconds(60)));
            return result;
        } catch (Exception e) {
            return emptyMetrics(last7Days);
        }
    }

    private static Metrics7dView emptyMetrics(List<LocalDate> days) {
        List<DailyMetricBucket> buckets = days.stream()
                .map(d -> new DailyMetricBucket(d.toString(), 0L, 0L))
                .toList();
        return new Metrics7dView(0L, 0L, 0L, buckets);
    }
}
