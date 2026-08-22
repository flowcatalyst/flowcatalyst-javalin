package io.flowcatalyst.sdk.annotations;

import io.flowcatalyst.sdk.sync.Definitions;
import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Builds a {@link DefinitionSet} from explicitly registered annotated
 * classes. No classpath scanning — pass the classes carrying
 * {@link AsEventType}, {@link AsSubscription}, {@link AsDispatchPool}, and
 * {@link AsRole}:
 *
 * <pre>{@code
 * DefinitionSet set = DefinitionScanner.scan("orders",
 *         List.of(OrderCreated.class, OrderShippedHandler.class, AdminRole.class));
 * client.definitions().sync(set);
 * }</pre>
 */
public final class DefinitionScanner {

    private DefinitionScanner() {}

    public static DefinitionSet scan(String applicationCode, Collection<Class<?>> classes) {
        List<Definitions.EventType> eventTypes = new ArrayList<>();
        List<Definitions.Subscription> subscriptions = new ArrayList<>();
        List<Definitions.DispatchPool> pools = new ArrayList<>();
        List<Definitions.Role> roles = new ArrayList<>();

        for (Class<?> clazz : classes) {
            AsEventType eventType = clazz.getAnnotation(AsEventType.class);
            if (eventType != null) {
                eventTypes.add(new Definitions.EventType(
                        eventType.code(), eventType.name(), emptyToNull(eventType.description())));
            }

            AsSubscription subscription = clazz.getAnnotation(AsSubscription.class);
            if (subscription != null) {
                subscriptions.add(new Definitions.Subscription(
                        subscription.code(),
                        subscription.name(),
                        emptyToNull(subscription.description()),
                        subscription.target(),
                        emptyToNull(subscription.connectionId()),
                        Arrays.stream(subscription.eventTypes())
                                .map(Definitions.SubscriptionEventType::of)
                                .toList(),
                        emptyToNull(subscription.dispatchPoolCode()),
                        subscription.mode().isEmpty()
                                ? null
                                : Definitions.SubscriptionMode.valueOf(subscription.mode()),
                        negativeToNull(subscription.maxRetries()),
                        negativeToNull(subscription.timeoutSeconds()),
                        subscription.dataOnly()));
            }

            AsDispatchPool pool = clazz.getAnnotation(AsDispatchPool.class);
            if (pool != null) {
                pools.add(new Definitions.DispatchPool(
                        pool.code(),
                        pool.name().isEmpty() ? pool.code() : pool.name(),
                        emptyToNull(pool.description()),
                        negativeToNull(pool.rateLimit()),
                        negativeToNull(pool.concurrency())));
            }

            AsRole role = clazz.getAnnotation(AsRole.class);
            if (role != null) {
                roles.add(new Definitions.Role(
                        role.name(),
                        emptyToNull(role.displayName()),
                        emptyToNull(role.description()),
                        Arrays.stream(role.permissions())
                                .<Definitions.PermissionRef>map(Definitions.PermissionRef::raw)
                                .toList(),
                        role.clientManaged()));
            }
        }

        return DefinitionSet.define(applicationCode)
                .withEventTypes(eventTypes)
                .withSubscriptions(subscriptions)
                .withDispatchPools(pools)
                .withRoles(roles);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Integer negativeToNull(int value) {
        return value < 0 ? null : value;
    }
}
