package asia.jppwl.scheduler.model;

public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final int errorCode;

    public ApiException(int httpStatus, int errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public ApiException(int httpStatus, int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public static ApiException badRequest(int errorCode, String message) {
        return new ApiException(400, errorCode, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, 40100, message);
    }

    public static ApiException notFound(int errorCode, String message) {
        return new ApiException(404, errorCode, message);
    }

    public static ApiException badGateway(int errorCode, String message, Throwable cause) {
        return new ApiException(502, errorCode, message, cause);
    }
}
