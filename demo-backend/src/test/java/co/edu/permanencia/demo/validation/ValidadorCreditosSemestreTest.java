package co.edu.permanencia.demo.validation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidadorCreditosSemestreTest {
    @Test
    void testCreditos() {
        assertTrue(ValidadorCreditosSemestre.estaEnRangoCreditos(16));
        assertTrue(ValidadorCreditosSemestre.estaEnRangoCreditos(8));
        assertTrue(ValidadorCreditosSemestre.estaEnRangoCreditos(22));
        assertFalse(ValidadorCreditosSemestre.estaEnRangoCreditos(5));
        assertFalse(ValidadorCreditosSemestre.estaEnRangoCreditos(25));
    }
}
