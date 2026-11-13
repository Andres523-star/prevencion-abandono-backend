package co.edu.permanencia.demo.dto;

public record FiltroReporteAvanzadoDto(
    String sede,
    String facultad,
    String carrera,
    int periodoAnio,
    int periodoSemestre
) {}
