package com.ticketsystem.ticket.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Constraint-level behaviour of the request DTOs, asserted through a real {@link Validator} rather than by
 * reading annotations, so the tests describe what a request actually gets rejected for.
 *
 * <p>Assertions are on the <em>set</em> of violating property paths, which is what the error response has to
 * report: every failing field, and no field that is actually valid (Requirements 4.10, 10.2).
 */
class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void shouldAcceptAFullyPopulatedCreateRequest() {
        CreateTicketRequest request =
                new CreateTicketRequest("Login fails on SSO", "Users see a 502.", TicketPriority.HIGH, "a.patel");

        assertThat(violatedFields(request)).isEmpty();
    }

    @Test
    void shouldAcceptACreateRequestWithOnlyTheMandatoryFields() {
        CreateTicketRequest request = new CreateTicketRequest("Login fails on SSO", null, TicketPriority.LOW, null);

        assertThat(violatedFields(request)).isEmpty();
    }

    /** Whitespace-only is zero-length under the trimmed rule, so it is rejected like a missing title. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u00A0", "\u3000"})
    void shouldRejectABlankCreateTitle(String blankTitle) {
        CreateTicketRequest request = new CreateTicketRequest(blankTitle, null, TicketPriority.LOW, null);

        assertThat(violatedFields(request)).containsExactly("title");
    }

    @Test
    void shouldRejectAMissingCreateTitleAndMissingPriorityTogether() {
        CreateTicketRequest request = new CreateTicketRequest(null, null, null, null);

        assertThat(violatedFields(request)).containsExactlyInAnyOrder("title", "priority");
    }

    @Test
    void shouldAcceptATitleOfExactlyTwoHundredTrimmedCharactersAndRejectOneMore() {
        String maximum = "t".repeat(200);

        assertThat(violatedFields(new CreateTicketRequest("  " + maximum + "  ", null, TicketPriority.LOW, null)))
                .isEmpty();
        assertThat(violatedFields(new CreateTicketRequest(maximum + "t", null, TicketPriority.LOW, null)))
                .containsExactly("title");
    }

    @Test
    void shouldReportEveryOverLongCreateFieldAtOnce() {
        CreateTicketRequest request = new CreateTicketRequest(
                "t".repeat(201), "d".repeat(5001), TicketPriority.LOW, "a".repeat(101));

        assertThat(violatedFields(request)).containsExactlyInAnyOrder("title", "description", "assignee");
    }

    @Test
    void shouldLeaveOmittedUpdateFieldsUnvalidated() {
        UpdateTicketRequest request = new UpdateTicketRequest(
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), 3L);

        assertThat(violatedFields(request)).isEmpty();
        assertThat(request.hasNoFields()).isTrue();
    }

    @Test
    void shouldValidateOnlyTheSuppliedUpdateFields() {
        UpdateTicketRequest request = new UpdateTicketRequest(
                Patch.of("   "), Patch.absent(), Patch.absent(), Patch.of("a".repeat(101)), 3L);

        assertThat(violatedFields(request)).containsExactlyInAnyOrder("title", "assignee");
    }

    /** Clearing a nullable field is a legitimate edit; clearing a mandatory one is not. */
    @Test
    void shouldAcceptClearingAssigneeAndDescriptionButRejectClearingTitleOrPriority() {
        UpdateTicketRequest clearing = new UpdateTicketRequest(
                Patch.absent(), Patch.of(null), Patch.absent(), Patch.of(null), 3L);
        UpdateTicketRequest nulling = new UpdateTicketRequest(
                Patch.of(null), Patch.absent(), Patch.of(null), Patch.absent(), 3L);

        assertThat(violatedFields(clearing)).isEmpty();
        assertThat(violatedFields(nulling)).containsExactlyInAnyOrder("title", "priority");
    }

    @Test
    void shouldRequireAVersionOnUpdateAndOnStatusTransition() {
        UpdateTicketRequest update = new UpdateTicketRequest(
                Patch.of("A new title"), Patch.absent(), Patch.absent(), Patch.absent(), null);

        assertThat(violatedFields(update)).containsExactly("version");
        assertThat(violatedFields(new StatusTransitionRequest(TicketStatus.IN_PROGRESS, null)))
                .containsExactly("version");
    }

    @Test
    void shouldRequireATargetStatusOnATransitionRequest() {
        assertThat(violatedFields(new StatusTransitionRequest(null, 3L))).containsExactly("status");
        assertThat(violatedFields(new StatusTransitionRequest(TicketStatus.CLOSED, 0L))).isEmpty();
    }

    @Test
    void shouldRejectMissingAndBlankCommentContent() {
        assertThat(violatedFields(new CreateCommentRequest(null))).containsExactly("content");
        assertThat(violatedFields(new CreateCommentRequest(" \t \u00A0 "))).containsExactly("content");
        assertThat(violatedFields(new CreateCommentRequest("c".repeat(5001)))).containsExactly("content");
        assertThat(violatedFields(new CreateCommentRequest("Escalated to the platform team."))).isEmpty();
    }

    @Test
    void shouldDefaultAbsentPageAndSizeWithoutTouchingSuppliedValues() {
        TicketQueryParams defaults = TicketQueryParams.defaults();

        assertThat(defaults.page()).isZero();
        assertThat(defaults.size()).isEqualTo(20);
        assertThat(defaults.keywordFilter()).isEmpty();
        assertThat(defaults.statusFilter()).isEmpty();
        assertThat(violatedFields(defaults)).isEmpty();

        // A supplied out-of-range page is preserved for the validator, never corrected.
        assertThat(new TicketQueryParams(-1, null, null, null).page()).isEqualTo(-1);
    }

    @Test
    void shouldRejectOutOfRangePagingValues() {
        assertThat(violatedFields(new TicketQueryParams(-1, 20, null, null))).containsExactly("page");
        assertThat(violatedFields(new TicketQueryParams(Integer.MIN_VALUE, 20, null, null)))
                .containsExactly("page");
        assertThat(violatedFields(new TicketQueryParams(0, 0, null, null))).containsExactly("size");
        assertThat(violatedFields(new TicketQueryParams(0, 101, null, null))).containsExactly("size");
        assertThat(violatedFields(new TicketQueryParams(0, Integer.MAX_VALUE, null, null)))
                .containsExactly("size");
        assertThat(violatedFields(new TicketQueryParams(-1, 101, null, null)))
                .containsExactlyInAnyOrder("page", "size");
    }

    @Test
    void shouldAcceptTheBoundaryPagingValues() {
        assertThat(violatedFields(new TicketQueryParams(0, 1, null, null))).isEmpty();
        assertThat(violatedFields(new TicketQueryParams(Integer.MAX_VALUE, 100, null, null))).isEmpty();
    }

    @Test
    void shouldConstrainKeywordLengthOnlyWhenOneIsSupplied() {
        assertThat(violatedFields(new TicketQueryParams(0, 20, null, TicketStatus.OPEN))).isEmpty();
        assertThat(violatedFields(new TicketQueryParams(0, 20, "s", null))).isEmpty();
        assertThat(violatedFields(new TicketQueryParams(0, 20, "k".repeat(200), null))).isEmpty();
        assertThat(violatedFields(new TicketQueryParams(0, 20, "", null))).containsExactly("keyword");
        assertThat(violatedFields(new TicketQueryParams(0, 20, "k".repeat(201), null)))
                .containsExactly("keyword");
    }

    /** @return the property paths of every violated constraint, which is what {@code fieldErrors} reports. */
    private static Set<String> violatedFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
