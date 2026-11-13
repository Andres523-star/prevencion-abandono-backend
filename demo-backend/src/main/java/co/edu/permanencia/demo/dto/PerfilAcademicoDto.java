package co.edu.permanencia.demo.dto;

public record PerfilAcademicoDto(
    String cedula,
    String nombreCompleto,
    String carrera,
    String sede,
    int semestreActual
) {}
