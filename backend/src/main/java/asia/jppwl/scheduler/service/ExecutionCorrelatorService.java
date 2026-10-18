package asia.jppwl.scheduler.service;

import asia.jppwl.scheduler.client.GitHubActionsClient;
import asia.jppwl.scheduler.client.GitHubActionsClient.RawLogTail;
import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.ExecutionRecord;
import asia.jppwl.scheduler.model.SchedulerDtos.JobSummaryItem;
import asia.jppwl.scheduler.model.SchedulerDtos.RunLogDetailView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRoutingView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRunsResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.StepSummaryItem;
import asia.jppwl.scheduler.service.AwsTopologyService.ResolvedScheduleNode;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlates GitHub Actions workflow runs with AWS EventBridge schedules using the
 * Dual-Layer Attribution Algorithm:
 * <ul>
 *   <li>Tier 1: Exact {@code display_title} ({@code run-name}) token matching against schedule inputs.</li>
 *   <li>Tier 2: Cron firing window alignment ({@code -30s .. +240s}) using {@link AwsCronEvaluator}.</li>
 * </ul>
 */
@ApplicationScoped
public class ExecutionCorrelatorService {

    private static final long WINDOW_EARLY_TOLERANCE_SECONDS = 30L;
    private static final long WINDOW_LATE_TOLERANCE_SECONDS = 240L;

    private final AppConfig appConfig;
    private final GitHubActionsClient gitHubClient;
    private final AwsCronEvaluator cronEvaluator;
    private final Map<String, CachedWorkflowRuns> workflowRunsCache = new ConcurrentHashMap<>();

    private record CachedWorkflowRuns(JsonNode runsNode, Instant expiresAt) {
    }

    @Inject
    public ExecutionCorrelatorService(
            AppConfig appConfig,
            GitHubActionsClient gitHubClient,
            AwsCronEvaluator cronEvaluator) {
        this.appConfig = appConfig;
        this.gitHubClient = gitHubClient;
        this.cronEvaluator = cronEvaluator;
    }

    public void evictAllRunCaches() {
        workflowRunsCache.clear();
    }

    public void evictWorkflowCache(String owner, String repo, String workflowFile) {
        if (owner != null && repo != null && workflowFile != null) {
            workflowRunsCache.remove(cacheKey(owner, repo, workflowFile));
        }
    }

    public ScheduleRunsResponse getAttributedRunsForSchedule(
            ResolvedScheduleNode targetSchedule,
            List<ResolvedScheduleNode> allSchedules,
            int limit,
            boolean forceRefresh) {

        ScheduleRoutingView routing = targetSchedule.routing();
        if (routing == null || routing.githubOwner() == null || routing.githubRepo() == null || routing.workflowFile() == null) {
            return new ScheduleRunsResponse(targetSchedule.name(), null, null, 0, List.of());
        }

        String owner = routing.githubOwner();
        String repo = routing.githubRepo();
        String workflowFile = routing.workflowFile();

        List<ResolvedScheduleNode> peerSchedules = allSchedules.stream()
                .filter(s -> s.routing() != null
                        && owner.equals(s.routing().githubOwner())
                        && repo.equals(s.routing().githubRepo())
                        && workflowFile.equals(s.routing().workflowFile()))
                .toList();

        JsonNode runsRoot = fetchWorkflowRunsCached(owner, repo, workflowFile, forceRefresh);
        JsonNode runsArray = runsRoot != null ? runsRoot.get("workflow_runs") : null;
        if (runsArray == null || !runsArray.isArray()) {
            return new ScheduleRunsResponse(targetSchedule.name(), owner + "/" + repo, workflowFile, 0, List.of());
        }

        int maxItems = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        List<ExecutionRecord> attributed = new ArrayList<>();

        for (JsonNode runNode : runsArray) {
            AttributionMatch match = attributeRun(runNode, peerSchedules);
            if (match != null && match.scheduleName().equals(targetSchedule.name())) {
                attributed.add(toExecutionRecord(runNode, match.method()));
            }
        }

        int totalMatched = attributed.size();
        List<ExecutionRecord> sliced = totalMatched > maxItems ? attributed.subList(0, maxItems) : attributed;
        return new ScheduleRunsResponse(
                targetSchedule.name(),
                owner + "/" + repo,
                workflowFile,
                totalMatched,
                sliced
        );
    }

    public RunLogDetailView getRunLogs(
            ResolvedScheduleNode schedule,
            long runId,
            Long requestedJobId,
            int tailLines) {

        ScheduleRoutingView routing = schedule.routing();
        if (routing == null || routing.githubOwner() == null || routing.githubRepo() == null) {
            throw ApiException.notFound(40402,
                    "Schedule '" + schedule.name() + "' has no linked GitHub Actions workflow target");
        }

        String owner = routing.githubOwner();
        String repo = routing.githubRepo();

        JsonNode jobsRoot = gitHubClient.listRunJobs(owner, repo, runId);
        JsonNode jobsArray = jobsRoot != null ? jobsRoot.get("jobs") : null;
        if (jobsArray == null || !jobsArray.isArray() || jobsArray.isEmpty()) {
            throw ApiException.notFound(40402,
                    "No jobs found for GitHub Actions runId " + runId + " in " + owner + "/" + repo);
        }

        List<JobSummaryItem> jobSummaries = new ArrayList<>();
        JsonNode selectedJobNode = null;

        for (JsonNode jobNode : jobsArray) {
            long jId = jobNode.path("id").asLong(0L);
            String jName = jobNode.path("name").asText("job");
            String jStatus = jobNode.path("status").asText("completed");
            String jConclusion = jobNode.path("conclusion").isNull() ? null : jobNode.path("conclusion").asText(null);
            long jDuration = computeDurationSeconds(jobNode.path("started_at").asText(null), jobNode.path("completed_at").asText(null));
            jobSummaries.add(new JobSummaryItem(jId, jName, jStatus, jConclusion, jDuration));

            if (requestedJobId != null && requestedJobId == jId) {
                selectedJobNode = jobNode;
            }
        }

        // Auto-select first failed job if no explicit jobId was provided
        if (selectedJobNode == null) {
            for (JsonNode jobNode : jobsArray) {
                if ("failure".equalsIgnoreCase(jobNode.path("conclusion").asText(""))) {
                    selectedJobNode = jobNode;
                    break;
                }
            }
        }
        if (selectedJobNode == null) {
            selectedJobNode = jobsArray.get(0);
        }

        long selectedJobId = selectedJobNode.path("id").asLong(0L);
        String selectedJobName = selectedJobNode.path("name").asText("job");
        String status = selectedJobNode.path("status").asText("completed");
        String conclusion = selectedJobNode.path("conclusion").isNull() ? null : selectedJobNode.path("conclusion").asText(null);
        String startedAt = selectedJobNode.path("started_at").asText(null);
        String completedAt = selectedJobNode.path("completed_at").asText(null);
        long durationSeconds = computeDurationSeconds(startedAt, completedAt);
        String htmlUrl = selectedJobNode.path("html_url").asText(
                String.format("https://github.com/%s/%s/actions/runs/%d", owner, repo, runId)
        );

        List<StepSummaryItem> steps = new ArrayList<>();
        JsonNode stepsArray = selectedJobNode.get("steps");
        if (stepsArray != null && stepsArray.isArray()) {
            for (JsonNode stepNode : stepsArray) {
                int num = stepNode.path("number").asInt(0);
                String sName = stepNode.path("name").asText("step");
                String sStatus = stepNode.path("status").asText("completed");
                String sConclusion = stepNode.path("conclusion").isNull() ? null : stepNode.path("conclusion").asText(null);
                long sDur = computeDurationSeconds(stepNode.path("started_at").asText(null), stepNode.path("completed_at").asText(null));
                steps.add(new StepSummaryItem(num, sName, sStatus, sConclusion, sDur));
            }
        }

        RawLogTail logTail;
        if ("queued".equalsIgnoreCase(status) || "in_progress".equalsIgnoreCase(status)) {
            RawLogTail fetched = gitHubClient.fetchJobPlainTextLogs(owner, repo, selectedJobId, tailLines);
            if (fetched.content().startsWith("[Log output is not yet available")) {
                logTail = new RawLogTail(
                        "[Job '" + selectedJobName + "' is currently " + status + ". Live step statuses are shown above; full console log stream will finalize upon job completion.]\n",
                        1,
                        false
                );
            } else {
                logTail = fetched;
            }
        } else {
            logTail = gitHubClient.fetchJobPlainTextLogs(owner, repo, selectedJobId, tailLines);
        }

        return new RunLogDetailView(
                schedule.name(),
                runId,
                selectedJobId,
                selectedJobName,
                status,
                conclusion,
                startedAt,
                completedAt,
                durationSeconds,
                htmlUrl,
                jobSummaries,
                steps,
                logTail.truncated(),
                logTail.totalLines(),
                logTail.content()
        );
    }

    private record AttributionMatch(String scheduleName, String method) {
    }

    AttributionMatch attributeRun(JsonNode runNode, List<ResolvedScheduleNode> peerSchedules) {
        if (peerSchedules == null || peerSchedules.isEmpty()) {
            return null;
        }
        // Single owner shortcut (e.g. jppwl-tls-cert-renew-weekly)
        if (peerSchedules.size() == 1) {
            return new AttributionMatch(peerSchedules.get(0).name(), "SINGLE_OWNER");
        }

        // Tier 1: Exact display_title (run-name) token matching
        String displayTitle = runNode.path("display_title").asText("").toUpperCase(Locale.ROOT);
        if (!displayTitle.isBlank()) {
            for (ResolvedScheduleNode candidate : peerSchedules) {
                Map<String, String> inputs = candidate.routing() != null ? candidate.routing().defaultInputs() : Map.of();
                String stage = inputs.getOrDefault("pipeline_stage", "").toUpperCase(Locale.ROOT);
                String period = inputs.getOrDefault("report_period", "").toUpperCase(Locale.ROOT);

                if ("REPORT".equals(stage) && !period.isEmpty()) {
                    // Matches e.g. "Batch ETL [report - DAILY]" or "Batch ETL [report | WEEKLY | finance]"
                    if (displayTitle.contains(period) && (displayTitle.contains("REPORT") || !displayTitle.contains("ALL"))) {
                        return new AttributionMatch(candidate.name(), "DISPLAY_TITLE");
                    }
                } else if ("ALL".equals(stage) || "ODS".equals(stage) || "DWD".equals(stage) || inputs.isEmpty()) {
                    if (displayTitle.contains("INCREMENTAL")
                            || displayTitle.contains("[ALL")
                            || displayTitle.contains("INGEST")
                            || displayTitle.contains("ODS")) {
                        return new AttributionMatch(candidate.name(), "DISPLAY_TITLE");
                    }
                }
            }
        }

        // Tier 2: Cron firing window alignment
        String createdAtStr = runNode.path("created_at").asText(
                runNode.path("run_started_at").asText(null)
        );
        if (createdAtStr != null && !createdAtStr.isBlank()) {
            try {
                Instant runCreated = Instant.parse(createdAtStr);
                ResolvedScheduleNode bestSpecificCronMatch = null;
                long bestSpecificDelta = Long.MAX_VALUE;
                ResolvedScheduleNode bestRateOrFrequentMatch = null;
                long bestFrequentDelta = Long.MAX_VALUE;

                for (ResolvedScheduleNode candidate : peerSchedules) {
                    boolean isHighFrequency = candidate.scheduleExpression().toLowerCase(Locale.ROOT).startsWith("rate(");
                    if (isHighFrequency) {
                        if (bestRateOrFrequentMatch == null) {
                            bestRateOrFrequentMatch = candidate;
                            bestFrequentDelta = 0L;
                        }
                        continue;
                    }
                    Instant fireTime = cronEvaluator.computePreviousFireTime(
                            candidate.scheduleExpression(),
                            candidate.timezone(),
                            runCreated.plusSeconds(WINDOW_EARLY_TOLERANCE_SECONDS)
                    );
                    if (fireTime == null) {
                        continue;
                    }
                    long deltaSeconds = Duration.between(fireTime, runCreated).getSeconds();
                    if (deltaSeconds >= -WINDOW_EARLY_TOLERANCE_SECONDS && deltaSeconds <= WINDOW_LATE_TOLERANCE_SECONDS) {
                        long absDelta = Math.abs(deltaSeconds);
                        if (absDelta < bestSpecificDelta) {
                            bestSpecificDelta = absDelta;
                            bestSpecificCronMatch = candidate;
                        }
                    }
                }
                if (bestSpecificCronMatch != null) {
                    return new AttributionMatch(bestSpecificCronMatch.name(), "CRON_WINDOW");
                }
                if (bestRateOrFrequentMatch != null) {
                    return new AttributionMatch(bestRateOrFrequentMatch.name(), "CRON_WINDOW");
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private ExecutionRecord toExecutionRecord(JsonNode runNode, String attributionMethod) {
        long runId = runNode.path("id").asLong(0L);
        int runNumber = runNode.path("run_number").asInt(0);
        String displayTitle = runNode.path("display_title").asText(
                runNode.path("name").asText("Workflow Run")
        );
        String event = runNode.path("event").asText("workflow_dispatch");
        String status = runNode.path("status").asText("completed");
        String conclusion = runNode.path("conclusion").isNull() ? null : runNode.path("conclusion").asText(null);
        String startedAt = runNode.path("run_started_at").asText(
                runNode.path("created_at").asText(null)
        );
        String updatedAt = runNode.path("updated_at").asText(startedAt);
        long durationSeconds = computeDurationSeconds(startedAt, updatedAt);
        String htmlUrl = runNode.path("html_url").asText("");

        return new ExecutionRecord(
                runId,
                runNumber,
                displayTitle,
                attributionMethod,
                event,
                status,
                conclusion,
                startedAt,
                updatedAt,
                durationSeconds,
                htmlUrl
        );
    }

    static long computeDurationSeconds(String startIso, String endIso) {
        if (startIso == null || startIso.isBlank() || endIso == null || endIso.isBlank()) {
            return 0L;
        }
        try {
            Instant start = Instant.parse(startIso);
            Instant end = Instant.parse(endIso);
            return Math.max(0L, Duration.between(start, end).getSeconds());
        } catch (Exception e) {
            return 0L;
        }
    }

    private JsonNode fetchWorkflowRunsCached(String owner, String repo, String workflowFile, boolean forceRefresh) {
        if (!gitHubClient.isConfigured()) {
            return null;
        }
        String key = cacheKey(owner, repo, workflowFile);
        Instant now = Instant.now();
        CachedWorkflowRuns cached = workflowRunsCache.get(key);
        if (!forceRefresh && cached != null && cached.expiresAt().isAfter(now)) {
            return cached.runsNode();
        }
        try {
            JsonNode fresh = gitHubClient.listWorkflowRuns(owner, repo, workflowFile, 50);
            workflowRunsCache.put(key, new CachedWorkflowRuns(
                    fresh,
                    now.plusSeconds(appConfig.cache().runHistoryTtlSeconds())
            ));
            return fresh;
        } catch (Exception e) {
            return cached != null ? cached.runsNode() : null;
        }
    }

    private static String cacheKey(String owner, String repo, String workflowFile) {
        return owner + "/" + repo + "/" + workflowFile;
    }
}
