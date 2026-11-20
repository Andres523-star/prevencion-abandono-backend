# Imagen del demo-backend para Railway (o cualquier plataforma con Docker).
# Etapa 1: compila el JAR con el wrapper de Maven. Etapa 2: solo el JRE, sin herramientas de build.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/permanencia-demo.jar app.jar
# Usuario sin privilegios: el proceso no corre como root.
USER app
# El demo genera 1,1 millones de estudiantes en memoria: se usa el 75 % de la RAM del contenedor y,
# si se agota, el proceso termina para que la plataforma lo reinicie en lugar de quedar colgado.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Railway define PORT; la aplicación lo lee (server.port: ${PORT:8080}).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
