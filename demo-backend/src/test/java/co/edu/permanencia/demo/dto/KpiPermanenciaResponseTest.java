package co.edu.permanencia.demo.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KpiPermanenciaResponseTest {
    @Test
    void testKpiResponse() {
        KpiPermanenciaResponse kpi = new KpiPermanenciaResponse("Pasto", 92.5, 7.5, 140);
        assertEquals("Pasto", kpi.sede());
        assertEquals(92.5, kpi.tasaRetencion());
        assertEquals(140, kpi.totalCasosAtendidos());
    }
}
