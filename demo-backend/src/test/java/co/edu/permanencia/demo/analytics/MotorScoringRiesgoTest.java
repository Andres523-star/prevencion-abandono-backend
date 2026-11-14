package co.edu.permanencia.demo.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MotorScoringRiesgoTest {
    @Test
    void testCalcularScorePonderado() {
        int score = MotorScoringRiesgo.calcularScorePonderado(80, 70, 50);
        assertEquals(71, score);
        assertEquals(100, MotorScoringRiesgo.calcularScorePonderado(150, 150, 150));
        assertEquals(0, MotorScoringRiesgo.calcularScorePonderado(-10, -10, -10));
    }
}
