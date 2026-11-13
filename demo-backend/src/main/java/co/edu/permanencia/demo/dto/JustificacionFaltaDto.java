package co.edu.permanencia.demo.dto;

public record JustificacionFaltaDto(
    String estudianteId,
    String materia,
    String fechaInasistencia,
    String motivo,
    String documentoAdjunto
) {}
