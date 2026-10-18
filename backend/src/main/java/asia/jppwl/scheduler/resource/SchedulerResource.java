package asia.jppwl.scheduler.resource;

import asia.jppwl.scheduler.client.GitHubActionsClient;
import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.model.ApiResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.CacheStatsDto;
import asia.jppwl.scheduler.model.SchedulerDtos.CronPreviewRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.CronPreviewResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.DashboardSummary;
import asia.jppwl.scheduler.model.SchedulerDtos.ExecutionRecord;
import asia.jppwl.scheduler.model.SchedulerDtos.Metrics7dView;
import asia.jppwl.scheduler.model.SchedulerDtos.RunLogDetailView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleDetailView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleListResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.SchedulePipelineView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRunsResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.SystemHealthView;
import asia.jppwl.scheduler.model.SchedulerDtos.TriggerScheduleRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.TriggerScheduleResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleConfigRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleConfigResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleStateRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleStateResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UserProfile;
import asia.jppwl.scheduler.security.ForwardAuthIdentityService;
import asia.jppwl.scheduler.service.AwsCronEvaluator;
import asia.jppwl.scheduler.service.AwsTopologyService;
import asia.jppwl.scheduler.service.AwsTopologyService.ResolvedScheduleNode;
import asia.jppwl.scheduler.service.CloudWatchService;
import asia.jppwl.scheduler.service.ExecutionCorrelatorService;
import asia.jppwl.scheduler.service.JobControlService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchedulerResource {

    private final AppConfig appConfig;
    private final ForwardAuthIdentityService identityService;
    private final AwsTopologyService topologyService;
    private final CloudWatchService cloudWatchService;
    private final ExecutionCorrelatorService correlatorService;
    private final JobControlService jobControlService;
    private final AwsCronEvaluator cronEvaluator;
    private final GitHubActionsClient gitHubClient;

    @Inject
    public SchedulerResource(
            AppConfig appConfig,
            ForwardAuthIdentityService identityService,
            AwsTopologyService topologyService,
            CloudWatchService cloudWatchService,
            ExecutionCorrelatorService correlatorService,
            JobControlService jobControlService,
            AwsCronEvaluator cronEvaluator,
            GitHubActionsClient gitHubClient) {
        this.appConfig = appConfig;
        this.identityService = identityService;
        this.topologyService = topologyService;
        this.cloudWatchService = cloudWatchService;
        this.correlatorService = correlatorService;
        this.jobControlService = jobControlService;
        this.cronEvaluator = cronEvaluator;
        this.gitHubClient = gitHubClient;
    }

    @GET
    @Path("/health")
    @RunOnVirtualThread
    public ApiResponse<SystemHealthView> getSystemHealth(@Context HttpHeaders headers) {
        identityService.resolveCurrentUser(headers);
        SystemHealthView health = new SystemHealthView(
                "HEALTHY",
                appConfig.aws().region(),
                appConfig.aws().accountId(),
                appConfig.aws().schedulerGroup(),
                appConfig.aws().eventBusName(),
                gitHubClient.isConfigured(),
                new CacheStatsDto(
                        appConfig.cache().topologyTtlSeconds(),
                        appConfig.cache().runHistoryTtlSeconds()
                )
        );
        return ApiResponse.ok(health);
    }

    @GET
    @Path("/schedules")
    @RunOnVirtualThread
    public ApiResponse<ScheduleListResponse> listSchedules(
            @Context HttpHeaders headers,
            @QueryParam("refresh") @DefaultValue("false") boolean refresh,
            @QueryParam("state") String stateFilter,
            @QueryParam("q") String query) {

        identityService.resolveCurrentUser(headers);
        if (refresh) {
            topologyService.evictCache();
            cloudWatchService.evictCache();
            correlatorService.evictAllRunCaches();
        }

        List<ResolvedScheduleNode> rawNodes = topologyService.getResolvedSchedules(refresh);
        Instant now = Instant.now();

        List<SchedulePipelineView> allViews = new ArrayList<>(rawNodes.size());
        int enabledCount = 0;
        int disabledCount = 0;
        int runningCount = 0;
        long totalInvocations7d = 0;
        long totalSuccess7d = 0;

        for (ResolvedScheduleNode node : rawNodes) {
            SchedulePipelineView view = buildPipelineView(node, rawNodes, now, refresh);
            allViews.add(view);
            if ("ENABLED".equalsIgnoreCase(view.state())) {
                enabledCount++;
            } else {
                disabledCount++;
            }
            if (view.lastExecution() != null) {
                String st = view.lastExecution().status();
                if ("in_progress".equalsIgnoreCase(st) || "queued".equalsIgnoreCase(st)) {
                    runningCount++;
                }
            }
            if (view.metrics7d() != null) {
                totalInvocations7d += view.metrics7d().invocations();
                totalSuccess7d += view.metrics7d().successfulInvocations();
            }
        }

        double successRate = totalInvocations7d > 0
                ? Math.round((totalSuccess7d * 1000.0) / totalInvocations7d) / 10.0
                : 100.0;

        DashboardSummary summary = new DashboardSummary(
                allViews.size(),
                enabledCount,
                disabledCount,
                runningCount,
                totalInvocations7d,
                successRate,
                appConfig.aws().accountId(),
                appConfig.aws().region()
        );

        List<SchedulePipelineView> filtered = allViews.stream()
                .filter(item -> matchesFilter(item, stateFilter, query))
                .toList();

        return ApiResponse.ok(new ScheduleListResponse(summary, filtered));
    }

    @GET
    @Path("/schedules/{name}")
    @RunOnVirtualThread
    public ApiResponse<ScheduleDetailView> getScheduleDetail(
            @Context HttpHeaders headers,
            @PathParam("name") String name) {

        identityService.resolveCurrentUser(headers);
        List<ResolvedScheduleNode> allNodes = topologyService.getResolvedSchedules(false);
        ResolvedScheduleNode node = topologyService.getResolvedScheduleByName(name, false);
        Instant now = Instant.now();
        SchedulePipelineView view = buildPipelineView(node, allNodes, now, false);

        List<String> upcoming = List.of();
        try {
            upcoming = cronEvaluator.computeNextFireTimes(node.scheduleExpression(), node.timezone(), now, 5)
                    .stream()
                    .map(i -> i.truncatedTo(ChronoUnit.SECONDS).toString())
                    .toList();
        } catch (Exception ignored) {
        }

        ScheduleDetailView detail = new ScheduleDetailView(
                view,
                upcoming,
                node.flexibleTimeWindowMode(),
                node.retryPolicy(),
                node.targetRoleArn()
        );
        return ApiResponse.ok(detail);
    }

    @GET
    @Path("/schedules/{name}/runs")
    @RunOnVirtualThread
    public ApiResponse<ScheduleRunsResponse> getScheduleRuns(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("refresh") @DefaultValue("false") boolean refresh) {

        identityService.resolveCurrentUser(headers);
        List<ResolvedScheduleNode> allNodes = topologyService.getResolvedSchedules(false);
        ResolvedScheduleNode target = topologyService.getResolvedScheduleByName(name, false);
        ScheduleRunsResponse runs = correlatorService.getAttributedRunsForSchedule(target, allNodes, limit, refresh);
        return ApiResponse.ok(runs);
    }

    @GET
    @Path("/schedules/{name}/runs/{runId}/logs")
    @RunOnVirtualThread
    public ApiResponse<RunLogDetailView> getRunLogs(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @PathParam("runId") long runId,
            @QueryParam("jobId") Long jobId,
            @QueryParam("tailLines") @DefaultValue("500") int tailLines) {

        identityService.resolveCurrentUser(headers);
        ResolvedScheduleNode target = topologyService.getResolvedScheduleByName(name, false);
        RunLogDetailView logDetail = correlatorService.getRunLogs(target, runId, jobId, tailLines);
        return ApiResponse.ok(logDetail);
    }

    @POST
    @Path("/schedules/{name}/trigger")
    @RunOnVirtualThread
    public Response triggerSchedule(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            TriggerScheduleRequest request) {

        UserProfile user = identityService.resolveCurrentUser(headers);
        TriggerScheduleResult result = jobControlService.triggerSchedule(name, request, user.username());
        String msg = "Schedule triggered successfully via " + result.mode();
        return Response.status(Response.Status.ACCEPTED)
                .entity(ApiResponse.ok(msg, result))
                .build();
    }

    @PATCH
    @Path("/schedules/{name}/state")
    @RunOnVirtualThread
    public ApiResponse<UpdateScheduleStateResult> updateScheduleState(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            UpdateScheduleStateRequest request) {

        identityService.resolveCurrentUser(headers);
        UpdateScheduleStateResult result = jobControlService.updateScheduleState(name, request);
        return ApiResponse.ok("Schedule state updated to " + result.currentState(), result);
    }

    @PUT
    @Path("/schedules/{name}")
    @RunOnVirtualThread
    public ApiResponse<UpdateScheduleConfigResult> updateScheduleConfig(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            UpdateScheduleConfigRequest request) {

        identityService.resolveCurrentUser(headers);
        UpdateScheduleConfigResult result = jobControlService.updateScheduleConfig(name, request);
        return ApiResponse.ok("Schedule configuration updated successfully", result);
    }

    @POST
    @Path("/cron/preview")
    @RunOnVirtualThread
    public ApiResponse<CronPreviewResponse> previewCron(
            @Context HttpHeaders headers,
            CronPreviewRequest request) {

        identityService.resolveCurrentUser(headers);
        String expr = request != null ? request.scheduleExpression() : null;
        String tz = request != null ? request.timezone() : "UTC";
        CronPreviewResponse preview = cronEvaluator.preview(expr, tz, Instant.now());
        return ApiResponse.ok(preview);
    }

    private SchedulePipelineView buildPipelineView(
            ResolvedScheduleNode node,
            List<ResolvedScheduleNode> allNodes,
            Instant now,
            boolean forceRefresh) {

        String humanCron = "Unknown";
        String nextFireIso = null;
        long nextFireSeconds = 0L;

        try {
            humanCron = cronEvaluator.toHumanReadable(node.scheduleExpression(), node.timezone());
            List<Instant> nextTimes = cronEvaluator.computeNextFireTimes(node.scheduleExpression(), node.timezone(), now, 1);
            if (!nextTimes.isEmpty()) {
                Instant next = nextTimes.get(0);
                nextFireIso = next.truncatedTo(ChronoUnit.SECONDS).toString();
                nextFireSeconds = Math.max(0L, Duration.between(now, next).getSeconds());
            }
        } catch (Exception ignored) {
        }

        String matchedRuleName = node.routing() != null ? node.routing().matchedRuleName() : null;
        Metrics7dView metrics7d = cloudWatchService.fetchRuleMetrics7d(matchedRuleName);

        ScheduleRunsResponse runsRes = correlatorService.getAttributedRunsForSchedule(node, allNodes, 1, forceRefresh);
        ExecutionRecord lastRun = (runsRes.runs() != null && !runsRes.runs().isEmpty())
                ? runsRes.runs().get(0)
                : null;

        return new SchedulePipelineView(
                node.name(),
                node.groupName(),
                node.arn(),
                node.description(),
                node.category(),
                node.state(),
                node.scheduleExpression(),
                node.timezone(),
                humanCron,
                nextFireIso,
                nextFireSeconds,
                node.lastModifiedDate(),
                node.routing(),
                metrics7d,
                lastRun
        );
    }

    private static boolean matchesFilter(SchedulePipelineView item, String stateFilter, String query) {
        if (stateFilter != null && !stateFilter.isBlank() && !"ALL".equalsIgnoreCase(stateFilter.trim())) {
            if (!stateFilter.trim().equalsIgnoreCase(item.state())) {
                return false;
            }
        }
        if (query != null && !query.isBlank()) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            String haystack = (
                    (item.name() != null ? item.name() : "") + " "
                            + (item.description() != null ? item.description() : "") + " "
                            + (item.category() != null ? item.category() : "") + " "
                            + (item.routing() != null && item.routing().source() != null ? item.routing().source() : "") + " "
                            + (item.routing() != null && item.routing().githubRepo() != null ? item.routing().githubRepo() : "")
            ).toLowerCase(Locale.ROOT);
            return haystack.contains(q);
        }
        return true;
    }
}
