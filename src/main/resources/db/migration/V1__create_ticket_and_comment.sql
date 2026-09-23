-- V1: initial schema for support ticket management (Req 9.1, 9.3, 9.4).
--
-- This migration owns the schema for every non-local profile, where Hibernate runs with
-- ddl-auto: validate. Column names, types, lengths, and nullability therefore have to match
-- com.ticketsystem.ticket.domain.Ticket and .Comment exactly.
--
-- Timestamps use the standard TIMESTAMP WITH TIME ZONE spelling rather than the TIMESTAMPTZ
-- alias: it is the same PostgreSQL type, but it also parses under the H2 PostgreSQL
-- compatibility mode that the slice tests run against.
--
-- Migrations are forward-only and additive: once this file has been applied to a non-local
-- environment it is never edited; follow-up changes go into V2__..., V3__..., and so on.

CREATE TABLE ticket (
    id          UUID                     PRIMARY KEY,
    title       VARCHAR(200)             NOT NULL,
    description VARCHAR(5000),
    status      VARCHAR(20)              NOT NULL,
    priority    VARCHAR(20)              NOT NULL,
    assignee    VARCHAR(100),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL DEFAULT 0,
    -- Enum values are persisted as strings (@Enumerated(STRING)); these CHECKs keep the stored
    -- set aligned with TicketStatus / TicketPriority.
    CONSTRAINT ck_ticket_status   CHECK (status   IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_ticket_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    -- Defense in depth behind @TrimmedSize(min=1, max=200): if a code path ever bypasses DTO
    -- validation, the database still refuses a blank or over-long title.
    CONSTRAINT ck_ticket_title_not_blank CHECK (length(btrim(title)) BETWEEN 1 AND 200)
);

CREATE TABLE comment (
    id         UUID                     PRIMARY KEY,
    -- ON DELETE CASCADE mirrors the JPA cascade / orphan-removal mapping, so removing a ticket
    -- can never leave orphaned comments behind.
    ticket_id  UUID                     NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    author     VARCHAR(100)             NOT NULL,
    content    VARCHAR(5000)            NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Defense in depth behind @TrimmedSize(min=1, max=5000) on comment content.
    CONSTRAINT ck_comment_content_not_blank CHECK (length(btrim(content)) BETWEEN 1 AND 5000)
);

-- Serves the default list ordering (created_at DESC) with an optional status filter.
CREATE INDEX ix_ticket_status_created  ON ticket (status, created_at DESC);
-- Serves ticket-scoped comment reads ordered oldest to newest.
CREATE INDEX ix_comment_ticket_created ON comment (ticket_id, created_at);
