package asia.jppwl.scheduler.client;

import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.model.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraalVM Native friendly GitHub Actions REST API client using {@link java.net.http.HttpClient}.
 * Supports listing workflow runs, dispatching workflows, listing run jobs/steps, and streaming
 * bounded plain-text job logs without multi-megabyte ZIP decompression.
 */
@ApplicationScoped
public class GitHubActionsClient {

    private static final int MAX_LOG_BYTES = 128 * 1024; // 128 KB cap for <64MB Native container

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private HttpClient httpClient;

    public record RawLogTail(String content, int totalLines, boolean truncated) {
    }

    @Inject
    public GitHubActionsClient(AppConfig appConfig, ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public void setHttpClientForTesting(HttpClient customClient) {
        this.httpClient = customClient;
    }

    public boolean isConfigured() {
        String token = appConfig.github().token().orElse("").trim();
        return !token.isEmpty() && !token.startsWith("<");
    }

    public JsonNode listWorkflowRuns(String owner, String repo, String workflowFile, int perPage) {
        String path = String.format("/repos/%s/%s/actions/workflows/%s/runs?per_page=%d",
                owner, repo, workflowFile, perPage);
        return sendGetJson(path);
    }

    public JsonNode listRunJobs(String owner, String repo, long runId) {
        String path = String.format("/repos/%s/%s/actions/runs/%d/jobs?per_page=30", owner, repo, runId);
        return sendGetJson(path);
    }

    public void dispatchWorkflow(String owner, String repo, String workflowFile, String ref, Map<String, String> inputs) {
        String path = String.format("/repos/%s/%s/actions/workflows/%s/dispatches", owner, repo, workflowFile);
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("ref", (ref == null || ref.isBlank()) ? "main" : ref);
            if (inputs != null && !inputs.isEmpty()) {
                bodyMap.put("inputs", inputs);
            }
            String payload = objectMapper.writeValueAsString(bodyMap);
            HttpRequest req = baseRequestBuilder(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 204 && res.statusCode() != 200) {
                throw ApiException.badGateway(50202,
                        "GitHub workflow_dispatch failed with HTTP " + res.statusCode() + ": " + res.body(), null);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway(50202, "Failed to dispatch GitHub workflow: " + e.getMessage(), e);
        }
    }

    public RawLogTail fetchJobPlainTextLogs(String owner, String repo, long jobId, int tailLines) {
        int boundedTail = Math.max(10, Math.min(tailLines <= 0 ? 500 : tailLines, 2000));
        String path = String.format("/repos/%s/%s/actions/jobs/%d/logs", owner, repo, jobId);
        try {
            HttpRequest req = baseRequestBuilder(path).GET().build();
            HttpResponse<InputStream> res = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() == 404 || res.statusCode() == 410) {
                return new RawLogTail(
                        "[Log output is not yet available or has expired on GitHub Actions (HTTP " + res.statusCode() + ")]\n",
                        1,
                        false
                );
            }
            if (res.statusCode() >= 400) {
                throw ApiException.badGateway(50202,
                        "GitHub job logs API returned HTTP " + res.statusCode(), null);
            }
            try (InputStream in = res.body()) {
                return readBoundedTail(in, boundedTail, MAX_LOG_BYTES);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway(50202, "Failed to stream GitHub job logs: " + e.getMessage(), e);
        }
    }

    public static RawLogTail readBoundedTail(InputStream in, int maxLines, int maxBytes) throws Exception {
        Deque<String> ring = new ArrayDeque<>(Math.min(maxLines, 2048));
        int totalLines = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (ring.size() == maxLines) {
                    ring.pollFirst();
                }
                ring.addLast(line);
            }
        }
        boolean truncatedByLines = totalLines > maxLines;
        List<String> kept = new ArrayList<>(ring);
        StringBuilder sb = new StringBuilder();
        for (String l : kept) {
            sb.append(l).append('\n');
        }
        String full = sb.toString();
        byte[] utf8 = full.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maxBytes) {
            String sliced = new String(utf8, utf8.length - maxBytes, maxBytes, StandardCharsets.UTF_8);
            int firstNewline = sliced.indexOf('\n');
            if (firstNewline >= 0 && firstNewline + 1 < sliced.length()) {
                sliced = sliced.substring(firstNewline + 1);
            }
            return new RawLogTail(sliced, totalLines, true);
        }
        return new RawLogTail(full, totalLines, truncatedByLines);
    }

    private JsonNode sendGetJson(String path) {
        try {
            HttpRequest req = baseRequestBuilder(path).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw ApiException.badGateway(50202,
                        "GitHub API " + path + " returned HTTP " + res.statusCode() + ": " + res.body(), null);
            }
            return objectMapper.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway(50202, "GitHub API call failed for " + path + ": " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder baseRequestBuilder(String path) {
        String baseUrl = appConfig.github().apiBaseUrl().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "aws-scheduler-dashboard/1.0");
        String token = appConfig.github().token().orElse("").trim();
        if (!token.isEmpty() && !token.startsWith("<")) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
