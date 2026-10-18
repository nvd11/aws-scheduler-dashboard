package asia.jppwl.scheduler.service;

import asia.jppwl.scheduler.client.GitHubActionsClient;
import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRoutingView;
import asia.jppwl.scheduler.model.SchedulerDtos.TriggerScheduleRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.TriggerScheduleResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleConfigRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleConfigResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleStateRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleStateResult;
import asia.jppwl.scheduler.service.AwsTopologyService.ResolvedScheduleNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.GetScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.GetScheduleResponse;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.ScheduleState;
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleResponse;

@ApplicationScoped
public class JobControlService {

    private final AppConfig appConfig;
    private final SchedulerClient schedulerClient;
    private final EventBridgeClient eventBridgeClient;
    private final GitHubActionsClient gitHubClient;
    private final AwsTopologyService topologyService;
    private final ExecutionCorrelatorService correlatorService;
    private final AwsCronEvaluator cronEvaluator;
    private final ObjectMapper objectMapper;

    @Inject
    public JobControlService(
            AppConfig appConfig,
            SchedulerClient schedulerClient,
            EventBridgeClient eventBridgeClient,
            GitHubActionsClient gitHubClient,
            AwsTopologyService topologyService,
            ExecutionCorrelatorService correlatorService,
            AwsCronEvaluator cronEvaluator,
            ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.schedulerClient = schedulerClient;
        this.eventBridgeClient = eventBridgeClient;
        this.gitHubClient = gitHubClient;
        this.topologyService = topologyService;
        this.correlatorService = correlatorService;
        this.cronEvaluator = cronEvaluator;
        this.objectMapper = objectMapper;
    }

    public TriggerScheduleResult triggerSchedule(
            String scheduleName,
            TriggerScheduleRequest request,
            String triggeredByUsername) {

        ResolvedScheduleNode node = topologyService.getResolvedScheduleByName(scheduleName, false);
        ScheduleRoutingView routing = node.routing();
        String mode = (request != null && request.mode() != null && !request.mode().isBlank())
                ? request.mode().trim().toUpperCase(Locale.ROOT)
                : "EVENTBRIDGE";

        if (!"EVENTBRIDGE".equals(mode) && !"GITHUB_DIRECT".equals(mode)) {
            throw ApiException.badRequest(40002,
                    "Invalid trigger mode '" + mode + "' (expected EVENTBRIDGE or GITHUB_DIRECT)");
        }

        String nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String targetWorkflow = (routing != null && routing.githubOwner() != null)
                ? routing.githubOwner() + "/" + routing.githubRepo() + "/" + routing.workflowFile()
                : "event-bus/" + appConfig.aws().eventBusName();

        if ("EVENTBRIDGE".equals(mode)) {
            if (routing == null || routing.source() == null || routing.source().isBlank()) {
                throw ApiException.badRequest(40002,
                        "Schedule '" + scheduleName + "' does not have EventBridge Source/DetailType parameters");
            }
            try {
                Map<String, Object> detailMap = new LinkedHashMap<>();
                detailMap.put("scheduleName", scheduleName);
                detailMap.put("triggeredBy", triggeredByUsername);
                detailMap.put("triggerMode", "MANUAL_DASHBOARD");
                detailMap.put("dispatchedAt", nowIso);
                if (request != null && request.inputs() != null && !request.inputs().isEmpty()) {
                    detailMap.put("overrideInputs", request.inputs());
                }

                PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                        .eventBusName(appConfig.aws().eventBusName())
                        .source(routing.source())
                        .detailType(routing.detailType() != null ? routing.detailType() : "manual-trigger")
                        .detail(objectMapper.writeValueAsString(detailMap))
                        .build();

                PutEventsResponse res = eventBridgeClient.putEvents(
                        PutEventsRequest.builder().entries(entry).build()
                );
                if (res.failedEntryCount() != null && res.failedEntryCount() > 0) {
                    String errMsg = (res.entries() != null && !res.entries().isEmpty())
                            ? res.entries().get(0).errorMessage()
                            : "Unknown PutEvents error";
                    throw ApiException.badGateway(50201, "AWS EventBridge PutEvents failed: " + errMsg, null);
                }
                String eventId = (res.entries() != null && !res.entries().isEmpty())
                        ? res.entries().get(0).eventId()
                        : UUID.randomUUID().toString();

                if (routing.githubOwner() != null) {
                    correlatorService.evictWorkflowCache(routing.githubOwner(), routing.githubRepo(), routing.workflowFile());
                }
                return new TriggerScheduleResult(
                        scheduleName,
                        mode,
                        eventId,
                        nowIso,
                        triggeredByUsername,
                        targetWorkflow,
                        4000
                );
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw ApiException.badGateway(50201, "AWS EventBridge PutEvents call failed: " + e.getMessage(), e);
            }
        }

        // Mode: GITHUB_DIRECT
        if (routing == null || routing.githubOwner() == null || routing.githubRepo() == null || routing.workflowFile() == null) {
            throw ApiException.notFound(40402,
                    "Schedule '" + scheduleName + "' is not mapped to a GitHub Actions workflow");
        }

        Map<String, String> mergedInputs = new LinkedHashMap<>();
        if (routing.defaultInputs() != null) {
            mergedInputs.putAll(routing.defaultInputs());
        }
        if (request != null && request.inputs() != null) {
            mergedInputs.putAll(request.inputs());
        }
        String ref = (request != null && request.ref() != null && !request.ref().isBlank())
                ? request.ref().trim()
                : routing.targetRef();

        gitHubClient.dispatchWorkflow(
                routing.githubOwner(),
                routing.githubRepo(),
                routing.workflowFile(),
                ref,
                mergedInputs
        );
        correlatorService.evictWorkflowCache(routing.githubOwner(), routing.githubRepo(), routing.workflowFile());

        return new TriggerScheduleResult(
                scheduleName,
                mode,
                "gh-dispatch-" + UUID.randomUUID().toString().substring(0, 8),
                nowIso,
                triggeredByUsername,
                targetWorkflow,
                4000
        );
    }

    public UpdateScheduleStateResult updateScheduleState(String scheduleName, UpdateScheduleStateRequest request) {
        if (request == null || request.state() == null || request.state().isBlank()) {
            throw ApiException.badRequest(40002, "Target state is required (ENABLED or DISABLED)");
        }
        String targetState = request.state().trim().toUpperCase(Locale.ROOT);
        if (!"ENABLED".equals(targetState) && !"DISABLED".equals(targetState)) {
            throw ApiException.badRequest(40002, "Invalid schedule state '" + targetState + "' (expected ENABLED or DISABLED)");
        }

        GetScheduleResponse existing = fetchExistingScheduleOrThrow(scheduleName);
        String previousState = existing.stateAsString() != null ? existing.stateAsString() : "ENABLED";

        try {
            UpdateScheduleResponse updateRes = schedulerClient.updateSchedule(
                     buildUpdateBuilderFromExisting(existing)
                            .state(ScheduleState.fromValue(targetState))
                            .build()
            );
            topologyService.evictCache();
            return new UpdateScheduleStateResult(
                    scheduleName,
                    previousState,
                    targetState,
                    updateRes.scheduleArn(),
                    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
            );
        } catch (Exception e) {
            throw ApiException.badGateway(50201, "Failed to update schedule state on AWS: " + e.getMessage(), e);
        }
    }

    public UpdateScheduleConfigResult updateScheduleConfig(String scheduleName, UpdateScheduleConfigRequest request) {
        if (request == null || request.scheduleExpression() == null || request.scheduleExpression().isBlank()) {
            throw ApiException.badRequest(40001, "scheduleExpression is required");
        }

        GetScheduleResponse existing = fetchExistingScheduleOrThrow(scheduleName);
        String newExpr = request.scheduleExpression().trim();
        String newTz = (request.timezone() != null && !request.timezone().isBlank())
                ? request.timezone().trim()
                : (existing.scheduleExpressionTimezone() != null ? existing.scheduleExpressionTimezone() : "UTC");
        String newDesc = request.description() != null ? request.description() : existing.description();
        String newState = (request.state() != null && !request.state().isBlank())
                ? request.state().trim().toUpperCase(Locale.ROOT)
                : (existing.stateAsString() != null ? existing.stateAsString() : "ENABLED");

        if (!"ENABLED".equals(newState) && !"DISABLED".equals(newState)) {
            throw ApiException.badRequest(40002, "Invalid schedule state: " + newState);
        }

        // Pre-validate expression and timezone before calling AWS
        cronEvaluator.validateOrThrow(newExpr, newTz);

        try {
            schedulerClient.updateSchedule(
                    buildUpdateBuilderFromExisting(existing)
                            .scheduleExpression(newExpr)
                            .scheduleExpressionTimezone(newTz)
                            .description(newDesc)
                            .state(ScheduleState.fromValue(newState))
                            .build()
            );
            topologyService.evictCache();

            List<Instant> nextTimes = cronEvaluator.computeNextFireTimes(newExpr, newTz, Instant.now(), 1);
            String nextFireIso = nextTimes.isEmpty() ? null : nextTimes.get(0).truncatedTo(ChronoUnit.SECONDS).toString();
            String human = cronEvaluator.toHumanReadable(newExpr, newTz);

            return new UpdateScheduleConfigResult(
                    scheduleName,
                    newExpr,
                    newTz,
                    human,
                    nextFireIso,
                    newState
            );
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway(50201, "Failed to update schedule configuration on AWS: " + e.getMessage(), e);
        }
    }

    private GetScheduleResponse fetchExistingScheduleOrThrow(String scheduleName) {
        String group = appConfig.aws().schedulerGroup();
        try {
            return schedulerClient.getSchedule(
                    GetScheduleRequest.builder()
                            .name(scheduleName)
                            .groupName(group)
                            .build()
            );
        } catch (ResourceNotFoundException e) {
            throw ApiException.notFound(40401,
                    "Schedule '" + scheduleName + "' not found in group '" + group + "'");
        } catch (Exception e) {
            throw ApiException.badGateway(50201,
                    "Failed to read schedule '" + scheduleName + "' from AWS: " + e.getMessage(), e);
        }
    }

    private UpdateScheduleRequest.Builder buildUpdateBuilderFromExisting(GetScheduleResponse existing) {
        return UpdateScheduleRequest.builder()
                .name(existing.name())
                .groupName(existing.groupName() != null ? existing.groupName() : appConfig.aws().schedulerGroup())
                .scheduleExpression(existing.scheduleExpression())
                .scheduleExpressionTimezone(existing.scheduleExpressionTimezone())
                .description(existing.description())
                .flexibleTimeWindow(existing.flexibleTimeWindow())
                .target(existing.target())
                .state(existing.state());
    }
}
