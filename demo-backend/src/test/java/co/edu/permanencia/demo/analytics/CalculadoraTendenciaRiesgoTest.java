package co.edu.permanencia.demo.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculadoraTendenciaRiesgoTest {
    @Test
    void testEvaluarTendencia() {
        assertEquals(CalculadoraTendenciaRiesgo.Tendencia.MEJORANDO, CalculadoraTendenciaRiesgo.evaluarTendencia(80, 70));
        assertEquals(CalculadoraTendenciaRiesgo.Tendencia.DETERIORANDO, CalculadoraTendenciaRiesgo.evaluarTendencia(50, 60));
        assertEquals(CalculadoraTendenciaRiesgo.Tendencia.ESTABLE, CalculadoraTendenciaRiesgo.evaluarTendencia(50, 52));
    }
}
