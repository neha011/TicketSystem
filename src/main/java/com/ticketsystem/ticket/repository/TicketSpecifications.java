package com.ticketsystem.ticket.repository;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;

/**
 * Query predicates for the ticket list endpoint: keyword search and status filter (Req 2.2, 6.1,
 * 6.5, 7.1).
 *
 * <p>Both predicates are expressed through the Criteria API rather than assembled JPQL, so the
 * user-supplied search term is bound as a parameter and never becomes part of the query text. The
 * two predicates are conjunctive, which is the structural reason a combined keyword-plus-status
 * query is always a subset of either applied alone (Req 6.5).
 *
 * <p>Case-insensitivity is applied by the database to both sides of the comparison
 * ({@code lower(field) like lower(?)}) rather than relying on column collation, because H2 (local
 * and slice tests) and PostgreSQL (everywhere else) do not agree on default collation behaviour.
 */
public final class TicketSpecifications {

    /**
     * Escape character for {@code LIKE} patterns.
     *
     * <p>Stated explicitly in the query instead of relying on the dialect default, so the meaning of
     * a backslash in a pattern does not depend on which database is behind the repository.
     */
    private static final char LIKE_ESCAPE = '\\';

    /**
     * Total ordering for ticket list queries (Req 2.2).
     *
     * <p>{@code createdAt} alone is not a total order — tickets created within the same instant would
     * come back in whatever order the database chose, which makes pagination unstable: the same row
     * could appear on two pages or on none. The {@code id} tiebreaker removes that.
     */
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private TicketSpecifications() {
        // static factory holder
    }

    /**
     * @return the default list ordering, {@code createdAt DESC, id DESC}
     */
    public static Sort defaultSort() {
        return DEFAULT_SORT;
    }

    /**
     * Case-insensitive substring match against the title or the description (Req 6.1).
     *
     * <p>{@code %} and {@code _} inside the keyword are escaped, so a user typing a wildcard searches
     * for that literal character instead of broadening their own search. Without this, a keyword of
     * {@code "%"} would match every ticket.
     *
     * @param keyword the search term; {@code null} or empty means "no keyword", which yields a no-op
     *     predicate. Length validation (1..200) belongs to the request boundary, not here.
     * @return a specification matching tickets whose title or description contains {@code keyword},
     *     ignoring case
     */
    public static Specification<Ticket> keywordMatches(@Nullable String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return noOp();
        }
        String pattern = "%" + escapeLikeWildcards(keyword) + "%";
        return (root, query, cb) ->
                cb.or(containsIgnoringCase(root, cb, "title", pattern),
                        containsIgnoringCase(root, cb, "description", pattern));
    }

    /**
     * Exact, case-sensitive status match (Req 7.1).
     *
     * @param status the status to filter on; {@code null} means "unfiltered" (Req 7.4) and yields a
     *     no-op predicate. An undefined status value cannot reach this method — it is not
     *     representable as a {@link TicketStatus}.
     * @return a specification matching tickets in exactly that status
     */
    public static Specification<Ticket> hasStatus(@Nullable TicketStatus status) {
        if (status == null) {
            return noOp();
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Composes the keyword and status predicates, dropping the absent ones (Req 6.5).
     *
     * <p>When both arguments are absent the result is a specification that constrains nothing, so the
     * caller gets the unfiltered list rather than an empty one.
     *
     * @param keyword the search term, or {@code null} when not searching
     * @param status the status filter, or {@code null} when unfiltered
     * @return the conjunction of whichever predicates are present
     */
    public static Specification<Ticket> matching(
            @Nullable String keyword, @Nullable TicketStatus status) {
        List<Specification<Ticket>> present = new ArrayList<>(2);
        if (keyword != null && !keyword.isEmpty()) {
            present.add(keywordMatches(keyword));
        }
        if (status != null) {
            present.add(hasStatus(status));
        }
        return Specification.allOf(present);
    }

    /**
     * {@code lower(field) like lower(?) escape '\'}.
     *
     * <p>The pattern goes through {@link HibernateCriteriaBuilder#value(Object)} rather than
     * {@code CriteriaBuilder.literal(...)}: {@code literal} renders the value straight into the SQL
     * text, while {@code value} honours the criteria value-handling mode (BIND by default) and so
     * emits a JDBC bind parameter. The user's search term therefore never becomes part of the query
     * string, which is the property this method exists to guarantee.
     *
     * <p>{@code lower} is applied by the database to both operands, so the match does not depend on
     * the JVM default locale or on the column's collation (H2 and PostgreSQL disagree there).
     */
    private static Predicate containsIgnoringCase(
            Root<Ticket> root, CriteriaBuilder cb, String attribute, String pattern) {
        HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
        Expression<String> boundPattern = hcb.lower(hcb.value(pattern));
        return cb.like(cb.lower(root.get(attribute)), boundPattern, LIKE_ESCAPE);
    }

    /**
     * Escapes the {@code LIKE} metacharacters {@code %} and {@code _}, plus the escape character
     * itself so that a literal backslash in the keyword stays literal.
     */
    private static String escapeLikeWildcards(String keyword) {
        StringBuilder escaped = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** A specification that contributes no restriction; a null predicate is dropped on composition. */
    private static Specification<Ticket> noOp() {
        return (root, query, cb) -> null;
    }
}
