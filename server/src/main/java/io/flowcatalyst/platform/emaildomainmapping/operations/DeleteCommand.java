package io.flowcatalyst.platform.emaildomainmapping.operations;

/// The input DTO for [DeleteEmailDomainMapping] (audit `operation` = `DeleteCommand`).
public record DeleteCommand(String id) {
}
