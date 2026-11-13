package co.edu.permanencia.demo.dto;

import java.util.List;

public record PlanAccionEstudianteDto(
    int estudianteId,
    String diagnostico,
    List<String> accionesRecomendadas,
    String fechaSeguimiento
) {}
