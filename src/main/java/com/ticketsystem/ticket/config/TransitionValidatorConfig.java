package com.ticketsystem.ticket.config;

import com.ticketsystem.ticket.service.TicketStatusTransitionValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the pure {@link TicketStatusTransitionValidator} as a bean so services can take it by
 * constructor injection.
 *
 * <p>The validator itself carries no Spring annotations on purpose: it is dependency-free logic that
 * tests instantiate directly, without a context. Registering it here — rather than annotating the
 * class with {@code @Component} — keeps that isolation intact while still letting
 * {@code TicketStatusTransitionService} depend on it through the container.
 */
@Configuration
public class TransitionValidatorConfig {

    @Bean
    public TicketStatusTransitionValidator ticketStatusTransitionValidator() {
        return new TicketStatusTransitionValidator();
    }
}
