package com.ticketsystem.ticket.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Smoke tests over the shipped {@code application.yml} profile layout.
 *
 * <p>These resolve the real configuration files through Spring Boot's config data
 * processing without starting an application context, so the assertions describe what a
 * deployed instance would actually see rather than what a test slice overrides. No
 * datasource property is read, which keeps the {@code prod}/{@code staging} placeholders
 * (deliberately default-less) out of play.
 */
class ProfileConfigurationSmokeTest {

    /** Environments other than local development must never generate schema (Req 9.4). */
    @ParameterizedTest
    @ValueSource(strings = {"test", "prod", "staging"})
    void shouldEnableFlywayAndOnlyValidateSchemaUnderNonLocalProfiles(String profile) {
        ConfigurableEnvironment environment = environmentForProfiles(profile);

        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    /** Drift must be surfaced rather than papered over on every non-local profile (Req 9.4). */
    @ParameterizedTest
    @ValueSource(strings = {"test", "prod", "staging"})
    void shouldNotBaselineAndShouldValidateMigrationsUnderNonLocalProfiles(String profile) {
        ConfigurableEnvironment environment = environmentForProfiles(profile);

        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class)).isTrue();
    }

    /** Local development is explicitly allowed to generate the schema instead (Req 9.6). */
    @Test
    void shouldPermitAutomaticSchemaGenerationUnderLocalProfile() {
        ConfigurableEnvironment environment = environmentForProfiles("local");

        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
    }

    /** An unqualified start is a local start, so no profile-less run gets migration duty (Req 9.6). */
    @Test
    void shouldFallBackToTheLocalProfileWhenNoneIsActivated() {
        ConfigurableEnvironment environment = environmentForProfiles();

        assertThat(environment.getDefaultProfiles()).containsExactly("local");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
    }

    private static ConfigurableEnvironment environmentForProfiles(String... profiles) {
        ConfigurableEnvironment environment = new StandardEnvironment();

        ConfigDataEnvironmentPostProcessor.applyTo(
                environment, new DefaultResourceLoader(), new DefaultBootstrapContext(), profiles);

        return environment;
    }
}
