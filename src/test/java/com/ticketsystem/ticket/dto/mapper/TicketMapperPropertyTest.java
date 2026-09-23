package com.ticketsystem.ticket.dto.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.validation.TrimmedSizeValidator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property-based tests for {@link TicketMapper} and {@link CommentMapper} mapping fidelity.
 *
 * <p>The mappers are hand-written, which makes a dropped or mis-assigned field the most likely silent
 * defect in the system: it would break the field-completeness of the create, detail, update, and comment
 * responses at once, and nothing about the code would look wrong. Example-based tests catch it only for
 * the values they happen to name, so this asserts the round trip over generated values instead.
 *
 * <p>Only {@link TicketMapper#toDetail} / {@link TicketMapper#toEntity} are round-tripped.
 * {@code toSummary} drops {@code description} and {@code comments} by design, so it has no inverse and
 * asserting one would be asserting the wrong thing; {@code TicketMapperTest} pins its projection instead.
 *
 * <p>Generators are deliberately hostile on text: supplementary code points (emoji, and an emoji plus a
 * skin-tone modifier), RTL letters and a right-to-left mark, combining marks, and interior whitespace
 * including NBSP — anything a mapper that "helpfully" normalized, trimmed, or re-encoded a value would
 * mangle. Titles are drawn at exactly 1 and exactly 200 trimmed characters as well as in between, because
 * a boundary-length title is where a stray re-trim on the way back would first show up.
 *
 * <p><strong>Requirements: 1.6, 2.6, 3.1, 5.2, 9.3</strong>
 */
class TicketMapperPropertyTest {

    /**
     * Code points safe at either end of a value whose trimmed length must equal its {@code char} length.
     * None is whitespace under {@code isWhitespace} or {@code isSpaceChar}, so a title built from these
     * has no leading or trailing whitespace for {@link TrimmedSizeValidator} to discount.
     */
    private static final int[] NON_BLANK_CODE_POINTS = {
        'a', 'B', '9', '-', '_', '%', '\'', '"',
        0x00E9, // é — precomposed Latin
        0x0301, // combining acute accent
        0x0323, // combining dot below
        0x05D0, // Hebrew alef — RTL
        0x0627, // Arabic alef — RTL
        0x200F, // right-to-left mark — zero-width format character
        0x3042, // Hiragana a
        0x4E2D, // CJK ideograph
        0x2603, // snowman — BMP symbol
        0x1F600, // grinning face — supplementary, surrogate pair
        0x1F926, // face-palm — supplementary
        0x1F3FD, // medium skin tone modifier — supplementary, only meaningful after a base emoji
    };

    /**
     * The same set widened with whitespace, for fields where leading and trailing blanks are part of the
     * stored value the round trip must not rewrite. NBSP and ideographic space are included because they
     * are exactly what a {@code trim()}-based or {@code strip()}-based mapper would silently eat.
     */
    private static final int[] TEXT_CODE_POINTS =
            IntStream.concat(
                            IntStream.of(NON_BLANK_CODE_POINTS),
                            IntStream.of(' ', '\t', '\n', '\r', 0x00A0, 0x3000))
                    .toArray();

    private final TicketMapper mapper = new TicketMapper(new CommentMapper());

    // Feature: support-ticket-management, Property 15: Entity ↔ DTO mapping preserves all field values
    /**
     * Property 15: for any ticket entity, {@code entity → DTO → entity} preserves every persisted field
     * and the comment association.
     *
     * <p>Four claims are asserted together, because each covers a different way the mapping could be
     * lossy:
     *
     * <ol>
     *   <li>every scalar field arrives unchanged and un-normalized — the values are compared against the
     *       generated inputs rather than against the intermediate DTO, so a mapper that mangled a field
     *       identically in both directions would still be caught;
     *   <li>the comment collection keeps its size, order, and each comment's four fields;
     *   <li>the association survives in both directions — the rebuilt ticket lists the comments and each
     *       rebuilt comment points back at that same ticket, which is what {@code Ticket.addComment} is
     *       there to guarantee;
     *   <li>re-mapping the rebuilt entity reproduces the original DTO exactly, so the round trip is a
     *       fixed point rather than merely field-wise plausible.
     * </ol>
     *
     * <p><strong>Validates: Requirements 1.6, 2.6, 3.1, 5.2, 9.3</strong>
     */
    @Property(tries = 300)
    void entityToDtoToEntityPreservesEveryFieldAndTheCommentAssociation(
            @ForAll("uuids") UUID id,
            @ForAll("titles") String title,
            @ForAll("descriptions") String description,
            @ForAll TicketStatus status,
            @ForAll TicketPriority priority,
            @ForAll("assignees") String assignee,
            @ForAll("instants") Instant createdAt,
            @ForAll("instants") Instant updatedAt,
            @ForAll("versions") long version,
            @ForAll("commentLists") List<Comment> comments) {

        // The generator's contract: a title's trimmed length is its char length, so "exactly 200 trimmed
        // characters" means what it says and a re-trim on the way back would be visible as a lost char.
        assertThat(TrimmedSizeValidator.trimmedLength(title))
                .as("generated title must carry no leading or trailing whitespace")
                .isEqualTo(title.length());

        Ticket original = new Ticket();
        original.setId(id);
        original.setTitle(title);
        original.setDescription(description);
        original.setStatus(status);
        original.setPriority(priority);
        original.setAssignee(assignee);
        original.setCreatedAt(createdAt);
        original.setUpdatedAt(updatedAt);
        original.setVersion(version);
        comments.forEach(original::addComment);

        TicketDetailResponse dto = mapper.toDetail(original);
        Ticket rebuilt = mapper.toEntity(dto);

        assertThat(rebuilt.getId()).as("id").isEqualTo(id);
        assertThat(rebuilt.getTitle()).as("title").isEqualTo(title);
        assertThat(rebuilt.getDescription()).as("description").isEqualTo(description);
        assertThat(rebuilt.getStatus()).as("status").isEqualTo(status);
        assertThat(rebuilt.getPriority()).as("priority").isEqualTo(priority);
        assertThat(rebuilt.getAssignee()).as("assignee").isEqualTo(assignee);
        assertThat(rebuilt.getCreatedAt()).as("createdAt").isEqualTo(createdAt);
        assertThat(rebuilt.getUpdatedAt()).as("updatedAt").isEqualTo(updatedAt);
        assertThat(rebuilt.getVersion()).as("version").isEqualTo(version);

        assertThat(rebuilt.getComments())
                .as("comment count must survive the round trip")
                .hasSameSizeAs(comments);

        for (int i = 0; i < comments.size(); i++) {
            Comment source = comments.get(i);
            Comment mapped = rebuilt.getComments().get(i);

            assertThat(mapped.getId()).as("comment[%d].id", i).isEqualTo(source.getId());
            assertThat(mapped.getAuthor()).as("comment[%d].author", i).isEqualTo(source.getAuthor());
            assertThat(mapped.getContent()).as("comment[%d].content", i).isEqualTo(source.getContent());
            assertThat(mapped.getCreatedAt())
                    .as("comment[%d].createdAt", i)
                    .isEqualTo(source.getCreatedAt());

            // Ticket → comments holds (the assertion above) and comment → ticket holds too, so the
            // reconstructed graph is navigable in both directions.
            assertThat(mapped.getTicket())
                    .as("comment[%d] must point back at the ticket that owns it", i)
                    .isSameAs(rebuilt);
        }

        assertThat(mapper.toDetail(rebuilt))
                .as("re-mapping the rebuilt entity must reproduce the original representation")
                .isEqualTo(dto);
    }

    // Feature: support-ticket-management, Property 15: Entity ↔ DTO mapping preserves all field values
    /**
     * Property 15, applied to a comment on its own: {@code entity → DTO → entity} preserves all four
     * exposed fields.
     *
     * <p>The rebuilt comment has no ticket yet — the owning side is set by {@code Ticket.addComment}, so
     * attaching it establishes the association rather than the mapper guessing at it. That is asserted
     * here so "no association" is a stated contract rather than an oversight nobody noticed.
     *
     * <p><strong>Validates: Requirements 3.1, 5.2, 9.3</strong>
     */
    @Property(tries = 300)
    void commentEntityToDtoToEntityPreservesEveryFieldAndLeavesTheAssociationToTheOwningTicket(
            @ForAll("comments") Comment original) {

        CommentMapper commentMapper = new CommentMapper();

        CommentResponse dto = commentMapper.toResponse(original);
        Comment rebuilt = commentMapper.toEntity(dto);

        assertThat(rebuilt.getId()).as("id").isEqualTo(original.getId());
        assertThat(rebuilt.getAuthor()).as("author").isEqualTo(original.getAuthor());
        assertThat(rebuilt.getContent()).as("content").isEqualTo(original.getContent());
        assertThat(rebuilt.getCreatedAt()).as("createdAt").isEqualTo(original.getCreatedAt());

        assertThat(rebuilt.getTicket()).as("the owning ticket is attached by the ticket, not the mapper").isNull();

        Ticket owner = new Ticket();
        owner.addComment(rebuilt);
        assertThat(rebuilt.getTicket()).isSameAs(owner);
        assertThat(owner.getComments()).containsExactly(rebuilt);

        assertThat(commentMapper.toResponse(rebuilt))
                .as("re-mapping the rebuilt comment must reproduce the original representation")
                .isEqualTo(dto);
    }

    /**
     * Titles at exactly 1 and exactly 200 trimmed characters plus arbitrary lengths in between, weighted
     * so both boundaries are hit often rather than left to chance. 200 is the column width and the
     * constraint maximum, 1 the minimum, and both are measured in {@code char} units — so a title ending
     * in an emoji at length 200 is a legitimate value the mapping has to carry intact.
     */
    @Provide
    Arbitrary<String> titles() {
        Arbitrary<Integer> lengths = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just(1)),
                Tuple.of(3, Arbitraries.just(200)),
                Tuple.of(4, Arbitraries.integers().between(1, 200)));
        return Combinators.combine(codePointPieces(NON_BLANK_CODE_POINTS).list().ofMinSize(1).ofMaxSize(200), lengths)
                .as(TicketMapperPropertyTest::textOfExactCharLength);
    }

    /**
     * Descriptions: null (none supplied), the empty string, and text carrying interior and edge
     * whitespace, which the mapper must preserve verbatim — a description has no trimmed-length rule.
     */
    @Provide
    Arbitrary<String> descriptions() {
        return text(TEXT_CODE_POINTS, 0, 80).injectNull(0.2);
    }

    /** An assignee is nullable — unassigned is a real state, not a missing value (Requirement 2.7). */
    @Provide
    Arbitrary<String> assignees() {
        return text(TEXT_CODE_POINTS, 1, 40).injectNull(0.3);
    }

    @Provide
    Arbitrary<List<Comment>> commentLists() {
        return comments().list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<Comment> comments() {
        return Combinators.combine(uuids(), text(NON_BLANK_CODE_POINTS, 1, 40), text(TEXT_CODE_POINTS, 1, 60), instants())
                .as((id, author, content, createdAt) -> {
                    Comment comment = new Comment();
                    comment.setId(id);
                    comment.setAuthor(author);
                    comment.setContent(content);
                    comment.setCreatedAt(createdAt);
                    return comment;
                });
    }

    /** Built from two longs rather than {@code randomUUID} so a counterexample shrinks and replays. */
    @Provide
    Arbitrary<UUID> uuids() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
    }

    /** Sub-second precision included, since a truncating mapper would otherwise pass unnoticed. */
    @Provide
    Arbitrary<Instant> instants() {
        return Combinators.combine(
                        Arbitraries.longs().between(0L, 4_000_000_000L),
                        Arbitraries.integers().between(0, 999_999_999))
                .as(Instant::ofEpochSecond);
    }

    /** Includes 0 (never modified) and the extremes, so a narrowing to {@code int} would show up. */
    @Provide
    Arbitrary<Long> versions() {
        return Arbitraries.frequencyOf(
                Tuple.of(2, Arbitraries.just(0L)),
                Tuple.of(1, Arbitraries.just(Long.MAX_VALUE)),
                Tuple.of(5, Arbitraries.longs().between(0L, Long.MAX_VALUE)));
    }

    /** One code point per generated piece, so a surrogate pair is never split mid-character. */
    private static Arbitrary<String> codePointPieces(int[] codePoints) {
        String[] pieces =
                IntStream.of(codePoints).mapToObj(Character::toString).toArray(String[]::new);
        return Arbitraries.of(pieces);
    }

    private static Arbitrary<String> text(int[] codePoints, int minPieces, int maxPieces) {
        return codePointPieces(codePoints)
                .list()
                .ofMinSize(minPieces)
                .ofMaxSize(maxPieces)
                .map(pieces -> String.join("", pieces));
    }

    /**
     * Assembles {@code pieces} into a string of exactly {@code target} {@code char}s, substituting a
     * single-char filler when the next piece would overshoot. That keeps a supplementary code point whole
     * while still landing on an exact boundary length such as 200.
     */
    private static String textOfExactCharLength(List<String> pieces, int target) {
        StringBuilder text = new StringBuilder(target);
        int index = 0;
        while (text.length() < target) {
            String piece = pieces.get(index++ % pieces.size());
            if (text.length() + piece.length() > target) {
                piece = "x";
            }
            text.append(piece);
        }
        return text.toString();
    }
}
