package co.edu.permanencia.demo.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FiltroCasosCriticosTest {
    @Test
    void testEsCasoUrgente() {
        assertTrue(FiltroCasosCriticos.esCasoUrgente(80, 3.5, 2));
        assertTrue(FiltroCasosCriticos.esCasoUrgente(40, 2.5, 1));
        assertTrue(FiltroCasosCriticos.esCasoUrgente(30, 3.8, 9));
        assertFalse(FiltroCasosCriticos.esCasoUrgente(45, 4.0, 3));
    }
}
