package com.ticketsystem.ticket.config;

import com.ticketsystem.ticket.service.CacheKeyDeriver;
import com.ticketsystem.ticket.service.EvictionPolicy;
import com.ticketsystem.ticket.service.PromptNormalizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the prompt cache's pure helper functions as beans so services can take them by constructor
 * injection.
 *
 * <p>{@link PromptNormalizer}, {@link CacheKeyDeriver} and {@link EvictionPolicy} carry no Spring
 * annotations on purpose: they are dependency-free, side-effect-free logic that tests instantiate
 * directly without a context. Registering them here — rather than annotating each class with
 * {@code @Component} — keeps that isolation intact while still letting {@code PromptCacheService}
 * depend on them through the container, mirroring {@link TransitionValidatorConfig}.
 */
@Configuration
public class PromptCacheConfig {

    @Bean
    public PromptNormalizer promptNormalizer() {
        return new PromptNormalizer();
    }

    @Bean
    public CacheKeyDeriver cacheKeyDeriver() {
        return new CacheKeyDeriver();
    }

    @Bean
    public EvictionPolicy evictionPolicy() {
        return new EvictionPolicy();
    }
}
