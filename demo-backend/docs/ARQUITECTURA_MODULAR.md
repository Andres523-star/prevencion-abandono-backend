# Arquitectura Modular del Backend · Plataforma Permanencia UCC

Este documento define la estructura y principios arquitectonicos del backend.

## Modulos del Sistema
- `auth`: Autenticacion sin estado con tokens de sesion simulados y perfiles por rol.
- `students`: Directorio de estudiantes con filtros en memoria indexados por sede y programa.
- `risk`: Evaluacion y clasificacion algoritmica del riesgo de desercion.
- `reports`: Motor de agregacion estadistica para tableros de decanos y rectores.
- `chat`: Mensajeria bidireccional de acompanamiento con soporte de adjuntos simulados.
- `ia`: Asistente conversacional contextual con prompts basados en roles institucionales.
- `validation`: Capa de validaciones de negocio independientes del framework.
- `analytics`: Componentes matematicos para scoring y deteccion de tendencias.
