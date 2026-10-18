package asia.jppwl.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RegisterForReflection
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, nowIso());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(0, message, data, nowIso());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, nowIso());
    }

    private static String nowIso() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
