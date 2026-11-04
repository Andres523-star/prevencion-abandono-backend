package co.edu.retencion.risk;

/** Nivel de riesgo de deserción. El orden importa: ALTO es el mayor. */
public enum RiskLevel {
    BAJO,
    MEDIO,
    ALTO;

    public boolean isAbove(RiskLevel other) {
        return other == null || ordinal() > other.ordinal();
    }

    public static RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
