package co.edu.permanencia.demo.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstantesSedesTest {
    @Test
    void testSedeValida() {
        assertTrue(ConstantesSedes.esSedeValida("Pasto"));
        assertTrue(ConstantesSedes.esSedeValida("BOGOTÁ"));
        assertTrue(ConstantesSedes.esSedeValida("Medellín"));
        assertFalse(ConstantesSedes.esSedeValida("CiudadInexistente"));
        assertFalse(ConstantesSedes.esSedeValida(null));
    }
}
