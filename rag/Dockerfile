# Etapa 1: Construcción (Build)
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Verificar versión de Java
RUN java -version

# Copiar archivos de configuración de Gradle
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Dar permisos de ejecución al script de Gradle
RUN chmod +x ./gradlew

# ✅ AGREGA ESTAS LÍNEAS - Limpia cache y fuerza Gradle 9.4+
RUN rm -rf ~/.gradle/caches
RUN ./gradlew wrapper --gradle-version 8.10.2 --no-daemon

# Descargar las dependencias (mejora el cache de Docker)
RUN ./gradlew dependencies --no-daemon || true

# Copiar el código fuente
COPY src src

# Construir la aplicación omitiendo los tests para mayor rapidez
RUN ./gradlew clean build -x test --no-daemon

# Etapa 2: Ejecución (Run)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copiar el JAR generado desde la etapa de construcción
COPY --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar

# Exponer el puerto configurado en application.properties
EXPOSE 8082

# Ejecutar la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]


# docker build -t aiva-rag-backend .
# mkdir -p $(pwd)/fedora-docs
# podman run -d   --name aiva-rag-backend   -p 8082:8082   -v $(pwd)/fedora-docs:/app/fedora-docs:Z   aiva-rag-backend