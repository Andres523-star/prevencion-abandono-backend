package co.edu.permanencia.demo.common;

import java.util.List;

public final class ConstantesSedes {
    private ConstantesSedes() {}

    public static final List<String> SEDES_OFICIALES = List.of(
        "Apartadó", "Arauca", "Barrancabermeja", "Bogotá", "Bucaramanga",
        "Cali", "Cartago", "Espinal", "Ibagué", "Medellín", "Montería",
        "Neiva", "Pasto", "Pereira", "Popayán", "Quibdó", "Santa Marta", "Villavicencio"
    );

    public static boolean esSedeValida(String sede) {
        if (sede == null) return false;
        return SEDES_OFICIALES.stream().anyMatch(s -> s.equalsIgnoreCase(sede.trim()));
    }
}
