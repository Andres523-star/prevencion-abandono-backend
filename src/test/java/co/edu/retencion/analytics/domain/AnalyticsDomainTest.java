package co.edu.retencion.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;

class AnalyticsDomainTest {

    @Test
    void smallCellsAreHiddenButZeroIsNot() {
        assertThat(CellSuppressor.mask(0, 10)).isZero();
        assertThat(CellSuppressor.mask(1, 10)).isNull();
        assertThat(CellSuppressor.mask(9, 10)).isNull();
        assertThat(CellSuppressor.mask(10, 10)).isEqualTo(10);
    }

    @Test
    void aSingleHiddenCellHidesTheSmallestVisibleOneToo() {
        // Con el total conocido, 3 se deduciría de 100 - 50 - 47.
        assertThat(CellSuppressor.maskColumn(List.of(50L, 47L, 3L), 10)).isEqualTo(Arrays.asList(50L, null, null));
        // Dos ocultas ya no se pueden deducir por separado.
        assertThat(CellSuppressor.maskColumn(List.of(50L, 4L, 3L), 10)).isEqualTo(Arrays.asList(50L, null, null));
        assertThat(CellSuppressor.maskColumn(List.of(50L, 40L), 10)).containsExactly(50L, 40L);
        // Los ceros no sirven de complemento: no ocultan nada.
        assertThat(CellSuppressor.maskColumn(List.of(0L, 3L), 10)).isEqualTo(Arrays.asList(0L, null));
        assertThat(CellSuppressor.maskColumn(List.of(), 10)).isEmpty();
    }

    @Test
    void percentageNeedsBothValues() {
        assertThat(CellSuppressor.percentage(1L, 3L)).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(CellSuppressor.percentage(null, 3L)).isNull();
        assertThat(CellSuppressor.percentage(1L, null)).isNull();
        assertThat(CellSuppressor.percentage(1L, 0L)).isNull();
    }

    @Test
    void trendJoinsLiveAndHistoricalInOrder() {
        LocalDate y24 = LocalDate.of(2024, 1, 1);
        LocalDate y25 = LocalDate.of(2025, 1, 1);
        LocalDate y26 = LocalDate.of(2026, 1, 1);
        List<TrendMerger.Merged> merged = TrendMerger.merge(
                List.of(new TrendMerger.Point(y26, 5, null), new TrendMerger.Point(y25, 2, 100L)),
                List.of(new TrendMerger.Point(y25, 3, 200L), new TrendMerger.Point(y24, 7, 300L)));
        assertThat(merged).extracting(TrendMerger.Merged::period).containsExactly(y24, y25, y26);
        assertThat(merged.get(0).source()).isEqualTo("HISTORICAL");
        assertThat(merged.get(1).source()).isEqualTo("MIXED");
        assertThat(merged.get(1).dropouts()).isEqualTo(5);
        assertThat(merged.get(1).enrolled()).isEqualTo(300);
        assertThat(merged.get(2).source()).isEqualTo("LIVE");
        assertThat(merged.get(2).enrolled()).isNull();
    }

    @Test
    void historyRejectsImpossibleCounts() {
        Instant now = Instant.now();
        UUID program = UUID.randomUUID();
        assertThatThrownBy(() -> DropoutHistory.of(program, YearMonth.of(2023, 2), null, 10, 11, null, null, now))
                .isInstanceOf(DomainException.class);
        DropoutHistory h = DropoutHistory.of(program, YearMonth.of(2023, 2), 3, 100, 10, "2023-1", null, now);
        assertThat(h.getPeriodMonth()).isEqualTo(LocalDate.of(2023, 2, 1));
        assertThat(h.update(100, 10, "2023-1", null, now)).isFalse();
        assertThat(h.update(100, 11, "2023-1", null, now)).isTrue();
        assertThat(h.getDropouts()).isEqualTo(11);
        assertThatThrownBy(() -> h.update(-1, 0, null, null, now)).isInstanceOf(DomainException.class);
    }

    @Test
    void filterRejectsInvertedDates() {
        AnalyticsFilter inverted = new AnalyticsFilter(null, null, null, null, LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 1, 1), null);
        assertThatThrownBy(inverted::validated).isInstanceOf(DomainException.class);
        assertThat(AnalyticsFilter.none().validated()).isEqualTo(AnalyticsFilter.none());
    }

    @Test
    void viewerAppliesTheScopeOfEachRole() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AnalyticsViewer coordinator = AnalyticsViewer.of(user(Set.of(Role.COORDINADOR), Set.of(mine)));
        assertThat(coordinator.effective(null)).containsExactly(mine);
        assertThat(coordinator.effective(mine)).containsExactly(mine);
        assertThatThrownBy(() -> coordinator.effective(other)).isInstanceOf(AccessDeniedException.class);

        assertThat(AnalyticsViewer.of(user(Set.of(Role.ADMIN), Set.of())).effective(null)).isNull();
        assertThat(AnalyticsViewer.of(user(Set.of(Role.PSICOLOGO), Set.of())).effective(other)).containsExactly(other);
        assertThatThrownBy(() -> AnalyticsViewer.of(user(Set.of(Role.CONSEJERO), Set.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static AuthenticatedUser user(Set<Role> roles, Set<UUID> programs) {
        return new AuthenticatedUser(UUID.randomUUID(), roles, programs, true, UUID.randomUUID());
    }
}
