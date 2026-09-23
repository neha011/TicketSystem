package com.ticketsystem.ticket.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the pagination envelope: the documented metadata field names, the derivation of
 * {@code totalPages}, and the empty-page cases that must still be successful results.
 */
class PagedResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeTheDocumentedMetadataFields() throws Exception {
        PagedResponse<String> page = PagedResponse.of(List.of("a", "b"), 0, 20, 137L);

        String json = objectMapper.writeValueAsString(page);

        assertThat(objectMapper.readTree(json).fieldNames())
                .toIterable()
                .containsExactly("content", "page", "size", "totalElements", "totalPages");
        assertThat(json).contains("\"totalElements\":137").contains("\"totalPages\":7");
    }

    @Test
    void shouldDeriveTotalPagesAsCeilingOfTotalElementsOverSize() {
        assertThat(PagedResponse.of(List.of(), 0, 20, 137L).totalPages()).isEqualTo(7);
        assertThat(PagedResponse.of(List.of(), 0, 20, 140L).totalPages()).isEqualTo(7);
        assertThat(PagedResponse.of(List.of(), 0, 20, 141L).totalPages()).isEqualTo(8);
        assertThat(PagedResponse.of(List.of(), 0, 1, 1L).totalPages()).isEqualTo(1);
    }

    @Test
    void shouldReportZeroTotalsForAnEmptyResultSet() throws Exception {
        PagedResponse<String> page = PagedResponse.empty(0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(objectMapper.writeValueAsString(page)).contains("\"content\":[]");
    }

    @Test
    void shouldKeepMetadataCoherentForAPageBeyondTheLast() {
        PagedResponse<String> beyond = PagedResponse.of(List.of(), 99, 20, 137L);

        assertThat(beyond.content()).isEmpty();
        assertThat(beyond.page()).isEqualTo(99);
        assertThat(beyond.size()).isEqualTo(20);
        assertThat(beyond.totalElements()).isEqualTo(137L);
        assertThat(beyond.totalPages()).isEqualTo(7);
    }

    @Test
    void shouldTreatNullContentAsAnEmptyPage() {
        assertThat(new PagedResponse<String>(null, 0, 20, 0L, 0).content()).isEmpty();
    }

    @Test
    void shouldNotExposeTheCallersContentListForMutation() {
        List<String> mutable = new ArrayList<>(List.of("a"));

        PagedResponse<String> page = PagedResponse.of(mutable, 0, 20, 1L);
        mutable.add("b");

        assertThat(page.content()).containsExactly("a");
    }

    @Test
    void shouldRejectMetadataThatCouldNeverHaveReachedTheResponseLayer() {
        assertThatIllegalArgumentException().isThrownBy(() -> PagedResponse.of(List.of(), -1, 20, 0L));
        assertThatIllegalArgumentException().isThrownBy(() -> PagedResponse.of(List.of(), 0, 0, 0L));
        assertThatIllegalArgumentException().isThrownBy(() -> PagedResponse.of(List.of(), 0, 20, -1L));
    }
}
