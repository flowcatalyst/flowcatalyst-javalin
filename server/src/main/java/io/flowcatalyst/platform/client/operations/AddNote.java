package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientNote;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientNoteAdded;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Appends a note stamped with the acting principal ([Client#addNote]) and
/// emits [ClientNoteAdded].
public final class AddNote {

    private AddNote() {
    }

    public static Operation<AddNoteCommand, ClientNoteAdded> of(ClientRepository repo) {
        return Operation.<AddNoteCommand, ClientNoteAdded>named("AddNote")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.clientId(), "ID_REQUIRED", "clientId is required");
                    UseCaseException.requireNonBlank(cmd.category(), "CATEGORY_REQUIRED", "category is required");
                    UseCaseException.requireNonBlank(cmd.text(), "TEXT_REQUIRED", "text is required");
                })
                // Clients are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Client c = Access.byId(repo, cmd.clientId())
                            .addNote(ClientNote.of(cmd.category(), cmd.text(), ec.principalId()));
                    return Plan.save(c, repo, ClientNoteAdded.of(ec, c, cmd.category(), cmd.text()));
                });
    }
}
