# syntax=docker/dockerfile:1.7
# Imagen de producción: build con Maven y ejecución con JRE mínimo como usuario sin privilegios.
# Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml lombok.config ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Los tests corren en CI; aquí solo se empaqueta.
RUN ./mvnw -B -q package -DskipTests && \
    mkdir -p target/extracted && \
    java -Djarmode=tools -jar target/retencion-backend-*.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="retencion-backend" \
      org.opencontainers.image.description="Backend de la plataforma de retención estudiantil" \
      org.opencontainers.image.source="https://github.com/Andres523-star/prevencion-abandono-backend"
# Sin herramientas de compilación ni shell de administración extra; parches de seguridad del sistema base.
RUN apk upgrade --no-cache
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./
USER 10001
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
