package co.edu.permanencia.demo.validation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidadorIdentificacionUCCTest {
    @Test
    void testDocumentosValidos() {
        assertTrue(ValidadorIdentificacionUCC.esDocumentoValido("1085293847"));
        assertTrue(ValidadorIdentificacionUCC.esDocumentoValido("123456"));
        assertFalse(ValidadorIdentificacionUCC.esDocumentoValido("123"));
        assertFalse(ValidadorIdentificacionUCC.esDocumentoValido("abc1085"));
        assertFalse(ValidadorIdentificacionUCC.esDocumentoValido(null));
    }
}
