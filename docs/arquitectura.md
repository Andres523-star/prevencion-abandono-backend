# Arquitectura (C4)

## Contexto

```mermaid
flowchart TB
  est[Estudiante]:::persona
  per[Personal: docente, consejero, psicólogo, coordinador, admin]:::persona
  sis[Plataforma de retención estudiantil]:::sistema
  sup[Supabase Auth]:::externo
  ant[Anthropic API - Claude]:::externo
  mail[Proveedor de correo]:::externo
  acad[Sistema académico de la universidad]:::externo
  est -->|portal, justificaciones, chat| sis
  per -->|alertas, análisis, chat, reportes| sis
  sis -->|valida tokens y correo| sup
  sis -->|datos seudonimizados| ant
  sis -->|avisos de faltas| mail
  acad -->|archivos CSV/Excel| sis
  classDef persona fill:#dbeafe,stroke:#1e40af
  classDef sistema fill:#dcfce7,stroke:#166534
  classDef externo fill:#f3f4f6,stroke:#6b7280
```

## Contenedores

```mermaid
flowchart LR
  web[Frontend web<br/>fuera de este repo] -->|HTTPS REST + WebSocket STOMP| api
  subgraph Render
    api[Backend Spring Boot<br/>monolito modular]
    db[(PostgreSQL 16<br/>particiones, vistas materializadas)]
    kv[(Redis / Key Value<br/>rate limit, tickets, caché)]
    av[ClamAV<br/>servicio privado]
  end
  api --> db
  api --> kv
  api --> av
  api --> s3[(Almacenamiento de objetos)]
  api -.opcional.-> mq[RabbitMQ + STOMP]
  api --> sup[Supabase Auth]
  api --> ant[Anthropic API]
  api --> mail[Correo]
```

## Componentes (módulos de Spring Modulith)

```mermaid
flowchart TB
  subgraph Base
    common; audit; settings; users; auth
  end
  subgraph Academico
    academic; students; imports; records; justifications; mailing; wellbeing
  end
  subgraph Inteligencia
    ai; risk
  end
  subgraph Intervencion
    realtime; alerts; notifications; chat; interventions; retention
  end
  subgraph Analitica
    analytics; dashboard; reports
  end
  subgraph Titular
    portal; privacy
  end
  records --> academic & students
  risk --> ai & records & students
  alerts --> risk
  notifications --> alerts & chat & retention
  analytics --> risk & records & retention
  dashboard --> analytics & alerts
  reports --> analytics
  portal --> records & risk & students
  privacy --> students & records & justifications
```

Reglas: cada módulo expone su paquete raíz (interfaces, records y eventos) y oculta sus subpaquetes; se comunican
por API pública o por eventos ([eventos.md](eventos.md)); `ModularityTest` falla si alguien cruza un límite. El
diagrama completo y verificado lo genera Spring Modulith en `target/spring-modulith-docs/` con `./mvnw verify`.
