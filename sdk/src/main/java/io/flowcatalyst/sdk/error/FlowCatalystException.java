package io.flowcatalyst.sdk.error;

/**
 * The single exception thrown by SDK operations. The {@link SdkError} variant
 * it carries identifies the failure; switch on {@link #error()} to handle
 * specific cases.
 */
public final class FlowCatalystException extends RuntimeException {

    private final transient SdkError error;

    public FlowCatalystException(SdkError error) {
        super(error.message(), cause(error));
        this.error = error;
    }

    public SdkError error() {
        return error;
    }

    private static Throwable cause(SdkError error) {
        return switch (error) {
            case SdkError.Network n -> n.cause();
            case SdkError.TokenFetchFailed t -> t.cause();
            default -> null;
        };
    }
}
