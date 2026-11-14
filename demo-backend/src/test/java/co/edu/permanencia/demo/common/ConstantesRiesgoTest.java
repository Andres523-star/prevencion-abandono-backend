package co.edu.permanencia.demo.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstantesRiesgoTest {
    @Test
    void testClasificarNivel() {
        assertEquals(ConstantesRiesgo.NIVEL_ALTO, ConstantesRiesgo.clasificarNivel(85));
        assertEquals(ConstantesRiesgo.NIVEL_MEDIO, ConstantesRiesgo.clasificarNivel(45));
        assertEquals(ConstantesRiesgo.NIVEL_BAJO, ConstantesRiesgo.clasificarNivel(15));
    }
}
