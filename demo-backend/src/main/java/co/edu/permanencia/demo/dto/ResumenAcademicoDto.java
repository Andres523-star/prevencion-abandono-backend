package co.edu.permanencia.demo.dto;

public record ResumenAcademicoDto(
    double promedioPonderado,
    int materiasAprobadas,
    int materiasReprobadas,
    int totalCreditos
) {}
