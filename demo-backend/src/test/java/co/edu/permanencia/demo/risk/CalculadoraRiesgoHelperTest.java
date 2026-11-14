package co.edu.permanencia.demo.risk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculadoraRiesgoHelperTest {
    @Test
    void testNormalizarPuntaje() {
        assertEquals(0, CalculadoraRiesgoHelper.normalizarPuntaje(-15));
        assertEquals(100, CalculadoraRiesgoHelper.normalizarPuntaje(150));
        assertEquals(75, CalculadoraRiesgoHelper.normalizarPuntaje(75.4));
    }

    @Test
    void testEsCasoPrioritario() {
        assertTrue(CalculadoraRiesgoHelper.esCasoPrioritario(80, 2));
        assertTrue(CalculadoraRiesgoHelper.esCasoPrioritario(40, 10));
        assertFalse(CalculadoraRiesgoHelper.esCasoPrioritario(25, 2));
    }
}
