package co.edu.permanencia.demo.dto;

public record ActualizarSeguimientoRequest(
    String idEstudiante,
    String nuevoEstado,
    String observacionDocente
) {}
