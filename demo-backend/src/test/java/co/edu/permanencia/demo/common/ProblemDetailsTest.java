package co.edu.permanencia.demo.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProblemDetailsTest {
    @Test
    void testProblemDetailsProperties() {
        ProblemDetails pd = new ProblemDetails("No Encontrado", 404, "Estudiante no existe");
        assertEquals("No Encontrado", pd.getTitle());
        assertEquals(404, pd.getStatus());
        assertEquals("Estudiante no existe", pd.getDetail());
        assertNotNull(pd.getTimestamp());
    }
}
