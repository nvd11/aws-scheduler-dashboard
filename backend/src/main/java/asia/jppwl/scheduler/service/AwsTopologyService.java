package asia.jppwl.scheduler.service;

import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.RetryPolicyView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRoutingView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ApiDestination;
import software.amazon.awssdk.services.eventbridge.model.ListApiDestinationsRequest;
import software.amazon.awssdk.services.eventbridge.model.ListRulesRequest;
import software.amazon.awssdk.services.eventbridge.model.ListTargetsByRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.Rule;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.GetScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.GetScheduleResponse;
import software.amazon.awssdk.services.scheduler.model.ListSchedulesRequest;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.ScheduleSummary;

@ApplicationScoped
public class AwsTopologyService {

    private static final Pattern GITHUB_DISPATCH_URL_PATTERN =
            Pattern.compile("repos/([^/]+)/([^/]+)/actions/workflows/([^/]+)/dispatches");

    private final AppConfig appConfig;
    private final SchedulerClient schedulerClient;
    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;

    private volatile CachedTopology cachedTopology;

    public record ResolvedScheduleNode(
            String name,
            String groupName,
            String arn,
            String description,
            String category,
            String state,
            String scheduleExpression,
            String timezone,
            String lastModifiedDate,
            String flexibleTimeWindowMode,
            RetryPolicyView retryPolicy,
            String targetRoleArn,
            ScheduleRoutingView routing
    ) {
    }

    private record CachedTopology(List<ResolvedScheduleNode> schedules, Instant expiresAt) {
    }

    private record IndexedRule(
            String name,
            String arn,
            List<String> sources,
            List<String> detailTypes,
            Target primaryTarget
    ) {
    }

    @Inject
    public AwsTopologyService(
            AppConfig appConfig,
            SchedulerClient schedulerClient,
            EventBridgeClient eventBridgeClient,
            ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.schedulerClient = schedulerClient;
        this.eventBridgeClient = eventBridgeClient;
        this.objectMapper = objectMapper;
    }

    public void evictCache() {
        this.cachedTopology = null;
    }

    public List<ResolvedScheduleNode> getResolvedSchedules(boolean forceRefresh) {
        Instant now = Instant.now();
        CachedTopology current = this.cachedTopology;
        if (!forceRefresh && current != null && current.expiresAt().isAfter(now)) {
            return current.schedules();
        }
        List<ResolvedScheduleNode> discovered = discoverTopologyFromAws();
        this.cachedTopology = new CachedTopology(
                discovered,
                now.plusSeconds(appConfig.cache().topologyTtlSeconds())
        );
        return discovered;
    }

    public ResolvedScheduleNode getResolvedScheduleByName(String name, boolean forceRefresh) {
        List<ResolvedScheduleNode> all = getResolvedSchedules(forceRefresh);
        for (ResolvedScheduleNode node : all) {
            if (node.name().equals(name)) {
                return node;
            }
        }
        throw ApiException.notFound(40401,
                "Schedule '" + name + "' not found in group '" + appConfig.aws().schedulerGroup() + "'");
    }

    List<ResolvedScheduleNode> discoverTopologyFromAws() {
        try {
            String groupName = appConfig.aws().schedulerGroup();
            String busName = appConfig.aws().eventBusName();

            // 1. Index EventBridge API Destinations by ARN
            Map<String, ApiDestination> apiDestByArn = new HashMap<>();
            List<ApiDestination> destinations = eventBridgeClient.listApiDestinations(
                    ListApiDestinationsRequest.builder().limit(100).build()
            ).apiDestinations();
            if (destinations != null) {
                for (ApiDestination dest : destinations) {
                    if (dest.apiDestinationArn() != null) {
                        apiDestByArn.put(dest.apiDestinationArn(), dest);
                    }
                }
            }

            // 2. Index EventBridge Rules and their Targets
            List<IndexedRule> indexedRules = new ArrayList<>();
            List<Rule> rules = eventBridgeClient.listRules(
                    ListRulesRequest.builder().eventBusName(busName).limit(100).build()
            ).rules();
            if (rules != null) {
                for (Rule rule : rules) {
                    if (rule.eventPattern() == null || rule.eventPattern().isBlank()) {
                        continue;
                    }
                    JsonNode patternNode = objectMapper.readTree(rule.eventPattern());
                    List<String> sources = readStringArray(patternNode.get("source"));
                    List<String> detailTypes = readStringArray(patternNode.get("detail-type"));
                    List<Target> targets = eventBridgeClient.listTargetsByRule(
                            ListTargetsByRuleRequest.builder()
                                    .eventBusName(busName)
                                    .rule(rule.name())
                                    .build()
                    ).targets();
                    Target firstTarget = (targets != null && !targets.isEmpty()) ? targets.get(0) : null;
                    indexedRules.add(new IndexedRule(rule.name(), rule.arn(), sources, detailTypes, firstTarget));
                }
            }

            // 3. List and describe all schedules in group
            List<ScheduleSummary> summaries = schedulerClient.listSchedules(
                    ListSchedulesRequest.builder().groupName(groupName).maxResults(100).build()
            ).schedules();

            List<ResolvedScheduleNode> result = new ArrayList<>();
            if (summaries != null) {
                for (ScheduleSummary summary : summaries) {
                    GetScheduleResponse detail;
                    try {
                        detail = schedulerClient.getSchedule(
                                GetScheduleRequest.builder()
                                        .name(summary.name())
                                        .groupName(groupName)
                                        .build()
                        );
                    } catch (ResourceNotFoundException e) {
                        continue;
                    }
                    result.add(buildScheduleNode(detail, busName, indexedRules, apiDestByArn));
                }
            }

            result.sort(Comparator.comparing(ResolvedScheduleNode::name));
            return Collections.unmodifiableList(result);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway(50201,
                    "Failed to discover AWS EventBridge Scheduler topology: " + e.getMessage(), e);
        }
    }

    private ResolvedScheduleNode buildScheduleNode(
            GetScheduleResponse detail,
            String defaultBusName,
            List<IndexedRule> indexedRules,
            Map<String, ApiDestination> apiDestByArn) {

        String name = detail.name();
        String group = detail.groupName() != null ? detail.groupName() : appConfig.aws().schedulerGroup();
        String state = detail.stateAsString() != null ? detail.stateAsString() : "ENABLED";
        String expr = detail.scheduleExpression() != null ? detail.scheduleExpression() : "";
        String tz = detail.scheduleExpressionTimezone() != null && !detail.scheduleExpressionTimezone().isBlank()
                ? detail.scheduleExpressionTimezone()
                : "UTC";
        String lastMod = detail.lastModificationDate() != null
                ? detail.lastModificationDate().truncatedTo(ChronoUnit.SECONDS).toString()
                : Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        String flexMode = detail.flexibleTimeWindow() != null && detail.flexibleTimeWindow().modeAsString() != null
                ? detail.flexibleTimeWindow().modeAsString()
                : "OFF";

        int maxAge = 86400;
        int maxRetries = 185;
        String roleArn = null;
        String source = null;
        String detailType = null;

        if (detail.target() != null) {
            roleArn = detail.target().roleArn();
            if (detail.target().retryPolicy() != null) {
                if (detail.target().retryPolicy().maximumEventAgeInSeconds() != null) {
                    maxAge = detail.target().retryPolicy().maximumEventAgeInSeconds();
                }
                if (detail.target().retryPolicy().maximumRetryAttempts() != null) {
                    maxRetries = detail.target().retryPolicy().maximumRetryAttempts();
                }
            }
            if (detail.target().eventBridgeParameters() != null) {
                source = detail.target().eventBridgeParameters().source();
                detailType = detail.target().eventBridgeParameters().detailType();
            }
        }

        ScheduleRoutingView routing = correlateRouting(defaultBusName, source, detailType, indexedRules, apiDestByArn);
        String category = classifyCategory(name, source, routing.githubRepo());

        return new ResolvedScheduleNode(
                name,
                group,
                detail.arn(),
                detail.description() != null ? detail.description() : "",
                category,
                state,
                expr,
                tz,
                lastMod,
                flexMode,
                new RetryPolicyView(maxAge, maxRetries),
                roleArn,
                routing
        );
    }

    private ScheduleRoutingView correlateRouting(
            String eventBusName,
            String source,
            String detailType,
            List<IndexedRule> indexedRules,
            Map<String, ApiDestination> apiDestByArn) {

        IndexedRule matchedRule = findMatchingRule(source, detailType, indexedRules);
        String ruleName = matchedRule != null ? matchedRule.name() : null;
        String ruleArn = matchedRule != null ? matchedRule.arn() : null;

        String apiDestName = null;
        String invocationEndpoint = null;
        String githubOwner = null;
        String githubRepo = null;
        String workflowFile = null;
        String targetRef = "main";
        Map<String, String> defaultInputs = new LinkedHashMap<>();

        if (matchedRule != null && matchedRule.primaryTarget() != null) {
            Target target = matchedRule.primaryTarget();
            if (target.input() != null && !target.input().isBlank()) {
                try {
                    JsonNode inputNode = objectMapper.readTree(target.input());
                    if (inputNode.hasNonNull("ref")) {
                        targetRef = inputNode.get("ref").asText("main");
                    }
                    JsonNode inputsObj = inputNode.get("inputs");
                    if (inputsObj != null && inputsObj.isObject()) {
                        inputsObj.fields().forEachRemaining(entry ->
                                defaultInputs.put(entry.getKey(), entry.getValue().asText("")));
                    }
                } catch (Exception ignored) {
                }
            }

            ApiDestination dest = apiDestByArn.get(target.arn());
            if (dest != null) {
                apiDestName = dest.name();
                invocationEndpoint = dest.invocationEndpoint();
                if (invocationEndpoint != null) {
                    Matcher urlMatcher = GITHUB_DISPATCH_URL_PATTERN.matcher(invocationEndpoint);
                    if (urlMatcher.find()) {
                        githubOwner = urlMatcher.group(1);
                        githubRepo = urlMatcher.group(2);
                        workflowFile = urlMatcher.group(3);
                    }
                }
            }
        }

        return new ScheduleRoutingView(
                eventBusName,
                source,
                detailType,
                ruleName,
                ruleArn,
                apiDestName,
                invocationEndpoint,
                githubOwner,
                githubRepo,
                workflowFile,
                targetRef,
                Collections.unmodifiableMap(defaultInputs)
        );
    }

    private IndexedRule findMatchingRule(String source, String detailType, List<IndexedRule> rules) {
        if (source == null || source.isBlank()) {
            return null;
        }
        // Pass 1: exact match on both source and detail-type
        for (IndexedRule rule : rules) {
            if (rule.sources().contains(source)
                    && !rule.detailTypes().isEmpty()
                    && detailType != null
                    && rule.detailTypes().contains(detailType)) {
                return rule;
            }
        }
        // Pass 2: match on source when rule does not restrict detail-type
        for (IndexedRule rule : rules) {
            if (rule.sources().contains(source) && rule.detailTypes().isEmpty()) {
                return rule;
            }
        }
        return null;
    }

    private static String classifyCategory(String name, String source, String repo) {
        String combined = ((name != null ? name : "") + " " + (source != null ? source : "") + " " + (repo != null ? repo : ""))
                .toLowerCase(Locale.ROOT);
        if (combined.contains("tls") || combined.contains("cert") || combined.contains("infra")) {
            return "INFRA_TLS";
        }
        if (combined.contains("report")) {
            return "FINANCE_REPORT";
        }
        return "FINANCE_ETL";
    }

    private static List<String> readStringArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }
}
