package com.ticketsystem.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

/**
 * Property-based test for keyword search case-invariance, executed against a real schema.
 *
 * <p>The predicate under test lives in {@link TicketSpecifications#keywordMatches(String)} but its
 * case behaviour is decided by the database, not by Java: the specification emits {@code lower(field)
 * like lower(?)}. A unit test over the Criteria tree could only assert that {@code lower} was
 * requested — whether the two sides actually fold to the same case is a property of the running
 * engine. So this property runs through {@link TicketRepository} against the Flyway-managed schema.
 *
 * <h2>Why the Spring context is bootstrapped by hand</h2>
 *
 * <p>jqwik runs on its own JUnit Platform engine, so Jupiter's {@code SpringExtension} — which is
 * what normally acts on {@code @DataJpaTest} — never sees these methods. The class annotations are
 * still the single source of truth for the context configuration; a {@link TestContextManager} is
 * created from them and asked to inject dependencies into each per-try instance. The underlying
 * {@code ApplicationContext} comes from Spring's static context cache, so it is built once.
 *
 * <p>No transactional test listener is driven, which means repository writes commit rather than
 * rolling back. Each try therefore truncates the table before building its own corpus, and the
 * datasource URL is overridden to a database dedicated to this class so committed rows cannot leak
 * into any other slice test sharing the {@code test} profile.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties =
                "spring.datasource.url=jdbc:h2:mem:ticketdb-keyword-search;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class TicketKeywordSearchPropertyTest {

    private static final Instant BASE_TIME = Instant.parse("2026-03-01T10:15:30.123456Z");

    /** Column limits from V1__create_ticket_and_comment.sql; over-long text is a DB error, not a finding. */
    private static final int TITLE_MAX = 200;

    private static final int DESCRIPTION_MAX = 5000;

    /** Realistic free text for the tickets that are not carrying a planted keyword. */
    private static final List<String> NOISE_WORDS =
            List.of(
                    "laptop",
                    "Printer",
                    "VPN",
                    "login",
                    "Outlook",
                    "crashes",
                    "slow",
                    "reset",
                    "50%",
                    "user_id",
                    "can't",
                    "r\u00e9solu");

    private static TestContextManager contextManager;

    @Autowired
    private TicketRepository ticketRepository;

    @BeforeContainer
    static void createTestContextManager() {
        contextManager = new TestContextManager(TicketKeywordSearchPropertyTest.class);
    }

    /**
     * Injects the repository into this try's instance and clears the corpus left by the previous try.
     *
     * <p>Both steps live in one method on purpose: jqwik gives no ordering guarantee between several
     * {@code @BeforeTry} methods, and the reset needs the injected repository.
     */
    @BeforeTry
    void prepareInstanceAndResetCorpus() throws Exception {
        contextManager.prepareTestInstance(this);
        ticketRepository.deleteAll();
    }

    // Feature: support-ticket-management, Property 17: Keyword search results are invariant under case
    // changes of the search term
    /**
     * Property 17: searching is case-insensitive, expressed as a relation between four runs over one
     * corpus rather than as an assertion about a single run. The keyword as typed, its lowercase form,
     * its uppercase form, and its character-by-character case-flipped form must all select exactly the
     * same tickets.
     *
     * <p>A metamorphic formulation is the only way to pin this down: any single-run assertion about one
     * keyword is equally satisfied by a case-sensitive implementation that happens to be handed a
     * keyword in matching case. Comparing runs removes that escape.
     *
     * <p>The corpus plants the keyword in titles and descriptions at randomized case and at token
     * boundaries, and always plants it verbatim in one ticket so a case-sensitive implementation cannot
     * make every run empty and pass trivially — hence the non-emptiness guard, which exists to rule out
     * a vacuous pass rather than to check completeness (that is Property 18's job).
     *
     * <p>Results are compared both as id sets — the property as stated — and as ordered id lists, which
     * is a legitimate strengthening because {@link TicketSpecifications#defaultSort()} is a total order,
     * so equal result sets must come back in equal sequence.
     *
     * <p><strong>Validates: Requirements 6.1</strong>
     */
    @Property
    void keywordSearchResultsAreInvariantUnderCaseChangesOfTheSearchTerm(
            @ForAll("keywords") String keyword, @ForAll("ticketDrafts") List<TicketDraft> drafts) {

        ticketRepository.saveAll(buildCorpus(keyword, drafts));

        List<UUID> asTyped = search(keyword);
        List<UUID> lowered = search(keyword.toLowerCase(Locale.ROOT));
        List<UUID> uppered = search(keyword.toUpperCase(Locale.ROOT));
        List<UUID> flipped = search(flipCase(keyword));

        assertThat(asTyped)
                .as("corpus plants '%s' verbatim, so the search must find something to compare", keyword)
                .isNotEmpty();

        assertThat(Set.copyOf(lowered))
                .as("lowercase '%s' should select the same tickets as '%s'", keyword.toLowerCase(Locale.ROOT), keyword)
                .isEqualTo(Set.copyOf(asTyped));
        assertThat(Set.copyOf(uppered))
                .as("uppercase '%s' should select the same tickets as '%s'", keyword.toUpperCase(Locale.ROOT), keyword)
                .isEqualTo(Set.copyOf(asTyped));
        assertThat(Set.copyOf(flipped))
                .as("case-flipped '%s' should select the same tickets as '%s'", flipCase(keyword), keyword)
                .isEqualTo(Set.copyOf(asTyped));

        assertThat(lowered).as("total ordering makes equal result sets equal sequences").containsExactlyElementsOf(asTyped);
        assertThat(uppered).as("total ordering makes equal result sets equal sequences").containsExactlyElementsOf(asTyped);
        assertThat(flipped).as("total ordering makes equal result sets equal sequences").containsExactlyElementsOf(asTyped);
    }

    private List<UUID> search(String keyword) {
        return ticketRepository
                .findAll(TicketSpecifications.keywordMatches(keyword), TicketSpecifications.defaultSort())
                .stream()
                .map(Ticket::getId)
                .toList();
    }

    /**
     * Materializes the generated drafts against the keyword, prepending one ticket whose description
     * contains the keyword verbatim.
     *
     * <p>The verbatim plant is what makes the comparison meaningful: it matches under any case policy,
     * case-sensitive or not, so the baseline run is never empty and the three variant runs always have
     * something to disagree about.
     */
    private static List<Ticket> buildCorpus(String keyword, List<TicketDraft> drafts) {
        List<Ticket> corpus = new ArrayList<>(drafts.size() + 1);
        corpus.add(
                ticket(
                        0,
                        clamp("Ticket 0 guaranteed match", TITLE_MAX),
                        clamp("before " + keyword + " after", DESCRIPTION_MAX),
                        TicketStatus.OPEN,
                        TicketPriority.MEDIUM));

        for (int i = 0; i < drafts.size(); i++) {
            TicketDraft draft = drafts.get(i);
            int index = i + 1;
            String planted = draft.caseMode().apply(keyword);

            // The planted term sits between separators so it lands at a token boundary rather than
            // always being a prefix, and the "Ticket n" prefix keeps the title non-blank for the
            // btrim CHECK constraint even when the keyword is pure whitespace.
            String title =
                    draft.plant() == Plant.TITLE
                            ? clamp("Ticket " + index + " [" + planted + "] " + draft.noise(), TITLE_MAX)
                            : clamp("Ticket " + index + " " + draft.noise(), TITLE_MAX);

            String description;
            if (draft.plant() == Plant.DESCRIPTION) {
                description = clamp(draft.noise() + " (" + planted + ") " + draft.noise(), DESCRIPTION_MAX);
            } else if (draft.hasDescription()) {
                description = clamp(draft.noise(), DESCRIPTION_MAX);
            } else {
                // A null description must not break the title-OR-description disjunction.
                description = null;
            }

            corpus.add(ticket(index, title, description, draft.status(), draft.priority()));
        }
        return corpus;
    }

    private static Ticket ticket(
            int index, String title, String description, TicketStatus status, TicketPriority priority) {
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setStatus(status);
        ticket.setPriority(priority);
        // Distinct instants per ticket, so (createdAt DESC, id DESC) is a strict order and the
        // sequence comparison above cannot be satisfied by luck.
        ticket.setCreatedAt(BASE_TIME.plusSeconds(index));
        ticket.setUpdatedAt(BASE_TIME.plusSeconds(index));
        return ticket;
    }

    private static String clamp(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * Swaps the case of every cased character, leaving everything else alone.
     *
     * <p>Done per character rather than through {@code String.toUpperCase} so no character can expand
     * into several (the classic case being {@code ß} → {@code SS}), which would change the search term
     * rather than just its case.
     */
    private static String flipCase(String text) {
        StringBuilder flipped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isUpperCase(c)) {
                flipped.append(Character.toLowerCase(c));
            } else if (Character.isLowerCase(c)) {
                flipped.append(Character.toUpperCase(c));
            } else {
                flipped.append(c);
            }
        }
        return flipped.toString();
    }

    /**
     * Search terms of length 1..200, the range the request boundary admits (Req 6.4).
     *
     * <p>The alphabet deliberately includes the {@code LIKE} metacharacters {@code %} and {@code _},
     * the escape character {@code \}, quotes, spaces, digits, and accented Latin letters, so a keyword
     * that mixes wildcards with letters still has to behave identically across cases.
     *
     * <p>It deliberately excludes characters whose case mapping is not a one-to-one round trip — {@code
     * ß}, {@code İ}, {@code ﬁ} and friends. For those, "the uppercase variant of the term" is not a
     * pure case change at all ({@code lower(upper("ß"))} is {@code "ss"}), so a mismatch would say
     * nothing about the search predicate. Whether such terms should fold is a specification question,
     * not something this property can settle.
     *
     * <p>Short terms dominate because that is what users type, but a fifth of the draws sit at the
     * upper end of the permitted range so the 200-character boundary is exercised regularly.
     */
    @Provide
    Arbitrary<String> keywords() {
        Arbitrary<String> shortTerms = caseStableText().ofMinLength(1).ofMaxLength(24);
        Arbitrary<String> longTerms = caseStableText().ofMinLength(150).ofMaxLength(200);
        return Arbitraries.frequencyOf(Tuple.of(4, shortTerms), Tuple.of(1, longTerms));
    }

    private static net.jqwik.api.arbitraries.StringArbitrary caseStableText() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(
                        ' ',
                        '%',
                        '_',
                        '\\',
                        '\'',
                        '"',
                        '-',
                        '.',
                        '\u00e9', // é
                        '\u00c9', // É
                        '\u00fc', // ü
                        '\u00dc'); // Ü
    }

    /**
     * Ticket drafts, deliberately independent of the keyword so the same corpus shape is exercised
     * against every generated term. The keyword is woven in by {@link #buildCorpus}.
     *
     * <p>The empty list is included: a corpus consisting only of the guaranteed match is a valid
     * dataset, and so is one where every ticket plants the term.
     */
    @Provide
    Arbitrary<List<TicketDraft>> ticketDrafts() {
        Arbitrary<String> noise =
                Arbitraries.of(NOISE_WORDS).list().ofMinSize(0).ofMaxSize(6).map(words -> String.join(" ", words));
        return Combinators.combine(
                        noise,
                        Arbitraries.of(true, false),
                        Arbitraries.of(Plant.class),
                        Arbitraries.of(CaseMode.class),
                        Arbitraries.of(TicketStatus.class),
                        Arbitraries.of(TicketPriority.class))
                .as(TicketDraft::new)
                .list()
                .ofMinSize(0)
                .ofMaxSize(10);
    }

    /** Where a draft carries the search term, if anywhere. */
    private enum Plant {
        NONE,
        TITLE,
        DESCRIPTION
    }

    /** The case in which a planted occurrence is stored, so matches cannot depend on stored case. */
    private enum CaseMode {
        AS_IS,
        LOWER,
        UPPER,
        FLIPPED;

        String apply(String keyword) {
            return switch (this) {
                case AS_IS -> keyword;
                case LOWER -> keyword.toLowerCase(Locale.ROOT);
                case UPPER -> keyword.toUpperCase(Locale.ROOT);
                case FLIPPED -> flipCase(keyword);
            };
        }
    }

    /** A ticket to build, described independently of the search term. */
    private record TicketDraft(
            String noise,
            boolean hasDescription,
            Plant plant,
            CaseMode caseMode,
            TicketStatus status,
            TicketPriority priority) {}
}
