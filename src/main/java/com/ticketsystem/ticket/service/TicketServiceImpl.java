package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.request.TicketQueryParams;
import com.ticketsystem.ticket.dto.request.UpdateTicketRequest;
import com.ticketsystem.ticket.dto.response.FieldError;
import com.ticketsystem.ticket.dto.response.PagedResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.exception.ValidationException;
import com.ticketsystem.ticket.repository.TicketRepository;
import com.ticketsystem.ticket.repository.TicketSpecifications;
import com.ticketsystem.ticket.validation.TrimmedSizeValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link TicketService}: owns the "new tickets start OPEN" rule, the clock, the version check
 * on writes, and the transaction boundary around every ticket read and write (Requirements 1.1, 1.2,
 * 3.1, 3.5, 4.1-4.6, 9.1).
 *
 * <p>Two ordering rules hold throughout this class and are the reason the operations are safe:
 *
 * <ul>
 *   <li><b>Authorize after loading, before returning or mutating.</b> A policy decision may depend on
 *       the ticket's assignee or state, so the entity has to be in hand first; running the check
 *       before anything is written or handed back means a denied request can neither modify data nor
 *       leak it (Requirement 3.5).
 *   <li><b>Validate everything before touching the entity.</b> Nothing is mutated or saved until every
 *       check has passed, so a rejected request leaves the database exactly as it was
 *       (Requirements 9.2, 10.5).
 * </ul>
 */
@Service
public class TicketServiceImpl implements TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketServiceImpl.class);

    /**
     * Status every ticket starts in (Requirement 1.1).
     *
     * <p>Named here rather than passed in: creation is the only way a ticket enters the lifecycle, and
     * the state machine has no transition back into OPEN, so this is the single point at which the
     * initial state is decided.
     */
    private static final TicketStatus INITIAL_STATUS = TicketStatus.OPEN;

    private final TicketRepository ticketRepository;
    private final TicketMapper ticketMapper;
    private final TicketAuthorizationService authorizationService;
    private final AssigneeValidator assigneeValidator;
    private final Clock clock;

    public TicketServiceImpl(
            TicketRepository ticketRepository,
            TicketMapper ticketMapper,
            TicketAuthorizationService authorizationService,
            AssigneeValidator assigneeValidator,
            Clock clock) {
        this.ticketRepository = Objects.requireNonNull(ticketRepository, "ticketRepository");
        this.ticketMapper = Objects.requireNonNull(ticketMapper, "ticketMapper");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.assigneeValidator = Objects.requireNonNull(assigneeValidator, "assigneeValidator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The write is wrapped in a transaction that commits before this method returns, so a caller
     * that receives a response knows the ticket is durable and a persistence failure surfaces as a
     * failure rather than a reported success (Requirements 1.8, 9.1, 9.2).
     *
     * <p>{@code createdAt} and {@code updatedAt} are taken from a single {@code Instant} read, so a
     * freshly created ticket reports them equal rather than microseconds apart — which is what lets
     * "has this ticket been modified?" be answered by comparing the two.
     */
    @Override
    @Transactional
    public TicketDetailResponse create(CreateTicketRequest request, String actor) {
        Objects.requireNonNull(request, "request");

        Instant now = clock.instant();
        // Built before authorizing because the authorization contract is stated over a ticket; this
        // instance is detached and unsaved, so a denial below writes nothing.
        Ticket ticket = ticketMapper.toNewEntity(request, INITIAL_STATUS, now);

        authorizationService.requireCanModify(actor, ticket);
        // A well-formed assignee that names no user is a 422. Checked before the save so a rejected
        // request never reaches the database (Req 4.5, 10.5).
        assigneeValidator.requireExists(request.assignee());

        Ticket saved = ticketRepository.save(ticket);
        log.info("Ticket {} created with status {} by {}", saved.getId(), saved.getStatus(), actor);
        return ticketMapper.toDetail(saved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Order of work is the whole point here: the parameters are checked first, so a rejected request
     * never runs a query and therefore cannot return ticket data alongside its error — which is what
     * Requirement 6.6 asks for when an over-long keyword would in fact have matched something. Only
     * then are the predicates composed and the page fetched.
     *
     * <p>{@code totalElements} comes from the count query rather than from the size of the returned
     * content, so it describes the whole result set and is identical no matter which page was asked
     * for; {@code totalPages} is derived from it by {@link PagedResponse#of} so the two cannot
     * disagree (Requirement 2.3). A page index at or past the end therefore comes back as empty content
     * with intact metadata and no error (Requirements 2.5, 9.8).
     */
    @Override
    @Transactional(readOnly = true)
    public PagedResponse<TicketSummaryResponse> list(TicketQueryParams params, String actor) {
        TicketQueryParams query = (params == null) ? TicketQueryParams.defaults() : params;
        // Both gates close before the query runs: a denied caller learns nothing, not even the total
        // count, and a rejected parameter cannot come back alongside ticket data (Req 3.5, 6.6).
        authorizationService.requireCanList(actor);
        requireListableParams(query);

        Pageable pageable =
                PageRequest.of(query.page(), query.size(), TicketSpecifications.defaultSort());
        Specification<Ticket> specification =
                TicketSpecifications.matching(query.keyword(), query.status());

        Page<Ticket> page = ticketRepository.findAll(specification, pageable);
        List<Ticket> tickets = page.getContent();

        return PagedResponse.of(
                ticketMapper.toSummaryList(tickets),
                query.page(),
                query.size(),
                page.getTotalElements());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The status string is resolved before anything else runs, so an unrecognized value is rejected
     * with zero tickets returned rather than being dropped and silently treated as "unfiltered" —
     * which would hand back tickets outside the requested filter (Requirement 7.5).
     */
    @Override
    @Transactional(readOnly = true)
    public PagedResponse<TicketSummaryResponse> list(
            TicketQueryParams params, String rawStatus, String actor) {
        TicketQueryParams query = (params == null) ? TicketQueryParams.defaults() : params;
        TicketStatus resolved = TicketService.resolveStatusFilter(rawStatus).orElse(null);

        return list(
                new TicketQueryParams(query.page(), query.size(), query.keyword(), resolved), actor);
    }

    /**
     * Re-checks the list parameters the request boundary already validated (Requirements 2.4, 6.4,
     * 6.6, 10.1).
     *
     * <p>Duplicating the constraints looks redundant next to the annotations on
     * {@link TicketQueryParams}, and for an HTTP caller it is. It is not redundant for any other
     * caller: the record can be constructed directly, and a {@code size} of 10 000 reaching the
     * repository would pull ten thousand rows into memory whatever the annotation said. Checking here
     * means the bound holds for every entry point, not just the validated one.
     *
     * <p>All failing parameters are reported together, so a caller sending two bad values learns about
     * both at once (Requirement 10.2).
     */
    private static void requireListableParams(TicketQueryParams query) {
        List<FieldError> failures = new ArrayList<>(3);
        if (query.page() < 0) {
            failures.add(new FieldError("page", "must be 0 or greater"));
        }
        if (query.size() < TicketQueryParams.MIN_SIZE || query.size() > TicketQueryParams.MAX_SIZE) {
            failures.add(
                    new FieldError(
                            "size",
                            "must be "
                                    + TicketQueryParams.MIN_SIZE
                                    + " to "
                                    + TicketQueryParams.MAX_SIZE));
        }
        String keyword = query.keyword();
        if (keyword != null
                && (keyword.length() < TicketQueryParams.MIN_KEYWORD_LENGTH
                        || keyword.length() > TicketQueryParams.MAX_KEYWORD_LENGTH)) {
            failures.add(
                    new FieldError(
                            "keyword",
                            "must be "
                                    + TicketQueryParams.MIN_KEYWORD_LENGTH
                                    + " to "
                                    + TicketQueryParams.MAX_KEYWORD_LENGTH
                                    + " characters"));
        }
        if (!failures.isEmpty()) {
            throw new ValidationException("Invalid ticket list request", failures);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read-only transaction: the comments are fetched inside it and the aggregate is mapped to a
     * DTO before it closes, so nothing is left as a lazy proxy for the serializer to trip over
     * ({@code spring.jpa.open-in-view} is false).
     */
    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getById(UUID id, String actor) {
        Objects.requireNonNull(id, "id");

        Ticket ticket = requireTicket(id);
        // After the load, so the policy can consider the ticket; before the mapping, so a denied
        // caller never receives ticket data (Req 3.5).
        authorizationService.requireCanView(actor, ticket);
        return ticketMapper.toDetail(ticket);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The order of the checks is the substance of this method, not incidental. Load, authorize,
     * compare the supplied version, resolve the assignee — and only then touch the entity. Every
     * rejection therefore happens while the loaded ticket is still untouched, so a failed update
     * leaves the record exactly as it was without needing a rollback to undo a half-applied change
     * (Requirements 4.4, 4.5, 4.6, 10.5). The whole sequence runs in one transaction, so even the
     * save is undone if the flush fails.
     *
     * <p>The version comparison is explicit rather than left to JPA's own {@code @Version} check on
     * flush. Hibernate only notices a conflict when a <em>concurrent</em> write has bumped the version
     * between this transaction's read and its flush; a client editing a ticket it read ten minutes ago
     * would otherwise have its stale write applied silently, because by flush time the version it
     * never looked at matches the row it is about to overwrite. Comparing what the client claims to
     * have seen against what is stored catches that case, which is the one Requirement 4.6 is about.
     * The {@code @Version} check still runs underneath and covers the narrower interleaving.
     *
     * <p>{@code status} is absent from {@link UpdateTicketRequest} and so cannot be reached from here:
     * a status change is only possible through the transition service, which consults the transition
     * table (Requirements 8.1-8.6).
     */
    @Override
    @Transactional
    public TicketDetailResponse update(UUID id, UpdateTicketRequest request, String actor) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");

        Ticket ticket = requireTicket(id);
        authorizationService.requireCanModify(actor, ticket);
        requireCurrentVersion(ticket, request.version());
        requireUpdatableFieldValues(request);
        // 422 for a well-formed assignee naming no user. An absent assignee is not checked at all,
        // and an explicit null means "unassign", which needs no directory lookup (Req 4.2, 4.5).
        if (request.assignee().isPresent()) {
            assigneeValidator.requireExists(request.assignee().orElseNull());
        }

        // Past this line every check has passed, so the mutations below cannot be stranded.
        request.title().ifPresent(title -> ticket.setTitle(TrimmedSizeValidator.trim(title)));
        request.description().ifPresent(ticket::setDescription);
        request.priority().ifPresent(ticket::setPriority);
        request.assignee().ifPresent(ticket::setAssignee);
        ticket.setUpdatedAt(nextUpdatedAt(ticket.getUpdatedAt()));

        Ticket saved = ticketRepository.save(ticket);
        log.info("Ticket {} fields updated by {}", saved.getId(), actor);
        return ticketMapper.toDetail(saved);
    }

    /**
     * Rejects a write whose expected version does not match the stored one (Requirement 4.6).
     *
     * <p>The message names both versions because that is what tells the user their copy is stale, and
     * both values are ones they either sent or are entitled to read back.
     */
    private static void requireCurrentVersion(Ticket ticket, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != ticket.getVersion()) {
            throw new ConflictException(
                    "Ticket "
                            + ticket.getId()
                            + " was modified by another request: expected version "
                            + expectedVersion
                            + " but the stored version is "
                            + ticket.getVersion());
        }
    }

    /**
     * Refuses to clear a field the ticket cannot be without (Requirements 4.3, 10.5).
     *
     * <p>This is not a second copy of the DTO's shape rules — trimmed lengths and priority spelling
     * stay at the request boundary, where {@code @TrimmedSize} and enum binding already judge them.
     * What is checked here is the entity's own invariant: a ticket always has a title and a priority.
     * An HTTP caller cannot breach it, since both fields carry {@code @NotNull}, but a direct caller
     * could, and the result would otherwise be a null column that fails at flush as a 500 instead of
     * being named as the bad field it is.
     */
    private static void requireUpdatableFieldValues(UpdateTicketRequest request) {
        List<FieldError> failures = new ArrayList<>(2);
        if (request.title().isNull()) {
            failures.add(new FieldError("title", "must not be null"));
        }
        if (request.priority().isNull()) {
            failures.add(new FieldError("priority", "must not be null"));
        }
        if (!failures.isEmpty()) {
            throw new ValidationException("Invalid ticket update request", failures);
        }
    }

    /**
     * The instant to stamp a mutation with: the clock's reading, or the smallest value strictly after
     * {@code previousUpdatedAt} when the clock has not visibly moved since the last write.
     *
     * <p>A plain {@code clock.instant()} would let two updates within the same clock tick record an
     * identical {@code updatedAt}, making a second edit indistinguishable from no edit at all — and
     * "is this ticket newer than the copy I hold?" is exactly what this field is read for. The nudge
     * is one microsecond, not one nanosecond, because PostgreSQL's {@code timestamptz} resolution is
     * microseconds and a nanosecond bump would be truncated away on store.
     */
    private Instant nextUpdatedAt(Instant previousUpdatedAt) {
        Instant now = clock.instant();
        if (previousUpdatedAt == null || now.isAfter(previousUpdatedAt)) {
            return now;
        }
        return previousUpdatedAt.plus(1, ChronoUnit.MICROS);
    }

    /**
     * Loads a ticket with its comments or fails with a 404 (Requirement 3.2).
     *
     * <p>The message names the identifier the caller supplied, which tells them nothing they did not
     * already send and is what the UI renders as "ticket not found".
     */
    private Ticket requireTicket(UUID id) {
        return ticketRepository
                .findWithCommentsById(id)
                .orElseThrow(() -> new NotFoundException("Ticket " + id + " not found"));
    }
}
