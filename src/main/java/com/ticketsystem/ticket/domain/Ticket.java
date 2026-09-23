package com.ticketsystem.ticket.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A support ticket and its lifecycle state.
 *
 * <p>This entity is never exposed on the API: {@code TicketMapper} converts to and from DTOs so that
 * the optimistic-lock {@code version}, JPA lazy proxies, and internal columns stay out of the wire
 * format.
 *
 * <p>Status changes are not applied by callers mutating {@link #setStatus(TicketStatus)} freely —
 * every transition goes through {@code TicketStatusTransitionValidator}, which owns the
 * allowed-transition table. The setter exists for the transition service and for JPA.
 */
@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    @GeneratedValue
    private UUID id;

    /** Stored already trimmed; trimmed-length 1..200 is enforced on the request DTO. */
    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 5000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketPriority priority;

    /** Null when the ticket is unassigned; the UI renders "Unassigned" for that case. */
    @Column(length = 100)
    private String assignee;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Optimistic locking. Clients round-trip the expected value on update and transition. */
    @Version
    private long version;

    /**
     * Comments ordered oldest to newest. The secondary {@code id} key makes the order total, so
     * comments created within the same instant still come back in a stable sequence.
     */
    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<Comment> comments = new ArrayList<>();

    public Ticket() {
        // no-arg constructor for JPA; services populate fields through the setters
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public void setPriority(TicketPriority priority) {
        this.priority = priority;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    /**
     * @return the comments in {@code (createdAt, id)} order. Unmodifiable so the owning side of the
     *     association is only ever changed through {@link #addComment} / {@link #removeComment}.
     */
    public List<Comment> getComments() {
        return Collections.unmodifiableList(comments);
    }

    /** Adds a comment and sets both sides of the association so the in-memory graph stays coherent. */
    public void addComment(Comment comment) {
        comments.add(comment);
        comment.setTicket(this);
    }

    /** Detaches a comment; orphan removal deletes it on flush. */
    public void removeComment(Comment comment) {
        if (comments.remove(comment)) {
            comment.setTicket(null);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Ticket otherTicket)) {
            return false;
        }
        // Unsaved entities have no identity yet, so they are only ever equal to themselves.
        return id != null && id.equals(otherTicket.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
