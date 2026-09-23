package com.ticketsystem.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Support Ticket Management backend.
 *
 * <p>The service owns all ticket business rules: input validation, the ticket status state
 * machine, and persistence. Clients (including the Ticket_UI) are never trusted to enforce
 * them, so every rule is applied again here regardless of what the caller sends.
 */
@SpringBootApplication
public class TicketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketServiceApplication.class, args);
    }
}
