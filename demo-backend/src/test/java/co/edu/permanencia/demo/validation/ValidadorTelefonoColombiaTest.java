package co.edu.permanencia.demo.validation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidadorTelefonoColombiaTest {
    @Test
    void testTelefonos() {
        assertTrue(ValidadorTelefonoColombia.esTelefonoMovilValido("3101234567"));
        assertTrue(ValidadorTelefonoColombia.esTelefonoMovilValido("+573209876543"));
        assertFalse(ValidadorTelefonoColombia.esTelefonoMovilValido("2891234"));
        assertFalse(ValidadorTelefonoColombia.esTelefonoMovilValido(null));
    }
}
