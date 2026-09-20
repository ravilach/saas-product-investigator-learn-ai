package com.saasinvestigator.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saasinvestigator.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Paging}, which is three lines of arithmetic protecting every list endpoint in the API.
 *
 * <p>Worth testing because the ceiling is a denial-of-service control rather than a preference: {@code ?size=1000000} on
 * any paginated endpoint would load a whole collection into memory and serialise it. The cap lives here precisely so no
 * controller has to remember it, which means nothing else would notice if it stopped applying.
 */
class PagingTest {

    @Test
    void capsAnOversizedPageRequestInsteadOfRejectingItSoALargeExportStillSucceeds() {
        // Capped rather than rejected because asking for too much is a reasonable thing for a client to do - it does not
        // know the ceiling - whereas asking for page -1 is a bug in the caller.
        assertThat(Paging.of(0, 5000).getPageSize()).isEqualTo(Paging.MAX_PAGE_SIZE);
        assertThat(Paging.of(0, Integer.MAX_VALUE).getPageSize()).isEqualTo(Paging.MAX_PAGE_SIZE);
    }

    @Test
    void passesThroughAPageRequestThatIsAlreadyWithinTheCeiling() {
        assertThat(Paging.of(3, 50)).satisfies(request -> {
            assertThat(request.getPageNumber()).isEqualTo(3);
            assertThat(request.getPageSize()).isEqualTo(50);
        });
        assertThat(Paging.of(0, Paging.MAX_PAGE_SIZE).getPageSize()).isEqualTo(Paging.MAX_PAGE_SIZE);
    }

    @Test
    void rejectsANegativePageRatherThanQuietlyServingTheFirstOne() {
        // Correcting it would make a broken pager look like a working one that had run out of data - the hardest kind of
        // frontend bug to find, because every response is a valid empty page.
        assertThatThrownBy(() -> Paging.of(-1, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("page must be 0 or greater.");
    }

    @Test
    void rejectsAPageSizeBelowOneBecauseAnEmptyPageIsNotAnAnswerToAnything() {
        assertThatThrownBy(() -> Paging.of(0, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("size must be at least 1.");
        assertThatThrownBy(() -> Paging.of(0, -10)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void definesADefaultPageSizeWellBelowTheCeilingSoTheCommonCaseIsCheap() {
        assertThat(Paging.DEFAULT_PAGE_SIZE).isPositive().isLessThan(Paging.MAX_PAGE_SIZE);
    }

    @Test
    void appliesNoSortSoEveryListKeepsTheIndexedOrderItsRepositoryQueryDefines() {
        // Asserted rather than assumed: adding a sort here would silently override the order each repository method has
        // an index for, turning every list into a collection scan.
        assertThat(Paging.of(0, 20).getSort().isSorted()).isFalse();
    }
}
