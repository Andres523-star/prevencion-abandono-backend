package co.edu.retencion.students;

/**
 * Perfil socioeconómico sin identificadores (API pública para el motor de riesgo).
 *
 * @param stratum    estrato 1-6 (nunca suma puntaje de riesgo: solo métricas de sesgo y elegibilidad de recursos)
 * @param works      si trabaja
 * @param dependents personas a cargo
 * @param transport  medio de transporte
 */
public record SocioeconomicInfo(Integer stratum, Boolean works, Integer dependents, TransportMode transport) {

    @Override
    public String toString() {
        return "SocioeconomicInfo[***]";
    }
}
