package co.edu.retencion.reports.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReportDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private static ReportJob job(ReportJob.Type type, boolean identity) {
        return ReportJob.request(UUID.randomUUID(), type, ReportJob.Format.XLSX, "{}", null, identity, NOW);
    }

    @Test
    void lifecycle() {
        ReportJob job = job(ReportJob.Type.AT_RISK_STUDENTS, true);
        assertThat(job.getStatus()).isEqualTo(ReportJob.Status.PENDIENTE);
        assertThat(job.downloadable(NOW)).isFalse();
        job.ready("reportes/x-xlsx", "reporte.xlsx", 10, 2, Duration.ofHours(24), NOW);
        assertThat(job.getStatus()).isEqualTo(ReportJob.Status.LISTO);
        assertThat(job.getMediaType()).contains("spreadsheetml");
        assertThat(job.downloadable(NOW.plusSeconds(60))).isTrue();
        assertThat(job.downloadable(NOW.plus(Duration.ofHours(25)))).isFalse();
        job.expire();
        assertThat(job.getStatus()).isEqualTo(ReportJob.Status.EXPIRADO);
        assertThat(job.getObjectKey()).isNull();

        ReportJob failed = job(ReportJob.Type.GENERAL, false);
        failed.fail("report_too_large", NOW);
        assertThat(failed.getStatus()).isEqualTo(ReportJob.Status.FALLIDO);
        assertThat(failed.getFailureCode()).isEqualTo("report_too_large");
    }

    @Test
    void aggregateReportsCannotCarryIdentity() {
        assertThat(ReportJob.Type.ATTENDANCE.listsStudents()).isTrue();
        assertThat(ReportJob.Type.GENERAL.listsStudents()).isFalse();
        assertThatThrownBy(() -> job(ReportJob.Type.GENERAL, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsKnowTheirFile() {
        assertThat(ReportJob.Format.PDF.mediaType()).isEqualTo("application/pdf");
        assertThat(ReportJob.Format.PDF.extension()).isEqualTo("pdf");
        assertThat(ReportJob.Format.XLSX.extension()).isEqualTo("xlsx");
    }

    @Test
    void builderRequiresTheConfidentialityNotice() {
        ReportDocument.Builder noNotice = ReportDocument.builder().title("T").section("S", List.of("A"), sink -> { });
        assertThatThrownBy(noNotice::build).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReportDocument.builder().notice("N").section("S", List.of("A"), sink -> { }).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReportDocument.builder().title("T").notice("N").build())
                .isInstanceOf(IllegalStateException.class);
        ReportDocument doc = ReportDocument.builder().title("T").notice("N").watermark("W")
                .section("S", List.of("A"), sink -> sink.accept(List.of(1))).build();
        assertThat(doc.title()).isEqualTo("T");
        assertThat(doc.notice()).isEqualTo("N");
        assertThat(doc.watermark()).isEqualTo("W");
        assertThat(doc.sections()).hasSize(1);
        assertThat(new ReportTooLargeException(5)).hasMessageContaining("5");
    }
}
