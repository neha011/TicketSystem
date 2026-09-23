package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.exception.ForbiddenException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/** Service-layer authorization enforcement (Requirement 3.5). */
class DefaultTicketAuthorizationServiceTest {

    private final DefaultTicketAuthorizationService authorization = new DefaultTicketAuthorizationService();

    /** Every check, so a new one cannot be added without inheriting these cases. */
    static Stream<Arguments> checks() {
        DefaultTicketAuthorizationService service = new DefaultTicketAuthorizationService();
        return Stream.of(
                Arguments.of("requireCanView", (BiConsumer<String, Ticket>) service::requireCanView),
                Arguments.of("requireCanModify", (BiConsumer<String, Ticket>) service::requireCanModify),
                Arguments.of("requireCanComment", (BiConsumer<String, Ticket>) service::requireCanComment));
    }

    @ParameterizedTest(name = "{0} permits an identified actor")
    @MethodSource("checks")
    void shouldPermitIdentifiedActor(String name, BiConsumer<String, Ticket> check) {
        Ticket ticket = ticket("alice");

        assertThatCode(() -> check.accept("bob", ticket)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} denies a null actor")
    @MethodSource("checks")
    void shouldDenyNullActor(String name, BiConsumer<String, Ticket> check) {
        Ticket ticket = ticket("alice");

        assertThatThrownBy(() -> check.accept(null, ticket)).isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest(name = "{0} denies a blank actor")
    @MethodSource("checks")
    void shouldDenyBlankActor(String name, BiConsumer<String, Ticket> check) {
        Ticket ticket = ticket("alice");

        assertThatThrownBy(() -> check.accept("   ", ticket)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void shouldPermitListingForAnIdentifiedActor() {
        assertThatCode(() -> authorization.requireCanList("bob")).doesNotThrowAnyException();
    }

    @Test
    void shouldDenyListingForAnUnidentifiedActor() {
        assertThatThrownBy(() -> authorization.requireCanList(null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authorization.requireCanList("   ")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void shouldMapDenialTo403WithoutRevealingTicketData() {
        Ticket ticket = ticket("alice");
        ticket.setTitle("Payroll export is failing");

        ForbiddenException thrown =
                (ForbiddenException)
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> authorization.requireCanView(null, ticket));

        assertThat(thrown.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(thrown.fieldErrors()).isEmpty();
        // Req 3.5: a 403 body carries no ticket data.
        assertThat(thrown.clientMessage())
                .doesNotContain("Payroll export is failing")
                .doesNotContain(ticket.getId().toString())
                .doesNotContain("alice");
    }

    private static Ticket ticket(String assignee) {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setTitle("A ticket");
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setAssignee(assignee);
        ticket.setCreatedAt(Instant.parse("2026-09-05T10:15:30Z"));
        ticket.setUpdatedAt(Instant.parse("2026-09-05T10:15:30Z"));
        return ticket;
    }
}
