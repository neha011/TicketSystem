package com.ticketsystem.ticket.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson wiring for the HTTP boundary.
 *
 * <p>Every setting here is stated explicitly rather than relying on a framework default, because each
 * one is load-bearing for a requirement and a silent default change would weaken validation without
 * failing a build:
 *
 * <ul>
 *   <li><b>Unknown enum values must fail, not coerce.</b> With coercion enabled an undefined {@code
 *       priority} or {@code status} would arrive as {@code null} — either a misleading {@code @NotNull}
 *       message or, worse, a {@code null} reaching the status state machine. Rejecting at parse time
 *       yields a 400 that names the offending value (Req 1.5, 8.8).
 *   <li><b>Whitespace-only text must reach the validator intact.</b> Coercing blank input to {@code
 *       null} would hide it from {@code @TrimmedSize}, whose verdict is defined over the raw submitted
 *       value (Req 5.8).
 *   <li><b>Timestamps go on the wire as ISO-8601.</b> Numeric epoch output would break the documented
 *       {@code 2026-09-05T10:15:30Z} contract.
 *   <li><b>Trailing content after a complete value is a malformed body.</b> A body such as {@code
 *       {"content":"x"} garbage} must be rejected as a parse failure (-> 400) rather than binding to
 *       a valid DTO and ignoring the suffix, so every write endpoint fails malformed input uniformly
 *       (Req 10.3).
 * </ul>
 *
 * <p>Customizing the Boot-managed builder rather than defining a replacement {@code ObjectMapper}
 * keeps the auto-configured module set (notably JSR-310) in place.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictEnumAndIsoTimestampCustomizer() {
        return builder -> {
            // An undefined enum name is a parse failure (-> 400), never a coerced null.
            builder.featuresToDisable(
                    DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL,
                    DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

            // A bare integer must not bind to an enum by ordinal: {"status":0} would otherwise become
            // OPEN. Requirement 8.8 counts anything that is not a defined *name* as a validation
            // failure, so numeric enum input is rejected at parse time (-> 400).
            builder.featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);

            // Blank input must survive deserialization so the trimmed-length constraint can judge it.
            builder.featuresToDisable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);

            // Content after the first complete JSON value is a malformed body, not a silently
            // ignored suffix. Without this, "{\"content\":\"x\"} garbage" would bind to a valid DTO
            // and slip past parsing; with it, the trailing token is a parse failure (-> 400),
            // matching Requirement 10.3's "trailing content after a complete value" category across
            // every write endpoint rather than only those whose DTO has another required field.
            builder.featuresToEnable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

            // Instant -> "2026-09-05T10:15:30Z" rather than an epoch number.
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            builder.modulesToInstall(modules -> modules.add(strictEnumWhitespaceModule()));
        };
    }

    /**
     * Closes the two holes the feature flags above leave open:
     *
     * <ul>
     *   <li>Jackson's enum deserializer retries a failed name lookup against the {@code trim()}ed
     *       text, so {@code " OPEN"} would otherwise bind to {@code OPEN}.
     *   <li>Scalar coercion turns a numeric <em>string</em> into an ordinal index, so {@code "0"}
     *       would otherwise bind to the first constant (and {@code FAIL_ON_NUMBERS_FOR_ENUMS} only
     *       covers a bare integer, not a quoted one).
     * </ul>
     *
     * Requirement 8.8 counts anything that is not a defined <em>name</em> as a validation failure, so
     * both padded names and numeric-string ordinals are rejected here rather than quietly normalized.
     *
     * <p>The check is deliberately limited to surrounding whitespace and numeric-string ordinals and
     * delegates every other decision to Jackson, so {@code @JsonProperty}/{@code @JsonValue} aliases
     * on any future enum keep working.
     */
    private static SimpleModule strictEnumWhitespaceModule() {
        SimpleModule module = new SimpleModule("StrictEnumWhitespace");
        module.setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyEnumDeserializer(
                    DeserializationConfig config,
                    JavaType type,
                    BeanDescription beanDescription,
                    JsonDeserializer<?> deserializer) {
                return new WhitespaceIntolerantEnumDeserializer(type.getRawClass(), deserializer);
            }
        });
        return module;
    }

    /** Rejects enum strings carrying leading or trailing whitespace; delegates everything else. */
    private static final class WhitespaceIntolerantEnumDeserializer extends JsonDeserializer<Object>
            implements ResolvableDeserializer {

        private final Class<?> enumType;
        private final JsonDeserializer<?> delegate;

        private WhitespaceIntolerantEnumDeserializer(Class<?> enumType, JsonDeserializer<?> delegate) {
            this.enumType = enumType;
            this.delegate = delegate;
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String raw = parser.getText();
                if (!raw.equals(raw.trim())) {
                    throw context.weirdStringException(
                            raw, enumType, "value must be one of " + acceptedValues() + " with no surrounding whitespace");
                }
                if (isAllDigits(raw)) {
                    // A quoted ordinal like "0" must not coerce to the first constant; only defined
                    // names are accepted (Req 8.8).
                    throw context.weirdStringException(
                            raw, enumType, "value must be one of " + acceptedValues()
                                    + ", not a numeric ordinal");
                }
            }
            return delegate.deserialize(parser, context);
        }

        @Override
        public Class<?> handledType() {
            return enumType;
        }

        @Override
        public void resolve(DeserializationContext context) throws com.fasterxml.jackson.databind.JsonMappingException {
            if (delegate instanceof ResolvableDeserializer resolvable) {
                resolvable.resolve(context);
            }
        }

        /** True when the raw text is a non-empty run of ASCII digits, i.e. an ordinal-like value. */
        private static boolean isAllDigits(String raw) {
            if (raw.isEmpty()) {
                return false;
            }
            for (int i = 0; i < raw.length(); i++) {
                if (!Character.isDigit(raw.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private String acceptedValues() {
            Object[] constants = enumType.getEnumConstants();
            if (constants == null) {
                return enumType.getSimpleName();
            }
            StringBuilder names = new StringBuilder();
            for (Object constant : constants) {
                names.append(names.isEmpty() ? "" : ", ").append(constant);
            }
            return names.toString();
        }
    }
}
