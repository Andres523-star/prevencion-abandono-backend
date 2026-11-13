package co.edu.permanencia.demo.dto;

public record KpiPermanenciaResponse(
    String sede,
    double tasaRetencion,
    double tasaDesercionTemprana,
    int totalCasosAtendidos
) {}
