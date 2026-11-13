package co.edu.permanencia.demo.dto;

public record MensajeChatDto(
    String remitente,
    String destinatario,
    String contenido,
    String fechaEnvio
) {}
