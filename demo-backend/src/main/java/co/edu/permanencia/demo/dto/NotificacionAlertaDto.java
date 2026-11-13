package co.edu.permanencia.demo.dto;

public record NotificacionAlertaDto(
    String id,
    String titulo,
    String mensaje,
    String nivelSeveridad,
    String enlaceAccion
) {}
