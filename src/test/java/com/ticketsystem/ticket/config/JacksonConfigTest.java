package com.ticketsystem.ticket.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Behavioral tests over the {@link ObjectMapper} the application actually builds.
 *
 * <p>The mapper is obtained from a context containing Boot's Jackson auto-configuration plus {@link
 * JacksonConfig}, so these assertions describe the wire behavior of a deployed instance rather than
 * that of a hand-assembled mapper.
 */
class JacksonConfigTest {

    private static final ApplicationContextRunner CONTEXT_RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    /**
     * Instants chosen to exercise the cases where a numeric or lossy rendering would differ from the
     * string contract: a whole second, sub-second precision at milli and nano scale, and the epoch
     * itself (which serializes as the number {@code 0} when the feature is left enabled).
     */
    private static final List<Instant> ISO_TIMESTAMP_CASES = List.of(
            Instant.parse("2026-09-05T10:15:30Z"),
            Instant.parse("2026-09-05T10:15:30.123Z"),
            Instant.parse("2026-09-05T10:15:30.123456789Z"),
            Instant.EPOCH);

    /** Payload shaped like the request/response DTOs, kept local so this test owns its fixture. */
    record Payload(String title, TicketPriority priority, TicketStatus status, Instant createdAt) {}

    /** An undefined enum name must fail parsing so the advice can answer 400 (Req 1.5, 8.8). */
    @ParameterizedTest
    @ValueSource(strings = {"URGENT", "low", "Low", " LOW", "LOW ", ""})
    void shouldRejectUndefinedPriorityValuesInsteadOfCoercingThemToNull(String undefinedPriority) {
        withMapper(mapper -> {
            String json = "{\"priority\":\"" + undefinedPriority + "\"}";

            assertThatThrownBy(() -> mapper.readValue(json, Payload.class))
                    .isInstanceOf(InvalidFormatException.class);
        });
    }

    /** Same rule for the status enum: an undefined target can never reach the state machine (Req 8.8). */
    @ParameterizedTest
    @ValueSource(strings = {"DONE", "open", "Open", " OPEN", "IN-PROGRESS"})
    void shouldRejectUndefinedStatusValuesInsteadOfCoercingThemToNull(String undefinedStatus) {
        withMapper(mapper -> {
            String json = "{\"status\":\"" + undefinedStatus + "\"}";

            assertThatThrownBy(() -> mapper.readValue(json, Payload.class))
                    .isInstanceOf(InvalidFormatException.class);
        });
    }

    /** Rejection must not come at the cost of the defined values (Req 1.5). */
    @Test
    void shouldDeserializeDefinedEnumValues() throws Exception {
        withMapperThrowing(mapper -> {
            Payload payload = mapper.readValue(
                    "{\"priority\":\"CRITICAL\",\"status\":\"IN_PROGRESS\"}", Payload.class);

            assertThat(payload.priority()).isEqualTo(TicketPriority.CRITICAL);
            assertThat(payload.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        });
    }

    /**
     * Strictness has to be total in the other direction too: every constant the API documents must
     * bind, so the rejection above cannot be satisfied by a deserializer that simply refuses
     * everything. Driven off {@code values()} so a newly added constant is covered without an edit.
     */
    @Test
    void shouldAcceptEveryDefinedEnumConstant() throws Exception {
        withMapperThrowing(mapper -> {
            for (TicketPriority priority : TicketPriority.values()) {
                assertThat(mapper.readValue("{\"priority\":\"" + priority + "\"}", Payload.class).priority())
                        .isEqualTo(priority);
            }
            for (TicketStatus status : TicketStatus.values()) {
                assertThat(mapper.readValue("{\"status\":\"" + status + "\"}", Payload.class).status())
                        .isEqualTo(status);
            }
        });
    }

    /** Timestamps go on the wire as ISO-8601 with a {@code Z} offset, not an epoch number. */
    @Test
    void shouldSerializeInstantAsIso8601WithZuluOffset() throws Exception {
        withMapperThrowing(mapper -> {
            Payload payload = new Payload(
                    "Login fails on SSO", TicketPriority.HIGH, TicketStatus.OPEN,
                    Instant.parse("2026-09-05T10:15:30Z"));

            assertThat(mapper.writeValueAsString(payload)).contains("\"createdAt\":\"2026-09-05T10:15:30Z\"");
        });
    }

    /**
     * The failure mode {@code WRITE_DATES_AS_TIMESTAMPS} guards against is a JSON <em>number</em>
     * (epoch seconds, or nanos-as-decimal), which a client reading the documented string contract
     * cannot parse. Asserted on the node type rather than on substring content so a numeric value
     * that happens to contain the right digits cannot pass.
     */
    @Test
    void shouldSerializeTimestampsAsJsonTextNeverAsANumber() throws Exception {
        withMapperThrowing(mapper -> {
            for (Instant instant : ISO_TIMESTAMP_CASES) {
                JsonNode createdAt = mapper.readTree(mapper.writeValueAsString(
                                new Payload(null, null, null, instant)))
                        .get("createdAt");

                assertThat(createdAt.isTextual()).as("%s must serialize as JSON text", instant).isTrue();
                assertThat(createdAt.isNumber()).isFalse();
                assertThat(createdAt.asText()).isEqualTo(DateTimeFormatter.ISO_INSTANT.format(instant));
            }
        });
    }

    /**
     * The serialized text must be the same instant a reader parses back, including sub-second
     * precision — otherwise "ISO-8601" would be satisfied by a lossy rendering.
     */
    @Test
    void shouldRoundTripAnInstantThroughIso8601Text() throws Exception {
        withMapperThrowing(mapper -> {
            for (Instant instant : ISO_TIMESTAMP_CASES) {
                Payload written = new Payload(null, null, null, instant);

                assertThat(mapper.readValue(mapper.writeValueAsString(written), Payload.class).createdAt())
                        .isEqualTo(instant);
            }
        });
    }

    /** Whitespace-only text must reach the trimmed-length validator unaltered (Req 5.8). */
    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", "\n", " \t\r\n ", "\u00A0", "\u3000"})
    void shouldNotCoerceWhitespaceOnlyTextToNull(String whitespaceOnly) throws Exception {
        withMapperThrowing(mapper -> {
            String json = mapper.writeValueAsString(new Payload(whitespaceOnly, null, null, null));

            Payload payload = mapper.readValue(json, Payload.class);

            assertThat(payload.title()).isEqualTo(whitespaceOnly);
        });
    }

    /**
     * The same rule stated against literal wire JSON rather than a round-trip, so nothing the writer
     * does can account for the result. Both halves of "as-is" are asserted: the value is not nulled,
     * and it is not trimmed — a trimming parser would turn each of these into {@code ""} and hand the
     * validator a verdict about a value the client never sent (Req 5.8).
     *
     * <p>Each character is submitted both as a {@code \\u} escape and as a raw code point, since a
     * parser that normalized only one of the two forms would otherwise slip through.
     */
    @ParameterizedTest
    @MethodSource("whitespaceOnlyJsonLiterals")
    void shouldBindWhitespaceOnlyJsonTextWithoutNullingOrTrimmingIt(String jsonLiteral, String expected)
            throws Exception {
        withMapperThrowing(mapper -> {
            Payload payload = mapper.readValue("{\"title\":" + jsonLiteral + "}", Payload.class);

            // Not asserted with isBlank(): AssertJ's definition rests on Character.isWhitespace, which
            // excludes NBSP — the very character the trimming rule has to cover.
            assertThat(payload.title()).isNotNull().isNotEmpty().isEqualTo(expected);
        });
    }

    /** JSON string literals (left) paired with the exact value they must bind to (right). */
    private static Stream<Arguments> whitespaceOnlyJsonLiterals() {
        return Stream.of(
                arguments("\" \"", " "),
                arguments("\"   \"", "   "),
                arguments("\"\\t\"", "\t"),
                arguments("\"\\n\"", "\n"),
                arguments("\" \\t\\r\\n \"", " \t\r\n "),
                arguments("\"\\u0020\"", " "),
                arguments("\"\\u00A0\"", "\u00A0"),
                arguments("\"\u00A0\"", "\u00A0"),
                arguments("\"\u3000\"", "\u3000"),
                arguments("\"\u1680\"", "\u1680"));
    }

    /** An empty string is text, not an absent value — the validator decides, not the parser (Req 5.8). */
    @Test
    void shouldNotCoerceEmptyStringTextToNull() throws Exception {
        withMapperThrowing(mapper -> {
            Payload payload = mapper.readValue("{\"title\":\"\"}", Payload.class);

            assertThat(payload.title()).isEqualTo("");
        });
    }

    /** Guards the configuration itself, so a future default flip fails here rather than silently. */
    @Test
    void shouldDisableTheCoercionAndTimestampFeatures() {
        withMapper(mapper -> {
            assertThat(mapper.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)).isFalse();
            assertThat(mapper.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE))
                    .isFalse();
            assertThat(mapper.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)).isFalse();
            assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        });
    }

    private static void withMapper(java.util.function.Consumer<ObjectMapper> assertions) {
        CONTEXT_RUNNER.run(context -> assertions.accept(context.getBean(ObjectMapper.class)));
    }

    /** Variant for assertion blocks that call the throwing Jackson read/write methods. */
    private static void withMapperThrowing(JsonAssertions assertions) {
        CONTEXT_RUNNER.run(context -> assertions.accept(context.getBean(ObjectMapper.class)));
    }

    @FunctionalInterface
    private interface JsonAssertions {
        void accept(ObjectMapper mapper) throws JsonProcessingException;
    }
}
