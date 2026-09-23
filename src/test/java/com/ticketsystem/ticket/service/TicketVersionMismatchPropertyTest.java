package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketsystem.ticket.config.UserDirectoryProperties;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.dto.request.Patch;
import com.ticketsystem.ticket.dto.request.UpdateTicketRequest;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for optimistic-lock version handling on the write path (Property 14; Req 4.6,
 * 8.9).
 *
 * <p>Over jqwik-generated {@code (storedVersion, suppliedVersion)} pairs, the update succeeds exactly
 * when the two are equal; on any mismatch the service throws {@link ConflictException} (which maps to
 * 409), leaves every field of the loaded ticket untouched, and never calls {@code save}. Because the
 * repository is the only mocked collaborator and the save stub echoes its argument back, "the ticket
 * is unchanged" is asserted against the real {@link TicketDetailResponse} the caller would receive on
 * success rather than against stubbed returns.
 *
 * <p>jqwik builds a fresh instance per try and does not honour the Jupiter/Mockito extensions, so the
 * mock and the service are wired up by hand in the constructor, matching {@code
 * TicketPartialUpdatePropertyTest}.
 *
 * <h2>Scope note — status-transition path</h2>
 *
 * <p>Property 14 also names the status-transition path (Req 8.9). That path lives in {@code
 * TicketStatusTransitionService}, whose implementation is task 10.1 and does not yet exist in the code
 * base — only {@code TicketServiceImpl.update} (task 9.1) is available. This test therefore covers the
 * update path in full and leaves the transition path uncovered until 10.1 lands. The version-check
 * semantics of both paths are designed to be identical (load → authorize → compare supplied version
 * against stored → 409 on mismatch, before the entity is touched), so the transition assertions will
 * mirror {@link #updateSucceedsIffSuppliedVersionMatchesStored} once the collaborator exists.
 */
class TicketVersionMismatchPropertyTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");
    private static final String ACTOR = "alice";

    /** Users the assignee validator will accept; unused by this test but required to wire the service. */
    private static final Set<String> KNOWN_USERS = Set.of("alice", "bob", "carol", "dave");

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketService ticketService;

    TicketVersionMismatchPropertyTest() {
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

    // Feature: support-ticket-management, Property 14: Version mismatch is rejected as a conflict
    /**
     * Property 14: an update succeeds if and only if the supplied version equals the stored one;
     * otherwise it is a 409 conflict that mutates nothing.
     *
     * <p>The request touches only {@code description} (a nullable, always-valid field), so the sole
     * variable in play is the version comparison — no assignee lookup or field-shape rule can mask or
     * confound the version verdict. For every generated {@code (storedVersion, suppliedVersion)} pair:
     *
     * <ul>
     *   <li><b>equal:</b> the update is applied — the response carries the new description, {@code
     *       updatedAt} strictly advances, and {@code save} is invoked once;
     *   <li><b>mismatched:</b> a {@link ConflictException} is thrown, the loaded ticket's status,
     *       fields, {@code updatedAt}, and {@code version} are byte-for-byte what they were before the
     *       call, and {@code save} is never invoked (Req 4.6).
     * </ul>
     *
     * <p>The stored and supplied versions are drawn independently over a small range so equal pairs
     * arise often enough to exercise the success branch, while the far larger space of unequal pairs
     * exercises the conflict branch.
     *
     * <p><strong>Validates: Requirements 4.6, 8.9</strong>
     */
    @Property(tries = 300)
    void updateSucceedsIffSuppliedVersionMatchesStored(
            @ForAll("baseTickets") TicketState base,
            @ForAll("versions") long suppliedVersion,
            @ForAll("descriptions") String newDescription) {

        UUID id = UUID.randomUUID();
        Ticket stored = base.toEntity(id);

        // jqwik reuses one test instance across all tries, so the shared mock accumulates invocations
        // from earlier tries. Clear them here so the save verifications below are scoped to this try.
        clearInvocations(ticketRepository);

        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));
        given(ticketRepository.save(any(Ticket.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        UpdateTicketRequest request =
                new UpdateTicketRequest(
                        Patch.absent(),
                        Patch.of(newDescription),
                        Patch.absent(),
                        Patch.absent(),
                        suppliedVersion);

        boolean versionsMatch = suppliedVersion == base.version();

        if (versionsMatch) {
            TicketDetailResponse updated = ticketService.update(id, request, ACTOR);

            assertThat(updated.description())
                    .as("matching version applies the update")
                    .isEqualTo(newDescription);
            assertThat(updated.updatedAt())
                    .as("updatedAt strictly advances on a successful update")
                    .isAfter(base.updatedAt());
            assertThat(updated.status()).as("status is untouched by an update").isEqualTo(base.status());
            assertThat(updated.version())
                    .as("stored version is preserved on the returned ticket")
                    .isEqualTo(base.version());
            verify(ticketRepository).save(any(Ticket.class));
        } else {
            assertThatThrownBy(() -> ticketService.update(id, request, ACTOR))
                    .as(
                            "mismatch stored=%s supplied=%s must be a 409 conflict",
                            base.version(), suppliedVersion)
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).status().value())
                    .isEqualTo(409);

            // The loaded entity must be exactly as it was: no field, timestamp, or version touched.
            assertThat(stored.getTitle()).isEqualTo(base.title());
            assertThat(stored.getDescription()).isEqualTo(base.description());
            assertThat(stored.getStatus()).isEqualTo(base.status());
            assertThat(stored.getPriority()).isEqualTo(base.priority());
            assertThat(stored.getAssignee()).isEqualTo(base.assignee());
            assertThat(stored.getCreatedAt()).isEqualTo(base.createdAt());
            assertThat(stored.getUpdatedAt())
                    .as("updatedAt must be untouched on a rejected update")
                    .isEqualTo(base.updatedAt());
            assertThat(stored.getVersion())
                    .as("version must be untouched on a rejected update")
                    .isEqualTo(base.version());

            // No write reaches the repository when the version check fails.
            verify(ticketRepository, never()).save(any(Ticket.class));
        }
    }

    /**
     * Base tickets spanning every status and priority, an assigned and an unassigned starting point,
     * and a version drawn from the same small range as the supplied version so equal pairs occur.
     *
     * <p>{@code updatedAt} is fixed at {@link #NOW} so it equals the fixed clock, forcing the service's
     * same-tick nudge and making the strict-advance assertion on the success branch meaningful.
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
                        versions())
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

    /**
     * Versions over a small range shared by the stored and supplied values, so that matching pairs
     * (the success branch) and mismatched pairs (the conflict branch) both occur frequently.
     */
    @Provide
    Arbitrary<Long> versions() {
        return Arbitraries.longs().between(0L, 8L);
    }

    /** New descriptions, always valid (0..5000 characters), so only the version verdict is in play. */
    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings().ofMinLength(0).ofMaxLength(400).map(s -> "D " + s);
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
