package co.edu.permanencia.demo.validation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidadorNotasUCCTest {
    @Test
    void testNotas() {
        assertTrue(ValidadorNotasUCC.esNotaValida(4.5));
        assertTrue(ValidadorNotasUCC.esNotaAprobatoria(3.0));
        assertFalse(ValidadorNotasUCC.esNotaAprobatoria(2.9));
        assertFalse(ValidadorNotasUCC.esNotaValida(5.5));
        assertFalse(ValidadorNotasUCC.esNotaValida(-1.0));
    }
}
