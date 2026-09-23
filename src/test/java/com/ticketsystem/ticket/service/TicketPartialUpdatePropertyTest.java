package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ticketsystem.ticket.config.UserDirectoryProperties;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.dto.request.Patch;
import com.ticketsystem.ticket.dto.request.UpdateTicketRequest;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Property-based test for the partial-update semantics of {@link TicketServiceImpl#update} (Req 4.1,
 * 4.2).
 *
 * <p>The repository is the only mocked collaborator, because persistence is the boundary; the mapper,
 * the baseline authorization policy, and the assignee validator are the real objects, so the
 * assertions are about the {@link TicketDetailResponse} a caller actually receives rather than a
 * rehearsal of stubbed returns. jqwik builds a fresh test instance per try and does not honour
 * Jupiter/Mockito extensions, so the mock and the service are wired up by hand in the constructor.
 *
 * <p>The clock is fixed at {@link #NOW}, and the ticket under test starts with
 * {@code updatedAt == NOW}, so the service's same-tick nudge is exercised: {@code updatedAt} must come
 * back strictly after {@code NOW} even though the clock has not moved. That is the interesting case
 * for the "updatedAt strictly advances" clause — a plain {@code clock.instant()} would return an equal
 * value here and the property would catch it.
 */
class TicketPartialUpdatePropertyTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");
    private static final String ACTOR = "alice";

    /** Users the assignee validator will accept; a new assignee is drawn only from this set. */
    private static final Set<String> KNOWN_USERS = Set.of("alice", "bob", "carol", "dave");

    /** The four fields a partial update may touch. */
    private enum Field {
        TITLE,
        DESCRIPTION,
        PRIORITY,
        ASSIGNEE
    }

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketService ticketService;

    TicketPartialUpdatePropertyTest() {
        UserDirectoryProperties directoryProperties = new UserDirectoryProperties();
        directoryProperties.setKnownUsers(KNOWN_USERS);

        this.ticketService =
                new TicketServiceImpl(
                        ticketRepository,
                        new TicketMapper(new CommentMapper()),
                        new DefaultTicketAuthorizationService(),
                        new DirectoryBackedAssigneeValidator(
                                new ConfiguredUserDirectory(directoryProperties)),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // Feature: support-ticket-management, Property 16: Partial updates change exactly the supplied
    // fields
    /**
     * Property 16: a partial update changes exactly the fields it names and nothing else.
     *
     * <p>Over an arbitrary non-empty subset of {@code {title, description, priority, assignee}} with
     * valid new values, four things are asserted about the returned ticket:
     *
     * <ul>
     *   <li>every patched field holds the new value (Req 4.1, 4.2);
     *   <li>every omitted field still holds the value it had before the update;
     *   <li>{@code updatedAt} is strictly after the pre-update value (Req 4.1, 4.2);
     *   <li>{@code status} and {@code createdAt} are unchanged — the update path is not a way through
     *       the state machine, and creation time is immutable.
     * </ul>
     *
     * <p>Each generated new value is drawn to differ from the stored one where that is possible (a new
     * priority is picked from the three the ticket does not currently hold, a new assignee from the
     * known users other than the current one), so "the field took the new value" is a real
     * observation rather than one satisfied by coincidence. New titles are generated already trimmed,
     * so the value the caller supplies is exactly the value the service stores after its trim step.
     *
     * <p><strong>Validates: Requirements 4.1, 4.2</strong>
     */
    @Property(tries = 200)
    void partialUpdateChangesExactlyTheSuppliedFields(
            @ForAll("baseTickets") TicketState base,
            @ForAll("fieldSubsets") @Size(min = 1, max = 4) Set<Field> fields,
            @ForAll("titles") String newTitle,
            @ForAll("descriptions") String newDescription,
            @ForAll("priorities") TicketPriority newPriorityCandidate,
            @ForAll("assignees") String newAssigneeCandidate) {

        UUID id = UUID.randomUUID();
        Ticket stored = base.toEntity(id);

        // The candidate values are nudged to differ from what the ticket already holds, so a patched
        // field asserting the new value cannot pass by accidentally matching the old one.
        TicketPriority newPriority = differentPriority(base.priority(), newPriorityCandidate);
        String newAssignee = differentAssignee(base.assignee(), newAssigneeCandidate);

        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));
        given(ticketRepository.save(any(Ticket.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        UpdateTicketRequest request =
                new UpdateTicketRequest(
                        fields.contains(Field.TITLE) ? Patch.of(newTitle) : Patch.absent(),
                        fields.contains(Field.DESCRIPTION)
                                ? Patch.of(newDescription)
                                : Patch.absent(),
                        fields.contains(Field.PRIORITY) ? Patch.of(newPriority) : Patch.absent(),
                        fields.contains(Field.ASSIGNEE) ? Patch.of(newAssignee) : Patch.absent(),
                        base.version());

        TicketDetailResponse updated = ticketService.update(id, request, ACTOR);

        // Patched fields take the new value; omitted fields keep the stored one.
        assertThat(updated.title())
                .as("title patched=%s", fields.contains(Field.TITLE))
                .isEqualTo(fields.contains(Field.TITLE) ? newTitle : base.title());
        assertThat(updated.description())
                .as("description patched=%s", fields.contains(Field.DESCRIPTION))
                .isEqualTo(fields.contains(Field.DESCRIPTION) ? newDescription : base.description());
        assertThat(updated.priority())
                .as("priority patched=%s", fields.contains(Field.PRIORITY))
                .isEqualTo(fields.contains(Field.PRIORITY) ? newPriority : base.priority());
        assertThat(updated.assignee())
                .as("assignee patched=%s", fields.contains(Field.ASSIGNEE))
                .isEqualTo(fields.contains(Field.ASSIGNEE) ? newAssignee : base.assignee());

        // updatedAt strictly advances even though the clock is fixed at the ticket's own updatedAt.
        assertThat(updated.updatedAt())
                .as("updatedAt must strictly advance past %s", base.updatedAt())
                .isAfter(base.updatedAt());

        // status and createdAt are never touched by an update.
        assertThat(updated.status()).as("status must be unchanged").isEqualTo(base.status());
        assertThat(updated.createdAt())
                .as("createdAt must be unchanged")
                .isEqualTo(base.createdAt());
    }

    /** Picks a priority different from {@code current} when the candidate happens to equal it. */
    private static TicketPriority differentPriority(
            TicketPriority current, TicketPriority candidate) {
        if (candidate != current) {
            return candidate;
        }
        for (TicketPriority priority : TicketPriority.values()) {
            if (priority != current) {
                return priority;
            }
        }
        return candidate; // unreachable: the enum has more than one constant
    }

    /**
     * Picks an assignee different from {@code current} when possible. When the ticket is currently
     * unassigned any known user differs; when it is assigned, a known user other than the current one
     * is chosen.
     */
    private static String differentAssignee(String current, String candidate) {
        if (!candidate.equals(current)) {
            return candidate;
        }
        for (String user : KNOWN_USERS) {
            if (!user.equals(current)) {
                return user;
            }
        }
        return candidate; // unreachable: KNOWN_USERS has more than one member
    }

    /**
     * Base tickets spanning every status and priority, an assigned and an unassigned starting point,
     * and a non-trivial version, so the "omitted fields keep their value" clause is checked against a
     * varied starting state rather than one fixed row.
     *
     * <p>{@code updatedAt} is fixed at {@link #NOW} so it equals the fixed clock, which is what forces
     * the service's same-tick nudge and makes the strict-advance assertion meaningful.
     */
    @Provide
    Arbitrary<TicketState> baseTickets() {
        Arbitrary<String> startingAssignee =
                Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(1, Arbitraries.just((String) null)),
                        net.jqwik.api.Tuple.of(3, Arbitraries.of("alice", "bob", "carol")));

        return Combinators.combine(
                        Arbitraries.integers().between(0, 999),
                        Arbitraries.of(TicketStatus.class),
                        Arbitraries.of(TicketPriority.class),
                        startingAssignee,
                        Arbitraries.longs().between(0L, 500L))
                .as(
                        (serial, status, priority, assignee, version) ->
                                new TicketState(
                                        "Original title " + serial,
                                        "Original description " + serial,
                                        status,
                                        priority,
                                        assignee,
                                        version));
    }

    /** Every non-empty subset of the four updatable fields. */
    @Provide
    Arbitrary<Set<Field>> fieldSubsets() {
        return Arbitraries.subsetOf(Field.values())
                .ofMinSize(1)
                .map(EnumSet::copyOf);
    }

    /**
     * New titles that are already trimmed (no leading or trailing whitespace) and 1..200 characters,
     * so the value supplied equals the value stored after the service's trim step.
     */
    @Provide
    Arbitrary<String> titles() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(200)
                .map(s -> "T " + s)
                .map(s -> s.substring(0, Math.min(s.length(), 200)));
    }

    /** New descriptions, 0..5000 characters. */
    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings().ofMinLength(0).ofMaxLength(400).map(s -> "D " + s);
    }

    @Provide
    Arbitrary<TicketPriority> priorities() {
        return Arbitraries.of(TicketPriority.class);
    }

    /** New assignees, always drawn from the known-user set so the 422 validator accepts them. */
    @Provide
    Arbitrary<String> assignees() {
        return Arbitraries.of(KNOWN_USERS.toArray(new String[0]));
    }

    /** The pre-update state of a ticket; {@link #toEntity} materializes it for the mocked load. */
    record TicketState(
            String title,
            String description,
            TicketStatus status,
            TicketPriority priority,
            String assignee,
            long version) {

        Instant createdAt() {
            return NOW;
        }

        Instant updatedAt() {
            return NOW;
        }

        Ticket toEntity(UUID id) {
            Ticket ticket = new Ticket();
            ticket.setId(id);
            ticket.setTitle(title);
            ticket.setDescription(description);
            ticket.setStatus(status);
            ticket.setPriority(priority);
            ticket.setAssignee(assignee);
            ticket.setCreatedAt(createdAt());
            ticket.setUpdatedAt(updatedAt());
            ticket.setVersion(version);
            return ticket;
        }
    }
}
