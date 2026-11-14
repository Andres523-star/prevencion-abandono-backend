package co.edu.permanencia.demo.analytics;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CalculadoraDesviacionEstandarTest {
    @Test
    void testCalcularDesviacion() {
        assertEquals(0.0, CalculadoraDesviacionEstandar.calcularDesviacion(List.of(4.0)));
        double dev = CalculadoraDesviacionEstandar.calcularDesviacion(List.of(3.0, 4.0, 5.0));
        assertEquals(1.0, dev);
    }
}
