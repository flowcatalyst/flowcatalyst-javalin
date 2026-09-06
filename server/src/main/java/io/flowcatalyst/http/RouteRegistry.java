package io.flowcatalyst.http;

import java.util.List;

/// Read side of route registration: every `get`/`post`/`put`/`patch`/`delete`
/// made through a [Routes], each carrying the [Group] it was registered
/// under (or `null` when registered outside `Routes.in(Group)`).
/// `before`/`after`/`exception` are not registrations. `LockfileCoverageTest`
/// walks this instead of Javalin's `HandlerType`. Every `Routes`
/// implementation is also a `RouteRegistry`.
public interface RouteRegistry {

    List<Registration> registrations();

    record Registration(String method, String path, Group group) {
    }
}
