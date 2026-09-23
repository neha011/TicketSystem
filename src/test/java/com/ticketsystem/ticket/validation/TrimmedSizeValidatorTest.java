package com.ticketsystem.ticket.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Example-based tests for {@link TrimmedSize} and {@link TrimmedSizeValidator}.
 *
 * <p>Exercised two ways on purpose: directly against the validator for the measuring logic, and
 * through a real Bean Validation {@link Validator} against an annotated record, so the annotation's
 * own wiring (target on a record component, message interpolation, discovery of the validator class)
 * is covered rather than assumed.
 *
 * <p><strong>Requirements: 1.3, 1.4, 4.3, 5.4, 5.5, 10.1, 10.4</strong>
 */
class TrimmedSizeValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    /** Stand-in for the title field: the 1..200 rule from Requirements 1.3, 1.4, 10.1. */
    record TitleHolder(@TrimmedSize(min = 1, max = 200) String title) {}

    /** Stand-in for comment content: the 1..5000 rule from Requirements 5.4, 5.5. */
    record ContentHolder(@TrimmedSize(min = 1, max = 5000) String content) {}

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    @Nested
    @DisplayName("trimmed length measurement")
    class TrimmedLength {

        @Test
        void shouldMeasureZeroForWhitespaceOnlyValues() {
            assertThat(TrimmedSizeValidator.trimmedLength("")).isZero();
            assertThat(TrimmedSizeValidator.trimmedLength("    ")).isZero();
            assertThat(TrimmedSizeValidator.trimmedLength("\t\r\n\f\u000B")).isZero();
        }

        /** {@code trim()} stops at {@code U+0020} and would leave these values looking non-empty. */
        @Test
        void shouldMeasureZeroForUnicodeSeparatorsThatTrimWouldMiss() {
            assertThat(TrimmedSizeValidator.trimmedLength("\u3000\u1680\u2028\u2029")).isZero();
        }

        /** The non-breaking spaces, which {@code String.strip()} itself would leave in place. */
        @Test
        void shouldMeasureZeroForNonBreakingSpacesThatStripWouldMiss() {
            assertThat(TrimmedSizeValidator.trimmedLength("\u00A0\u202F\u2007")).isZero();
        }

        @Test
        void shouldNotSplitSupplementaryCharactersWhileTrimming() {
            assertThat(TrimmedSizeValidator.trimmedLength("  \uD83D\uDE00  ")).isEqualTo(2);
        }

        @Test
        void shouldMeasureOnlySurroundingWhitespaceAsRemovable() {
            assertThat(TrimmedSizeValidator.trimmedLength("  a b  ")).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("title constraint, 1..200 (Requirements 1.3, 1.4, 10.4)")
    class TitleConstraint {

        @ParameterizedTest(name = "rejects whitespace-only title [{0}]")
        @ValueSource(strings = {"", " ", "   \t\r\n  ", "\u00A0\u3000\u1680", "\u202F\u2007"})
        void shouldRejectWhitespaceOnlyTitle(String rawTitle) {
            Set<ConstraintViolation<TitleHolder>> violations =
                    validator.validate(new TitleHolder(rawTitle));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .isEqualTo("must be 1 to 200 characters after trimming");
        }

        @Test
        void shouldAcceptTitleThatIsOnlyLongEnoughAfterWhitespaceIsIgnored() {
            assertThat(validator.validate(new TitleHolder("   x   "))).isEmpty();
        }

        @Test
        void shouldAcceptTitleOfExactlyTwoHundredTrimmedCharacters() {
            String title = "  " + "a".repeat(200) + "  ";

            assertThat(validator.validate(new TitleHolder(title))).isEmpty();
        }

        @Test
        void shouldRejectTitleExceedingTwoHundredTrimmedCharacters() {
            String title = "  " + "a".repeat(201) + "  ";

            assertThat(validator.validate(new TitleHolder(title))).hasSize(1);
        }

        /** Presence is {@code @NotNull}'s job, so this constraint stays silent on null. */
        @Test
        void shouldAcceptNullAndLeavePresenceToNotNull() {
            assertThat(validator.validate(new TitleHolder(null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("comment content constraint, 1..5000 (Requirements 5.4, 5.5)")
    class ContentConstraint {

        @Test
        void shouldRejectWhitespaceOnlyContent() {
            assertThat(validator.validate(new ContentHolder("\n\n\t "))).hasSize(1);
        }

        @Test
        void shouldAcceptContentOfExactlyFiveThousandTrimmedCharacters() {
            String content = "\n" + "c".repeat(5000) + "\n";

            assertThat(validator.validate(new ContentHolder(content))).isEmpty();
        }

        @Test
        void shouldRejectContentExceedingFiveThousandTrimmedCharacters() {
            assertThat(validator.validate(new ContentHolder("c".repeat(5001)))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("misconfigured bounds")
    class MisconfiguredBounds {

        @Test
        void shouldRejectNegativeMin() {
            TrimmedSizeValidator subject = new TrimmedSizeValidator();

            assertThatThrownBy(() -> subject.initialize(trimmedSize(-1, 10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("min must not be negative");
        }

        @Test
        void shouldRejectMaxBelowMin() {
            TrimmedSizeValidator subject = new TrimmedSizeValidator();

            assertThatThrownBy(() -> subject.initialize(trimmedSize(5, 4)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be less than min");
        }

        /** Minimal hand-rolled annotation instance; only the bounds are read by initialize(). */
        private TrimmedSize trimmedSize(int min, int max) {
            return new TrimmedSize() {
                @Override
                public Class<? extends java.lang.annotation.Annotation> annotationType() {
                    return TrimmedSize.class;
                }

                @Override
                public int min() {
                    return min;
                }

                @Override
                public int max() {
                    return max;
                }

                @Override
                public String message() {
                    return "";
                }

                @Override
                public Class<?>[] groups() {
                    return new Class<?>[0];
                }

                @Override
                public Class<? extends jakarta.validation.Payload>[] payload() {
                    return payloadArray();
                }

                @SuppressWarnings("unchecked")
                private Class<? extends jakarta.validation.Payload>[] payloadArray() {
                    return new Class[0];
                }
            };
        }
    }
}
