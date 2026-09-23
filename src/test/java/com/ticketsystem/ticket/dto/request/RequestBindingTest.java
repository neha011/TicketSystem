package com.ticketsystem.ticket.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.domain.TicketPriority;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * How the request DTOs bind from JSON, asserted through the {@link ObjectMapper} the application builds so
 * the behaviour matches a deployed instance.
 *
 * <p>The load-bearing case is {@link UpdateTicketRequest}: an omitted field and an explicitly null one must
 * arrive as distinguishable states, because the service treats the first as "leave unchanged" and the second
 * as "clear" (Requirement 4.2). A plain nullable field or {@code Optional} would merge them.
 */
class RequestBindingTest {

    private static final ApplicationContextRunner CONTEXT_RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void shouldDistinguishAnOmittedAssigneeFromAnExplicitlyNullOne() {
        withMapper(mapper -> {
            UpdateTicketRequest omitted = mapper.readValue("{\"version\":3}", UpdateTicketRequest.class);
            UpdateTicketRequest cleared =
                    mapper.readValue("{\"assignee\":null,\"version\":3}", UpdateTicketRequest.class);
            UpdateTicketRequest assigned =
                    mapper.readValue("{\"assignee\":\"a.patel\",\"version\":3}", UpdateTicketRequest.class);

            assertThat(omitted.assignee().isPresent()).isFalse();
            assertThat(omitted.hasNoFields()).isTrue();

            assertThat(cleared.assignee().isPresent()).isTrue();
            assertThat(cleared.assignee().isNull()).isTrue();
            assertThat(cleared.hasNoFields()).isFalse();

            assertThat(assigned.assignee().isPresent()).isTrue();
            assertThat(assigned.assignee().get()).isEqualTo("a.patel");

            assertThat(omitted.assignee()).isNotEqualTo(cleared.assignee());
        });
    }

    @Test
    void shouldBindOnlyTheSuppliedUpdateFields() {
        withMapper(mapper -> {
            String json = "{\"title\":\"Login fails on SSO for EU tenants\",\"priority\":\"CRITICAL\",\"version\":3}";

            UpdateTicketRequest request = mapper.readValue(json, UpdateTicketRequest.class);

            assertThat(request.title().get()).isEqualTo("Login fails on SSO for EU tenants");
            assertThat(request.priority().get()).isEqualTo(TicketPriority.CRITICAL);
            assertThat(request.description().isPresent()).isFalse();
            assertThat(request.assignee().isPresent()).isFalse();
            assertThat(request.version()).isEqualTo(3L);
        });
    }

    /** Blank text must survive binding untouched so the trimmed-length constraint can judge it (Req 5.8). */
    @Test
    void shouldBindBlankTextWithoutTrimmingOrNullingIt() {
        withMapper(mapper -> {
            CreateTicketRequest create =
                    mapper.readValue("{\"title\":\"  \",\"priority\":\"LOW\"}", CreateTicketRequest.class);
            CreateCommentRequest comment =
                    mapper.readValue("{\"content\":\"\\t \"}", CreateCommentRequest.class);
            UpdateTicketRequest update =
                    mapper.readValue("{\"title\":\" \",\"version\":1}", UpdateTicketRequest.class);

            assertThat(create.title()).isEqualTo("  ");
            assertThat(comment.content()).isEqualTo("\t ");
            assertThat(update.title().get()).isEqualTo(" ");
        });
    }

    /** An undefined enum name fails at parse time, inside a Patch as much as outside one (Req 1.5, 8.8). */
    @Test
    void shouldRejectAnUndefinedEnumNameInAnyRequestShape() {
        withMapper(mapper -> {
            assertThatThrownBy(() ->
                            mapper.readValue("{\"title\":\"t\",\"priority\":\"URGENT\"}", CreateTicketRequest.class))
                    .isInstanceOf(InvalidFormatException.class);
            assertThatThrownBy(() ->
                            mapper.readValue("{\"priority\":\"urgent\",\"version\":1}", UpdateTicketRequest.class))
                    .isInstanceOf(InvalidFormatException.class);
            assertThatThrownBy(() ->
                            mapper.readValue("{\"status\":\" OPEN\",\"version\":1}", StatusTransitionRequest.class))
                    .isInstanceOf(InvalidFormatException.class);
        });
    }

    /**
     * The comment author comes from the authenticated principal, so the DTO has no {@code author}
     * component for one to bind to. A client that sends one has it dropped on the floor: the request still
     * succeeds, but nothing downstream can see the supplied value, so a comment can never be attributed to
     * somebody else (Requirement 5.6).
     */
    @Test
    void shouldIgnoreAnAuthorSuppliedInACommentBody() {
        withMapper(mapper -> {
            CreateCommentRequest request = mapper.readValue(
                    "{\"content\":\"hello\",\"author\":\"attacker\"}", CreateCommentRequest.class);

            assertThat(request.content()).isEqualTo("hello");
            assertThat(CreateCommentRequest.class.getRecordComponents()).hasSize(1);
        });
    }

    private static void withMapper(JsonAssertions assertions) {
        CONTEXT_RUNNER.run(context -> assertions.accept(context.getBean(ObjectMapper.class)));
    }

    @FunctionalInterface
    private interface JsonAssertions {
        void accept(ObjectMapper mapper) throws JsonProcessingException;
    }
}
