package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketsystem.ticket.config.UserDirectoryProperties;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.request.Patch;
import com.ticketsystem.ticket.dto.request.TicketQueryParams;
import com.ticketsystem.ticket.dto.request.UpdateTicketRequest;
import com.ticketsystem.ticket.dto.response.FieldError;
import com.ticketsystem.ticket.dto.response.PagedResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import com.ticketsystem.ticket.exception.ApiException;
import com.ticketsystem.ticket.exception.ForbiddenException;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.exception.UnprocessableEntityException;
import com.ticketsystem.ticket.exception.ValidationException;
import com.ticketsystem.ticket.repository.TicketRepository;
import com.ticketsystem.ticket.repository.TicketSpecifications;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

/**
 * Ticket creation and single-ticket retrieval (Requirements 1.1, 1.2, 3.1, 3.2, 3.5, 9.1).
 *
 * <p>The repository is mocked because persistence is a boundary; the mapper, authorization policy,
 * assignee validator, and clock are the real collaborators, so what is asserted is the behaviour a
 * caller actually gets rather than a rehearsal of stubbed returns. The clock is fixed so the stored
 * timestamps can be asserted exactly.
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");
    private static final String ACTOR = "alice";

    @Mock private TicketRepository ticketRepository;

    /**
     * Only used by the test that needs a policy which refuses an actor it recognized; the baseline
     * policy has no such branch, so it cannot be provoked into one.
     */
    @Mock private TicketAuthorizationService authorizationPolicy;

    private TicketService ticketService;

    @BeforeEach
    void setUp() {
        ticketService = serviceWithPolicy(new DefaultTicketAuthorizationService());
    }

    private TicketService serviceWithPolicy(TicketAuthorizationService policy) {
        UserDirectoryProperties directoryProperties = new UserDirectoryProperties();
        directoryProperties.setKnownUsers(Set.of("alice", "bob"));

        return new TicketServiceImpl(
                ticketRepository,
                new TicketMapper(new CommentMapper()),
                policy,
                new DirectoryBackedAssigneeValidator(new ConfiguredUserDirectory(directoryProperties)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldCreateTicketAsOpenWithBothTimestampsSet() {
        given(ticketRepository.save(any(Ticket.class))).willAnswer(saveAssigningId());

        TicketDetailResponse created =
                ticketService.create(
                        new CreateTicketRequest("  Login fails on SSO  ", "502 after redirect",
                                TicketPriority.HIGH, "bob"),
                        ACTOR);

        assertThat(created.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(created.id()).isNotNull();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        assertThat(created.title()).isEqualTo("Login fails on SSO");
        assertThat(created.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(created.assignee()).isEqualTo("bob");
        assertThat(created.comments()).isEmpty();
    }

    @Test
    void shouldRejectUnknownAssigneeWithoutPersisting() {
        UnprocessableEntityException thrown =
                catchThrowableOfType(
                        () ->
                                ticketService.create(
                                        new CreateTicketRequest(
                                                "Printer offline", null, TicketPriority.LOW, "dave"),
                                        ACTOR),
                        UnprocessableEntityException.class);

        assertThat(thrown).isNotNull();
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void shouldRejectCreateForUnidentifiedActorWithoutPersisting() {
        ForbiddenException thrown =
                catchThrowableOfType(
                        () ->
                                ticketService.create(
                                        new CreateTicketRequest(
                                                "Printer offline", null, TicketPriority.LOW, null),
                                        "  "),
                        ForbiddenException.class);

        assertThat(thrown).isNotNull();
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void shouldApplyDefaultPageAndSizeWhenNoneRequested() {
        given(ticketRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(storedTicket(UUID.randomUUID()))));

        PagedResponse<TicketSummaryResponse> listed = ticketService.list(null, ACTOR);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort()).isEqualTo(TicketSpecifications.defaultSort());
        assertThat(listed.page()).isZero();
        assertThat(listed.size()).isEqualTo(20);
        assertThat(listed.content()).hasSize(1);
    }

    @Test
    void shouldReportTotalsFromTheCountRatherThanThePageContents() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(storedTicket(id)), PageRequest.of(2, 5), 137L));

        PagedResponse<TicketSummaryResponse> listed =
                ticketService.list(new TicketQueryParams(2, 5, null, null), ACTOR);

        assertThat(listed.page()).isEqualTo(2);
        assertThat(listed.size()).isEqualTo(5);
        assertThat(listed.totalElements()).isEqualTo(137L);
        assertThat(listed.totalPages()).isEqualTo(28);
        assertThat(listed.content()).extracting(TicketSummaryResponse::id).containsExactly(id);
    }

    @Test
    void shouldReturnAnEmptySuccessfulPageBeyondTheLastPage() {
        given(ticketRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(99, 20), 7L));

        PagedResponse<TicketSummaryResponse> listed =
                ticketService.list(new TicketQueryParams(99, 20, null, null), ACTOR);

        assertThat(listed.content()).isEmpty();
        assertThat(listed.page()).isEqualTo(99);
        assertThat(listed.totalElements()).isEqualTo(7L);
        assertThat(listed.totalPages()).isEqualTo(1);
    }

    @Test
    void shouldReturnAZeroTotalPageWhenNothingHasEverBeenPersisted() {
        given(ticketRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        PagedResponse<TicketSummaryResponse> listed = ticketService.list(TicketQueryParams.defaults(), ACTOR);

        assertThat(listed.content()).isEmpty();
        assertThat(listed.totalElements()).isZero();
        assertThat(listed.totalPages()).isZero();
    }

    @Test
    void shouldTreatAbsentNullAndEmptyStatusAlikeAsUnfiltered() {
        given(ticketRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        // Only an absent value, an explicit null, and the empty string mean "no status filter"
        // (Req 7.4). A whitespace-only value is unrecognized, covered by the rejection test below.
        ticketService.list(new TicketQueryParams(0, 20, null, null), ACTOR);
        ticketService.list(new TicketQueryParams(0, 20, null, null), null, ACTOR);
        ticketService.list(new TicketQueryParams(0, 20, null, null), "", ACTOR);

        // All three ran a query: none was rejected, and none narrowed the result by status.
        verify(ticketRepository, times(3)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void shouldRejectAnUnrecognizedStatusReachingTheServiceAndReturnNoTickets() {
        // A whitespace-only value is unrecognized, not "unfiltered": it is neither the empty string
        // (Req 7.4) nor the exact name of a defined status (Req 7.3).
        for (String unrecognized : List.of("open", "Open", " OPEN", "ARCHIVED", "OPEN ", " ", "   ")) {
            ValidationException thrown =
                    catchThrowableOfType(
                            () ->
                                    ticketService.list(
                                            TicketQueryParams.defaults(), unrecognized, ACTOR),
                            ValidationException.class);

            assertThat(thrown).as("status %s", unrecognized).isNotNull();
            assertThat(thrown.fieldErrors()).extracting(FieldError::field).containsExactly("status");
        }

        verify(ticketRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void shouldRejectOutOfRangeParametersWithoutQuerying() {
        assertThat(
                        catchThrowableOfType(
                                () -> ticketService.list(new TicketQueryParams(-1, 20, null, null), ACTOR),
                                ValidationException.class))
                .isNotNull();
        assertThat(
                        catchThrowableOfType(
                                () -> ticketService.list(new TicketQueryParams(0, 101, null, null), ACTOR),
                                ValidationException.class))
                .isNotNull();
        // Req 6.6: an over-long keyword is refused even though it would have matched a ticket.
        assertThat(
                        catchThrowableOfType(
                                () ->
                                        ticketService.list(
                                                new TicketQueryParams(0, 20, "k".repeat(201), null), ACTOR),
                                ValidationException.class))
                .isNotNull();

        verify(ticketRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void shouldDenyListingForUnidentifiedActorWithoutQuerying() {
        assertThat(
                        catchThrowableOfType(
                                () -> ticketService.list(TicketQueryParams.defaults(), null),
                                ForbiddenException.class))
                .isNotNull();

        verify(ticketRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void shouldReturnStoredTicketById() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(storedTicket(id)));

        TicketDetailResponse found = ticketService.getById(id, ACTOR);

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.title()).isEqualTo("Login fails on SSO");
        assertThat(found.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(found.comments()).isEmpty();
    }

    @Test
    void shouldReturn404WhenTicketNotFound() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.empty());

        NotFoundException thrown =
                catchThrowableOfType(() -> ticketService.getById(id, ACTOR), NotFoundException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.clientMessage()).contains(id.toString());
        // Req 3.2: the lookup failure has to arrive at the boundary already carrying its 404.
        assertThat(thrown.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldDenyViewForUnidentifiedActor() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(storedTicket(id)));

        ForbiddenException thrown =
                catchThrowableOfType(() -> ticketService.getById(id, null), ForbiddenException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Req 3.5 with a policy that refuses an actor it <em>did</em> recognize, which the baseline
     * policy never does. Stubbing the gate is the only way to reach that branch, and it is worth
     * reaching: the interesting part is that the ticket was already loaded when the refusal happened,
     * so the response must still carry no ticket detail.
     */
    @Test
    void shouldDenyViewWhenThePolicyRefusesAnIdentifiedActor() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(storedTicket(id)));
        willThrow(new ForbiddenException())
                .given(authorizationPolicy)
                .requireCanView(ACTOR, storedTicket(id));

        TicketService service = serviceWithPolicy(authorizationPolicy);

        ForbiddenException thrown =
                catchThrowableOfType(() -> service.getById(id, ACTOR), ForbiddenException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(thrown.clientMessage())
                .isEqualTo(ForbiddenException.MESSAGE)
                .doesNotContain("Login fails on SSO", "502 after redirect", "bob", id.toString());
    }

    /**
     * Req 4.1, 4.2: the supplied fields take the new values, the omitted ones keep theirs, and
     * {@code updatedAt} moves. {@code status} and {@code createdAt} are asserted unchanged because the
     * update path must not be a way through the state machine (Requirements 8.1-8.6).
     */
    @Test
    void shouldApplyOnlyTheSuppliedFieldsAndAdvanceUpdatedAt() {
        UUID id = UUID.randomUUID();
        Ticket stored = storedTicket(id);
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));
        given(ticketRepository.save(any(Ticket.class))).willAnswer(invocation -> invocation.getArgument(0));

        TicketDetailResponse updated =
                ticketService.update(
                        id,
                        new UpdateTicketRequest(
                                Patch.of("  Login fails on SSO for EU tenants  "),
                                Patch.absent(),
                                Patch.of(TicketPriority.CRITICAL),
                                Patch.absent(),
                                0L),
                        ACTOR);

        assertThat(updated.title()).isEqualTo("Login fails on SSO for EU tenants");
        assertThat(updated.priority()).isEqualTo(TicketPriority.CRITICAL);
        assertThat(updated.description()).isEqualTo("502 after redirect");
        assertThat(updated.assignee()).isEqualTo("bob");
        assertThat(updated.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(updated.createdAt()).isEqualTo(NOW);
        assertThat(updated.updatedAt()).isAfter(NOW);
    }

    /** Req 4.2: an explicit null clears the assignment, and clearing needs no directory lookup. */
    @Test
    void shouldClearAssigneeOnExplicitNullAndLeaveItAloneWhenOmitted() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(storedTicket(id)));
        given(ticketRepository.save(any(Ticket.class))).willAnswer(invocation -> invocation.getArgument(0));

        TicketDetailResponse cleared =
                ticketService.update(
                        id,
                        new UpdateTicketRequest(
                                Patch.absent(), Patch.absent(), Patch.absent(), Patch.of(null), 0L),
                        ACTOR);

        assertThat(cleared.assignee()).isNull();

        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(storedTicket(id)));

        TicketDetailResponse untouched =
                ticketService.update(
                        id,
                        new UpdateTicketRequest(
                                Patch.absent(),
                                Patch.of("edited"),
                                Patch.absent(),
                                Patch.absent(),
                                0L),
                        ACTOR);

        assertThat(untouched.assignee()).isEqualTo("bob");
    }

    /**
     * Req 1.8: a persistence failure must reach the caller as a failure. The failure mode this guards
     * against is the tempting one — catching the repository exception and returning the unsaved,
     * in-memory ticket, which would report a ticket as created that no query will ever find.
     */
    @Test
    void shouldPropagateRepositoryPersistenceFailureRatherThanReportingSuccess() {
        given(ticketRepository.save(any(Ticket.class)))
                .willThrow(new DataIntegrityViolationException("could not execute statement"));

        Throwable thrown =
                catchThrowable(
                        () ->
                                ticketService.create(
                                        new CreateTicketRequest(
                                                "Login fails on SSO",
                                                "502 after redirect",
                                                TicketPriority.HIGH,
                                                "bob"),
                                        ACTOR));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        // Not translated into an ApiException here: a repository fault is not a client error, and the
        // advice maps DataAccessException to a 500 with a generic message (Req 1.8, 10.2).
        assertThat(thrown).isNotInstanceOf(ApiException.class);
    }

    /**
     * Req 4.4, 10.5: an update against an id that names no ticket is a 404 that touches nothing. The
     * failure has to arrive already carrying its status, and no save may be issued, because there is
     * no record to save and none must be created as a side effect.
     */
    @Test
    void shouldReturn404WhenUpdatingUnknownTicketWithoutSaving() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.empty());

        NotFoundException thrown =
                catchThrowableOfType(
                        () ->
                                ticketService.update(
                                        id,
                                        new UpdateTicketRequest(
                                                Patch.of("New title"),
                                                Patch.absent(),
                                                Patch.of(TicketPriority.CRITICAL),
                                                Patch.absent(),
                                                0L),
                                        ACTOR),
                        NotFoundException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thrown.clientMessage()).contains(id.toString());
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /**
     * Req 4.5, 10.5: a well-formed assignee that names no user is a 422, and the rejection must leave
     * the loaded ticket exactly as it was read — the assignee check runs before any field is applied,
     * so no mutation reaches the entity and no save is issued.
     */
    @Test
    void shouldReturn422WhenUpdatingWithUnknownAssigneeLeavingTicketUnmodified() {
        UUID id = UUID.randomUUID();
        Ticket stored = storedTicket(id);
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));

        UnprocessableEntityException thrown =
                catchThrowableOfType(
                        () ->
                                ticketService.update(
                                        id,
                                        new UpdateTicketRequest(
                                                Patch.of("Edited title"),
                                                Patch.absent(),
                                                Patch.of(TicketPriority.CRITICAL),
                                                Patch.of("dave"),
                                                0L),
                                        ACTOR),
                        UnprocessableEntityException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // The valid fields alongside the bad assignee must not have been applied: validation is atomic.
        assertThat(stored.getTitle()).isEqualTo("Login fails on SSO");
        assertThat(stored.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(stored.getAssignee()).isEqualTo("bob");
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /**
     * Req 10.5: validation is atomic. A request that mixes valid edits (a new description) with an
     * invalid one (clearing a field the ticket cannot be without) applies none of them — the entity
     * is left untouched and no save is issued, rather than the valid fields sneaking through.
     */
    @Test
    void shouldApplyNoFieldsWhenAValidAndInvalidFieldAreMixed() {
        UUID id = UUID.randomUUID();
        Ticket stored = storedTicket(id);
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));

        ValidationException thrown =
                catchThrowableOfType(
                        () ->
                                ticketService.update(
                                        id,
                                        new UpdateTicketRequest(
                                                Patch.absent(),
                                                Patch.of("A valid new description"),
                                                Patch.of(null),
                                                Patch.absent(),
                                                0L),
                                        ACTOR),
                        ValidationException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown.fieldErrors()).extracting(FieldError::field).contains("priority");
        // The valid description edit must not have been applied: nothing is written unless everything passes.
        assertThat(stored.getDescription()).isEqualTo("502 after redirect");
        assertThat(stored.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /** Stands in for the database assigning the generated identifier on persist. */
    private static org.mockito.stubbing.Answer<Ticket> saveAssigningId() {
        return invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(UUID.randomUUID());
            return ticket;
        };
    }

    private static Ticket storedTicket(UUID id) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTitle("Login fails on SSO");
        ticket.setDescription("502 after redirect");
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setAssignee("bob");
        ticket.setCreatedAt(NOW);
        ticket.setUpdatedAt(NOW);
        return ticket;
    }
}
