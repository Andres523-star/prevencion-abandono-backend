package co.edu.permanencia.demo.validation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidadorCorreoInstitucionalTest {
    @Test
    void testCorreosUCC() {
        assertTrue(ValidadorCorreoInstitucional.esCorreoUCC("estudiante@campusucc.edu.co"));
        assertTrue(ValidadorCorreoInstitucional.esCorreoUCC("profesor@ucc.edu.co"));
        assertFalse(ValidadorCorreoInstitucional.esCorreoUCC("externo@gmail.com"));
        assertFalse(ValidadorCorreoInstitucional.esCorreoUCC(null));
    }
}
