# Manual Tecnico · Backend de Demostracion (Permanencia UCC)

Este documento describe la arquitectura modular del backend de demostracion.

## Caracteristicas Principales
- Desarrollado en Java 21 con Spring Boot 3.5.
- Almacenamiento en memoria optimizado para busquedas ultrarrapidas sin dependencia de base de datos externa.
- Modulos desacoplados: autenticacion, directorio estudiantil, evaluacion de riesgo, reportes, notificaciones y chat.
- Modelo de datos preparado para simulacion de estudiantes en las 17 sedes de la Universidad Cooperativa de Colombia.

## Seguridad

Ver [SEGURIDAD_DEMO.md](SEGURIDAD_DEMO.md): contraseñas con hash, sesiones que vencen, límite de intentos, registro acotado, adjuntos validados con enlace firmado e IA (Claude Sonnet 5.5) sin datos personales.
