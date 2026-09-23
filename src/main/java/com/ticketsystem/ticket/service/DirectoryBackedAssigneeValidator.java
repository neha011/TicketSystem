package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.dto.response.FieldError;
import com.ticketsystem.ticket.exception.UnprocessableEntityException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Rejects an assignee that names no user in the {@link UserDirectory} with 422 (Requirement 4.5).
 *
 * <p>Null passes through untouched — unassigned is a valid state, and on update an explicit null is
 * how an assignee is cleared. Anything present, including a whitespace-only string, must resolve to
 * a directory entry; blank text names no one, so it fails here rather than being silently stored.
 */
@Service
public class DirectoryBackedAssigneeValidator implements AssigneeValidator {

    private static final String FIELD = "assignee";

    private final UserDirectory userDirectory;

    public DirectoryBackedAssigneeValidator(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public void requireExists(String assignee) {
        if (assignee == null) {
            return;
        }
        if (!userDirectory.exists(assignee)) {
            // The submitted value is echoed back because the caller sent it, so it leaks nothing
            // internal, and naming it is what lets the UI annotate the assignee input (Req 4.5).
            throw new UnprocessableEntityException(
                    "Assignee '" + assignee + "' does not correspond to an existing user",
                    List.of(new FieldError(FIELD, "must name an existing user")));
        }
    }
}
