package io.flowcatalyst.sdk.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a subscription this application consumes. Place on the handler
 * class and register it with {@link DefinitionScanner}.
 *
 * <pre>{@code
 * @AsSubscription(
 *         code = "order-shipped-hook",
 *         name = "Order Shipped Hook",
 *         target = "https://app.example.com/webhooks/order-shipped",
 *         eventTypes = {"orders:fulfillment:shipment:shipped"})
 * public final class OrderShippedHandler { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AsSubscription {

    /** Short code (unique within the application). */
    String code();

    String name();

    String description() default "";

    /** Webhook URL where events are delivered. */
    String target();

    /** Full event type codes this subscription consumes. */
    String[] eventTypes();

    /** Pre-configured connection reference (alternative to {@code target}). */
    String connectionId() default "";

    /** Dispatch pool code; platform default pool when omitted. */
    String dispatchPoolCode() default "";

    /** {@code IMMEDIATE} (default) or {@code BLOCK_ON_ERROR}. */
    String mode() default "";

    /** Negative = platform default. */
    int maxRetries() default -1;

    /** Negative = platform default. */
    int timeoutSeconds() default -1;

    /** When true, only the event's {@code data} field is POSTed (no metadata envelope). */
    boolean dataOnly() default true;
}
