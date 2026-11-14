package co.edu.permanencia.demo.reports;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EstadisticasHelperTest {
    @Test
    void testCalcularPromedio() {
        assertEquals(4.0, EstadisticasHelper.calcularPromedio(List.of(3.0, 4.0, 5.0)));
        assertEquals(0.0, EstadisticasHelper.calcularPromedio(List.of()));
    }
}
