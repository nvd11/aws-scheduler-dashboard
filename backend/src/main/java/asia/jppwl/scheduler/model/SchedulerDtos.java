package asia.jppwl.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

public final class SchedulerDtos {

    private SchedulerDtos() {
    }

    @RegisterForReflection
    public record ProbeStatus(
            String status,
            String service,
            boolean nativeMode
    ) {
    }

    @RegisterForReflection
    public record UserProfile(
            String id,
            String username,
            String email,
            String avatarUrl,
            String authMode
    ) {
    }

    @RegisterForReflection
    public record LogoutResult(
            String signoutUrl
    ) {
    }

    @RegisterForReflection
    public record CacheStatsDto(
            int topologyTtlSeconds,
            int runHistoryTtlSeconds
    ) {
    }

    @RegisterForReflection
    public record SystemHealthView(
            String status,
            String awsRegion,
            String awsAccountId,
            String schedulerGroup,
            String eventBusName,
            boolean githubConfigured,
            CacheStatsDto cacheStats
    ) {
    }

    @RegisterForReflection
    public record DashboardSummary(
            int totalSchedules,
            int enabledSchedules,
            int disabledSchedules,
            int runningOrQueuedJobs,
            long invocations7d,
            double successRate7d,
            String awsAccountId,
            String awsRegion
    ) {
    }

    @RegisterForReflection
    public record ScheduleRoutingView(
            String eventBusName,
            String source,
            String detailType,
            String matchedRuleName,
            String matchedRuleArn,
            String apiDestinationName,
            String invocationEndpoint,
            String githubOwner,
            String githubRepo,
            String workflowFile,
            String targetRef,
            Map<String, String> defaultInputs
    ) {
    }

    @RegisterForReflection
    public record DailyMetricBucket(
            String date,
            long invocations,
            long failures
    ) {
    }

    @RegisterForReflection
    public record Metrics7dView(
            long invocations,
            long successfulInvocations,
            long failedInvocations,
            List<DailyMetricBucket> dailyBuckets
    ) {
    }

    @RegisterForReflection
    public record ExecutionRecord(
            long runId,
            int runNumber,
            String displayTitle,
            String attributionMethod,
            String event,
            String status,
            String conclusion,
            String startedAt,
            String updatedAt,
            long durationSeconds,
            String htmlUrl
    ) {
    }

    @RegisterForReflection
    public record SchedulePipelineView(
            String name,
            String groupName,
            String arn,
            String description,
            String category,
            String state,
            String scheduleExpression,
            String timezone,
            String humanReadableCron,
            String nextFireTime,
            long nextFireInSeconds,
            String lastModifiedDate,
            ScheduleRoutingView routing,
            Metrics7dView metrics7d,
            ExecutionRecord lastExecution
    ) {
    }

    @RegisterForReflection
    public record ScheduleListResponse(
            DashboardSummary summary,
            List<SchedulePipelineView> items
    ) {
    }

    @RegisterForReflection
    public record RetryPolicyView(
            int maximumEventAgeInSeconds,
            int maximumRetryAttempts
    ) {
    }

    @RegisterForReflection
    public record ScheduleDetailView(
            SchedulePipelineView schedule,
            List<String> upcomingFireTimes,
            String flexibleTimeWindowMode,
            RetryPolicyView retryPolicy,
            String targetRoleArn
    ) {
    }

    @RegisterForReflection
    public record ScheduleRunsResponse(
            String scheduleName,
            String githubRepo,
            String workflowFile,
            int totalMatched,
            List<ExecutionRecord> runs
    ) {
    }

    @RegisterForReflection
    public record JobSummaryItem(
            long jobId,
            String name,
            String status,
            String conclusion,
            long durationSeconds
    ) {
    }

    @RegisterForReflection
    public record StepSummaryItem(
            int number,
            String name,
            String status,
            String conclusion,
            long durationSeconds
    ) {
    }

    @RegisterForReflection
    public record RunLogDetailView(
            String scheduleName,
            long runId,
            long jobId,
            String jobName,
            String status,
            String conclusion,
            String startedAt,
            String completedAt,
            long durationSeconds,
            String htmlUrl,
            List<JobSummaryItem> jobs,
            List<StepSummaryItem> steps,
            boolean truncated,
            int totalLines,
            String logContent
    ) {
    }

    @RegisterForReflection
    public record TriggerScheduleRequest(
            String mode,
            String ref,
            Map<String, String> inputs
    ) {
    }

    @RegisterForReflection
    public record TriggerScheduleResult(
            String scheduleName,
            String mode,
            String eventId,
            String dispatchedAt,
            String triggeredBy,
            String targetWorkflow,
            int recommendedPollDelayMs
    ) {
    }

    @RegisterForReflection
    public record UpdateScheduleStateRequest(
            String state
    ) {
    }

    @RegisterForReflection
    public record UpdateScheduleStateResult(
            String name,
            String previousState,
            String currentState,
            String scheduleArn,
            String updatedAt
    ) {
    }

    @RegisterForReflection
    public record UpdateScheduleConfigRequest(
            String scheduleExpression,
            String timezone,
            String description,
            String state
    ) {
    }

    @RegisterForReflection
    public record UpdateScheduleConfigResult(
            String name,
            String scheduleExpression,
            String timezone,
            String humanReadableCron,
            String nextFireTime,
            String state
    ) {
    }

    @RegisterForReflection
    public record CronPreviewRequest(
            String scheduleExpression,
            String timezone
    ) {
    }

    @RegisterForReflection
    public record CronPreviewResponse(
            boolean valid,
            String humanReadableCron,
            List<String> upcomingFireTimes,
            String errorMessage
    ) {
    }
}
