package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.WebhookCredentials;

import java.util.List;

/// The input DTO for [UpdateServiceAccount] (audit `operation` = `UpdateCommand`).
/// `null` fields (other than `id`) mean "leave untouched" (spec §4.2).
public record UpdateCommand(String id, String name, String description, String scope, List<String> clientIds,
                            WebhookCredentials webhookCredentials) {
}
