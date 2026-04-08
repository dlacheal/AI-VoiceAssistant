# Proyecto RAG AIVA (Retrieval-Augmented Generation)

Este proyecto implementa un sistema RAG (Retrieval-Augmented Generation) en Java utilizando **Spring Boot** y **Spring AI**. Actúa como el backend de conocimiento y procesamiento del asistente de voz AIVA, permitiendo ingerir documentos (texto y PDFs) y responder preguntas basadas exclusivamente en el contexto almacenado.

---

## 🏗️ Arquitectura y Tecnologías

El sistema está construido sobre una arquitectura moderna basada en la nube:

* **Framework Base**: Spring Boot 3.4.3 (Java 21)
* **Inteligencia Artificial**: Spring AI (Framework oficial de Spring para desarrollo con IA)
* **Base de Datos Vectorial**: PostgreSQL con la extensión `pgvector`
* **Modelos Nativos Locales**: Integración con [Ollama](https://ollama.com/) (ej. modelo `qwen2.5:3b`)
* **Despliegue**: Docker / Podman (para aislamiento hipervisor)

### ¿Por qué Spring AI en lugar de LangChain o LlamaIndex?

Se decidió utilizar la especificación oficial de **Spring AI** para integrarse de forma nativa e idiomática con el ecosistema de Spring Java (inyección de dependencias, `@Service`, `application.properties`). Spring AI adapta los mismos conceptos comprobados de LangChain y LlamaIndex a las interfaces robustas de Java:

1. **`ChatClient`** *(en vez de LLM Chain de LangChain)*: Spring provee una API fluida y resiliente para interactuar con los Modelos de Lenguaje (Ollama). Permite añadir historial, avisos de sistema (System Prompts) y contexto de forma tipada, sin la complejidad manual de encadenar componentes no estructurados como las cadenas de LangChain.
2. **`VectorStore`** *(para la base de datos vectorial PGVector)*: Abstrae la base de datos de vectores. Permite cambiar entre PGVector, Chroma o Milvus con solo cambiar una línea en `application.properties`. Se encarga unificadamente de calcular la similaridad del coseno (`COSINE_DISTANCE`) en las búsquedas en vez de hacer queries en duro.
3. **`PagePdfDocumentReader`** *(como los Document Loaders de LlamaIndex)*: Utilizado para leer archivos PDF (normativas de SBS, manuales). Spring AI usa librerías Java consolidadas internamente y lo transforma de forma estandarizada a la abstracción de `Document`, inyectando la metadata (como el nombre del archivo `source`) de forma predeterminada para asegurar la trazabilidad.
4. **`TokenTextSplitter`** *(como los Text Splitters de LangChain)*: Divide textos gigantescos en fragmentos semánticos usando **Chunking Avanzado con Overlap**. Retiene 350 tokens de solapamiento para garantizar que ninguna oración o normativa se rompa por la mitad.

Adicionalmente, se cuenta con extracciones ricas orientada a normativas de la SBS (NLP Metadata) usando Regex para identificar Artículos o Capítulos e inyectarlos localmente a la BD de PGVector, y un **Re-Ranking Avanzado** (Cross-Encoder Semántico) implementado en el propio motor de búsquedas para pulir y entregar exactamente los párrafos que importan reduciendo los tokens y costos enviados al modelo.

---

## 🛠️ Entorno de Base de Datos (PostgreSQL + PGVector)

El proyecto requiere una base de datos PostgreSQL con la extensión **pgvector** habilitada. Puedes levantarla rápidamente usando Docker o Podman:

```bash
docker run --name aiva-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=1234 \
  -e POSTGRES_DB=postgres \
  -p 25432:5432 \
  -d pgvector/pgvector:pg16
```

### Inicialización de Tablas

Una vez levantada la base de datos, ejecuta el siguiente script SQL (DBeaver, pgAdmin o `psql`):

```sql
-- 1. Habilitar la extensión de vectores
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Crear la tabla principal del Vector Store (Spring AI)
CREATE TABLE IF NOT EXISTS vector_store_e5_v1 (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    content text,
    metadata jsonb,
    embedding vector(384)   -- 384 dimensiones (multilingual-e5-small)
);

-- 3. Índice HNSW para búsqueda semántica eficiente
CREATE INDEX ON vector_store_e5_v1 USING hnsw (embedding vector_cosine_ops);

-- 4. Índice GIN para Full-Text Search en español
CREATE INDEX IF NOT EXISTS vector_store_e5_v1_content_idx
    ON vector_store_e5_v1 USING GIN (to_tsvector('spanish', content));



-- ─────────────────────────────────────────────────────────────
-- PROMPT TRACKING — tablas creadas automáticamente al arrancar
-- (Spring las inicializa con spring.sql.init al levantar la app)
-- ─────────────────────────────────────────────────────────────

-- 5. Versiones de System Prompts
CREATE TABLE IF NOT EXISTS prompt_versions (
    id            BIGSERIAL    PRIMARY KEY,
    prompt_name   VARCHAR(100) NOT NULL,
    version       VARCHAR(30)  NOT NULL,
    content       TEXT         NOT NULL,
    description   TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT uq_prompt_name_version UNIQUE (prompt_name, version)
);

-- 6. Registro de ejecuciones del LLM
CREATE TABLE IF NOT EXISTS prompt_executions (
    id                BIGSERIAL    PRIMARY KEY,
    prompt_version_id BIGINT       REFERENCES prompt_versions(id) ON DELETE SET NULL,
    prompt_name       VARCHAR(100) NOT NULL,
    question          TEXT         NOT NULL,
    context_snippets  INT          NOT NULL DEFAULT 0,
    response_preview  TEXT,
    latency_ms        BIGINT,
    executed_at       TIMESTAMP    NOT NULL DEFAULT now()
);

-- Índices para consulta rápida del Prompt Tracking
CREATE INDEX IF NOT EXISTS idx_prompt_versions_name   ON prompt_versions  (prompt_name);
CREATE INDEX IF NOT EXISTS idx_prompt_versions_active ON prompt_versions  (prompt_name, is_active);
CREATE INDEX IF NOT EXISTS idx_prompt_executions_name ON prompt_executions (prompt_name);
CREATE INDEX IF NOT EXISTS idx_prompt_executions_ver  ON prompt_executions (prompt_version_id);
```

> **Nota**: Las tablas de **Prompt Tracking** (`prompt_versions`, `prompt_executions`) también se crean automáticamente al arrancar la app gracias a la configuración `spring.sql.init.schema-locations` en `application.properties`. El script manual es solo para referencia o entornos donde el init automático esté desactivado.

---

## 🔄 Prompt Tracking — Versionado y Trazabilidad

El sistema registra automáticamente cada versión de los System Prompts y todas las llamadas al LLM, permitiendo comparar cómo evolucionan los prompts y medir su impacto en latencia y calidad.

### ¿Cómo funciona?

**Al arrancar la app:**
1. `SbsRagService` y `RagService` ejecutan un `@PostConstruct` que detecta si el contenido del prompt cambió.
2. Si es la primera vez → crea la versión `1.0.0`.
3. Si el contenido cambió respecto a la versión activa → desactiva la anterior y crea `1.1.0`, `1.2.0`, etc.
4. Si el contenido es idéntico → no hace nada (idempotente).

**En cada llamada al LLM:**
- Se mide la latencia (`startTime` → `endTime`).
- Se registra en `prompt_executions`: pregunta, versión usada, snippets RAG enviados, preview de la respuesta (500 chars) y latencia.

**Para versionar un prompt manualmente:**
1. Modifica el texto de `SYSTEM_PROMPT_TEMPLATE` en `SbsRagService.java` o `RagService.java`.
2. Reinicia la app → la versión se crea automáticamente.

### Prompts registrados

| Nombre | Servicio | Descripción |
|--------|----------|-------------|
| `SBS_AUDITOR` | `SbsRagService` | Auditor normativo SBS — responde en JSON con referencia legal |
| `AIVA_RAG` | `RagService` | Asistente corporativo AIVA con Chain-of-Thought |

---

## 🔍 Recuperación Avanzada (Hybrid Search & Top-K)

Para optimizar la precisión y recall de los resultados, el sistema implementa técnicas avanzadas de recuperación en los servicios `RagService` y `SbsRagService`:

1. **Top-K Paramétrico**: El número de documentos recuperados (`rag.retrieval.topK`) y los umbrales de similitud se controlan desde `application.properties` para poder afinarse sin recompilación.
2. **Búsqueda Híbrida (Vector + Keyword Search)**: Las consultas no solo se basan en similitud de cosenos (Vector Search) sino que cruzan los datos simultáneamente con una búsqueda por coincidencia de palabras exacta (Full-Text Search) nativa mediante `JdbcTemplate`.
3. **Fusión RRF (Reciprocal Rank Fusion)**: Los resultados provenientes de ambas listas (semántica y por palabra clave) se unifican algorítmicamente ponderando sus rankings mediante la fórmula de *Reciprocal Rank Fusion*.

---

## 🛡️ Ingeniería de Prompts (Guardrails & Anti-Hallucination)

Para llevar la confiabilidad del sistema de "experimental" a "grado de producción" (Enterprise), se han implementado técnicas avanzadas de Prompt Engineering:

1. **System Prompts Estructurados**: Separación clara de contexto, directivas y restricciones usando meta-etiquetas (`[ROLE]`, `[CONSTRAINTS]`, `<context>`).
2. **Output Guardrails (Formato Resiliente)**: `SbsRagService` fuerza al LLM a estructurar la respuesta exclusivamente como JSON validado (`SbsResponse`).
3. **Input Guardrails & Anti-Hallucination**: Restricción explícita de *Groundedness* — si la información no está en el contexto, el modelo responde que no la tiene en lugar de especular.
4. **Context Steering (Few-Shot Prompting)**: La memoria se inicializa inyectando ejemplos Chain-of-Thought para alinear el tono y razonamiento del bot.

---

## 📊 Observabilidad (OpenTelemetry & Arize Phoenix)

El proyecto incluye instrumentación completa nativa con **OpenTelemetry (OTLP)**. Todos los tiempos de procesamiento de texto, inserción en la base de datos vectorial (`PgVectorStore`), parseo de respuestas, llamadas a la API de Ollama y el consumo exacto de tokens (Prompt y Generation) se envían automáticamente al servidor de observabilidad.

Utilizamos **Arize Phoenix**, una plataforma open-source especializada en visualizar grafos de ejecución (Spans), rastrear la recuperación del RAG y evaluar el desempeño de la capa LLM.

### 1. Iniciar Arize Phoenix con Docker
En la terminal de tu entorno, despliega Phoenix. Toma en cuenta que usa su propio puerto `6006` tanto para recibir la telemetría OTLP HTTP como para acceder a la Interfaz Gráfica (Dashboard Ui):

```bash
docker run -d \
  --name aiva-phoenix \
  -p 6006:6006 \
  -p 4317:4317 \
  arizephoenix/phoenix:latest
```

*(El puente OTLP está configurado en `application.properties` para apuntar a `http://[IP-SERVIDOR]:6006/v1/traces`)*

### 2. Acceso al Dashboard
Una vez que Phoenix y la aplicación Spring estén corriendo:
1. Realiza al menos una invocación a un endpoint (ej: `/api/sbs/ask`).
2. Abre tu navegador y navega hacia `http://[IP-SERVIDOR]:6006`.
3. Dirígete a la sección **Traces** o **LLMs** para ver el rastreo milimétrico de la generación del texto y de la memoria usada en tu modelo RAG.

---

## 🧪 Experimentación y MLOps (MLflow Tracking)

Para gestionar rigurosamente el ajuste de hiperparámetros (Chunk size, Top-K, model types) y observar qué configuración obtiene mejor *Accuracy* sin perder memoria de pruebas pasadas, el RAG integra un cliente HTTP nativo hacia **MLflow**.

A diferencia de las arquitecturas en Python, este proyecto invoca las APIs REST de MLflow transparentemente desde los servicios Java (`MlflowClientService.java`). Cada vez que evalúas el Ground Truth en `/api/evaluate/scoring`, Spring Boot inyecta silenciosamente los resultados a MLflow.

### Despliegue del Servidor MLflow (Local)
Para habilitarlo, levanta el puerto `5000`. Debido a las políticas estrictas de seguridad de MLflow (CORS/FastAPI), si experimentas errores de conexión o HTTP 403, permite cualquier host usando comodines (solo seguro para testing en red local):

```bash
docker run -d \
  --name aiva-mlflow \
  -p 5000:5000 \
  ghcr.io/mlflow/mlflow mlflow server \
  --host 0.0.0.0 \
  --serve-artifacts \
  --allowed-hosts "*" \
  --cors-allowed-origins "*"
```

*(Una vez ejecutado, abre `http://localhost:5000` o la IP de tu servidor en tu navegador para ver las métricas de Accuracy de RAG)*

---

## 🔀 Versionado de Embeddings y A/B Testing

Para permitir escalabilidad y pruebas sin corromper la memoria del sistema, el RAG implementa un modelo de **colecciones aisladas** (Collection-per-Version).

En lugar de utilizar una tabla dura y quemada en código, el sistema enruta las peticiones de búsquedas e inserciones a través de un proxy (`RoutingVectorStore.java`) y soporta Tablas Dinámicas mediante `application.properties`:

```properties
spring.ai.vectorstore.pgvector.table-name=vector_store_e5_v1
```

### Ventajas de este Patrón:
1. **Preservación Segura:** Tus tablas e índices creados con modelos locales (ej. `e5-small` a 384 dimensiones) quedan blindados en su propia tabla base (`vector_store_e5_v1`).
2. **A/B Testing en Vivo:** En un futuro cercano, conectando `ModelContextHolder`, la aplicación RAG puede usar al vuelo la tabla `vector_store_openai_v2` con índices de 1536 dimensiones, sin chocar ni sobreescribir vectores antiguos. 

*(El versionado local ya fue inicializado mediante la migración Liquibase V3).*

---

## 📜 Data Lineage & Versionado Documental (Blob Storage Local)

El sistema ahora cuenta con trazabilidad absoluta histórica. Cada vez que se sube un PDF a la ruta del *FileWatcher*, no solo se indexa, sino que el sistema calcula su **Hash SHA-256**. 

1. **Idempotencia (Ahorro de Costos):** Si el Hash ya existe en el sistema, el sistema ignora la tarea y bloquea el chunking costoso, ahorrando CPU y tokens.
2. **Respaldo Histórico (Archive):** Si el Hash es diferente (una versión modificada del PDF), se genera una **versión incremental** en la base de datos (Ej: `v2`) y el documento físico es blindado permanentemente como Blob en el directorio `rag.document-archive.path` (por defecto `./fedora-docs-archive/`), renombrado similar a: `ISO-9001_v2_f8a9e22b.pdf`.
3. **Saneamiento Vectorial:** Simultáneamente elimina los fragmentos de la versión antigua en Postgres y guarda la nueva versión. Así, el RAG solo responde sobre la última revisión, pero guardamos el archivo inmutable previo por si un "Auditor Humano" necesita investigar de dónde sacó una respuesta el LLM el mes pasado.

*(Consulta la tabla PostgreSQL `document_versions` para ver el historial y bitácora de toda la documentación corporativa inyectada).*

---

## ⚙️ Compilación del Proyecto (Build)

```bash
./gradlew clean build -x test
```

| Flag | Propósito |
|------|-----------|
| `clean` | Elimina la carpeta `build/` anterior |
| `build` | Compila el código `.java` y genera el `.jar` ejecutable |
| `-x test` | Excluye las pruebas unitarias (evita `OutOfMemoryError` en entornos limitados) |

> ⚠️ El primer build descarga los modelos ONNX de embedding (~100MB) y cross-encoder (~87MB) desde HuggingFace. En conexiones lentas puede tomar 20-40 minutos. Las ejecuciones posteriores usan caché de Gradle.

---

## 🚀 Despliegue en Servidor Fedora con Docker/Podman

**1. Construir la imagen:**
```bash
podman build -t aiva-rag-backend .
```

**2. Ejecutar la aplicación (con volumen para el File Watcher):**
```bash
# Paso 1: Crear directorio de PDFs
mkdir -p $(pwd)/fedora-docs

# Paso 2: Iniciar el contenedor
podman run -d \
  --name aiva-rag-backend \
  -p 8082:8082 \
  -v $(pwd)/fedora-docs:/app/fedora-docs:Z \
  aiva-rag-backend
```

> El flag `:Z` es obligatorio en Fedora/RHEL porque SELinux bloquea accesos a volúmenes por defecto.

---

## 🧪 Pruebas — Guía completa de Endpoints (curl)

La aplicación corre en `http://localhost:8082`. Sustituye `localhost` por la IP de tu servidor Fedora si aplica.

---

### 📄 1. Ingestar conocimiento manual (DocumentController)

Inyecta texto libre directamente al `vector_store`.

```bash
curl -X POST 'http://localhost:8082/api/documents' \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "El asistente AIVA fue desarrollado por Saamcito en el año 2026. Es un asistente de voz avanzado para Android.",
    "source": "conocimiento_interno"
  }'
```

---

### 💬 2. Chat General con contexto RAG (ChatController)

Preguntas conversacionales — recupera documentos del `vector_store` y responde con contexto.

```bash
curl -X POST 'http://localhost:8082/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{
    "message": "¿Qué es programación orientada a objetos?",
    "conversationId": "sesion-prueba"
  }'
```

---

### 📚 3. Indexar PDFs de normativas SBS (SbsRagController)

> El **File Watcher** indexa automáticamente los PDFs que se copien a la carpeta `fedora-docs/`. Este endpoint dispara la indexación manual de los PDFs ubicados en `src/main/resources/docs/`.

```bash
curl -X POST 'http://localhost:8082/api/sbs/index'
```

---

### ⚖️ 4. Preguntar al auditor normativo SBS (SbsRagController)

Responde **exclusivamente** con información de los PDFs indexados, en formato JSON estructurado.

```bash
curl -X POST 'http://localhost:8082/api/sbs/ask' \
  -H 'Content-Type: application/json' \
  -d '{"question": "¿Qué dice la norma ISO 27001?"}'
```

**Respuesta esperada (Output Guardrail JSON):**
```json
{
  "respuesta": "La norma ISO/IEC 27001 detalla los requisitos para establecer, implementar, mantener y mejorar continuamente un SGSI.",
  "referencia_legal": "Norma ISO 27001"
}
```

---

### 🏆 5. Evaluación automática de respuestas (HybridScoringController)

Mide qué tan correcta fue la respuesta generada comparada con una respuesta esperada ("Ground Truth").

#### ⚡ Fast Path — Similitud lexical alta (< 5 ms, sin LLM)

```bash
curl -X POST 'http://localhost:8082/api/evaluate/scoring' \
  -H 'Content-Type: application/json' \
  -d '{
    "generatedAnswer": "La SBS es la superintendencia de banca",
    "expectedAnswer": "la superintendencia de banca es la sbs"
  }'
```

**Respuesta esperada:**
```json
{
  "score": 100.0,
  "methodUsed": "SIMPLE",
  "reasoning": "Fast lexical similarity matched with high confidence."
}
```

#### 🧠 Slow Path — Fallback semántico al LLM (~500 ms)

```bash
curl -X POST 'http://localhost:8082/api/evaluate/scoring' \
  -H 'Content-Type: application/json' \
  -d '{
    "generatedAnswer": "El organismo fiscalizador del sistema financiero y de seguros peruano.",
    "expectedAnswer": "La Superintendencia encargada de supervisar los bancos y sus afiliadas en el Perú."
  }'
```

**Respuesta esperada:**
```json
{
  "score": 90.0,
  "methodUsed": "LLM",
  "reasoning": "Ambas respuestas describen el mismo rol normativo e institucional."
}
```

---

### 📊 6. Monitoreo en Tiempo Real (Spring Boot Actuator)

> ⚠️ Las métricas se inicializan de forma **Lazy** — aparecen solo después de la primera evaluación del Paso 5.

```bash
# Tráfico total de validaciones
curl 'http://localhost:8082/actuator/metrics/rag.scoring.validations'

# Filtrar solo las procesadas por el LLM
curl 'http://localhost:8082/actuator/metrics/rag.scoring.validations?tag=method:LLM'

# Latencia y tiempos de ejecución
curl 'http://localhost:8082/actuator/metrics/rag.scoring.latency'

# Salud general de la app
curl 'http://localhost:8082/actuator/health'
```

---

### 🔎 7. Prompt Tracking — Versionado y Comparación de Prompts

Estos endpoints permiten consultar y comparar la evolución de los System Prompts del sistema.

#### Listar todos los prompts registrados
```bash
curl 'http://localhost:8082/api/prompts'
```
```json
{
  "total": 2,
  "prompts": ["AIVA_RAG", "SBS_AUDITOR"]
}
```

#### Historial de versiones de un prompt
```bash
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/versions'
```
```json
{
  "prompt_name": "SBS_AUDITOR",
  "total_versions": 2,
  "versions": [
    { "id": 2, "version": "1.1.0", "isActive": true, "createdAt": "2026-04-02T..." },
    { "id": 1, "version": "1.0.0", "isActive": false, "createdAt": "2026-03-28T..." }
  ]
}
```

#### Versión activa actual
```bash
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/versions/active'
```

#### Registrar una versión nueva manualmente
```bash
curl -X POST 'http://localhost:8082/api/prompts/SBS_AUDITOR/versions' \
  -H 'Content-Type: application/json' \
  -d '{
    "version":     "2.0.0",
    "content":     "[ROLE]: Nueva versión del auditor SBS...",
    "description": "Se agregó restricción de idioma"
  }'
```

#### Comparar dos versiones (diff unificado)
```bash
curl 'http://localhost:8082/api/prompts/compare?id1=1&id2=2'
```
```json
{
  "version_a": { "id": 1, "version": "1.0.0", "prompt_name": "SBS_AUDITOR" },
  "version_b": { "id": 2, "version": "1.1.0", "prompt_name": "SBS_AUDITOR" },
  "diff": "--- SBS_AUDITOR v1.0.0\n+++ SBS_AUDITOR v1.1.0\n@@ -3,4 +3,5 @@\n ..."
}
```

#### Historial de ejecuciones (últimas 100)
```bash
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/executions'
```
```json
{
  "prompt_name": "SBS_AUDITOR",
  "total": 37,
  "executions": [
    {
      "promptVersionId": 2,
      "question": "¿Qué dice la norma ISO 27001?",
      "contextSnippets": 3,
      "latencyMs": 1420,
      "executedAt": "2026-04-02T..."
    }
  ]
}
```

#### Estadísticas de latencia por versión
```bash
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/stats'
```
```json
{
  "prompt_name": "SBS_AUDITOR",
  "stats_by_version": [
    {
      "version": "1.1.0",
      "total_executions": 25,
      "avg_latency_ms": 1380,
      "min_latency_ms": 890,
      "max_latency_ms": 3200,
      "is_active": true
    },
    {
      "version": "1.0.0",
      "total_executions": 12,
      "avg_latency_ms": 1650,
      "min_latency_ms": 1100,
      "max_latency_ms": 4100,
      "is_active": false
    }
  ]
}
```

---

## 📋 Resumen de Todos los Endpoints

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/documents` | Ingestar texto al vector store |
| `POST` | `/api/chat` | Chat general RAG con historial |
| `POST` | `/api/sbs/index` | Indexar PDFs de normativas |
| `POST` | `/api/sbs/ask` | Pregunta al auditor SBS (JSON) |
| `POST` | `/api/evaluate/scoring` | Evaluar calidad de respuesta |
| `GET`  | `/actuator/health` | Salud del servidor |
| `GET`  | `/actuator/metrics/rag.scoring.validations` | Métricas de validación |
| `GET`  | `/actuator/metrics/rag.scoring.latency` | Métricas de latencia |
| `GET`  | `/api/prompts` | Listar prompts registrados |
| `GET`  | `/api/prompts/{name}/versions` | Historial de versiones |
| `GET`  | `/api/prompts/{name}/versions/active` | Versión activa actual |
| `POST` | `/api/prompts/{name}/versions` | Registrar nueva versión manualmente |
| `GET`  | `/api/prompts/compare?id1=X&id2=Y` | Diff entre dos versiones |
| `GET`  | `/api/prompts/{name}/executions` | Historial de ejecuciones |
| `GET`  | `/api/prompts/{name}/stats` | Estadísticas por versión |

---

## 🖥️ Ejemplos de uso con curl

Referencia rápida de todos los comandos curl del proyecto. Reemplaza `localhost` por la IP de tu servidor si aplica (ej. `<IP_DEL_SERVIDOR>`).

> **Base URL:** `http://localhost:8082`

---

### RAG General

```bash
# ── Ingestar texto libre al Vector Store ──────────────────────────────────────
curl -X POST 'http://localhost:8082/api/documents' \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "El asistente AIVA fue desarrollado por Saamcito en 2026. Es un asistente de voz avanzado para Android.",
    "source": "conocimiento_interno"
  }'

# ── Chat conversacional con contexto RAG ──────────────────────────────────────
curl -X POST 'http://localhost:8082/api/chat' \
  -H 'Content-Type: application/json' \
  -d '{
    "message": "¿Quién creó a AIVA?",
    "conversationId": "sesion-001"
  }'

# ── Limpiar historial de una conversación ─────────────────────────────────────
curl -X DELETE 'http://localhost:8082/api/chat/sesion-001'
```

---

### Normativas SBS

```bash
# ── Indexar PDFs manualmente (desde resources/docs/) ──────────────────────────
curl -X POST 'http://localhost:8082/api/sbs/index'

# ── Consulta al auditor normativo SBS (respuesta en JSON) ────────────────────
curl -X POST 'http://localhost:8082/api/sbs/ask' \
  -H 'Content-Type: application/json' \
  -d '{"question": "¿Qué dice la norma ISO 27001?"}'

# ── Otra consulta de ejemplo ───────────────────────────────────────────────────
curl -X POST 'http://localhost:8082/api/sbs/ask' \
  -H 'Content-Type: application/json' \
  -d '{"question": "¿Cuáles son los requisitos de gestión de riesgos según la SBS?"}'
```

---

### Evaluación de Calidad (Hybrid Scoring)

```bash
# ── Fast Path: similitud léxica alta (sin LLM, < 5 ms) ───────────────────────
curl -X POST 'http://localhost:8082/api/evaluate/scoring' \
  -H 'Content-Type: application/json' \
  -d '{
    "generatedAnswer": "La SBS es la superintendencia de banca",
    "expectedAnswer":  "la superintendencia de banca es la sbs"
  }'

# ── Slow Path: fallback semántico al LLM (~500 ms) ───────────────────────────
curl -X POST 'http://localhost:8082/api/evaluate/scoring' \
  -H 'Content-Type: application/json' \
  -d '{
    "generatedAnswer": "El organismo fiscalizador del sistema financiero y de seguros peruano.",
    "expectedAnswer":  "La Superintendencia encargada de supervisar los bancos y sus afiliadas en el Perú."
  }'
```

---

### Observabilidad (Actuator)

```bash
# ── Salud general ─────────────────────────────────────────────────────────────
curl 'http://localhost:8082/actuator/health'

# ── Total de validaciones realizadas ──────────────────────────────────────────
curl 'http://localhost:8082/actuator/metrics/rag.scoring.validations'

# ── Solo las procesadas por el LLM ────────────────────────────────────────────
curl 'http://localhost:8082/actuator/metrics/rag.scoring.validations?tag=method:LLM'

# ── Latencia del scoring ──────────────────────────────────────────────────────
curl 'http://localhost:8082/actuator/metrics/rag.scoring.latency'
```

---

### Prompt Tracking

```bash
# ── Listar todos los prompts registrados ──────────────────────────────────────
curl 'http://localhost:8082/api/prompts'

# ── Historial de versiones de un prompt ───────────────────────────────────────
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/versions'
curl 'http://localhost:8082/api/prompts/AIVA_RAG/versions'

# ── Versión activa actual ─────────────────────────────────────────────────────
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/versions/active'
curl 'http://localhost:8082/api/prompts/AIVA_RAG/versions/active'

# ── Comparar dos versiones (diff unificado línea a línea) ────────────────────
curl 'http://localhost:8082/api/prompts/compare?id1=1&id2=2'

# ── Registrar nueva versión manualmente ──────────────────────────────────────
curl -X POST 'http://localhost:8082/api/prompts/SBS_AUDITOR/versions' \
  -H 'Content-Type: application/json' \
  -d '{
    "version":     "2.0.0",
    "content":     "[ROLE]: Nueva versión del auditor SBS...",
    "description": "Agrego restricción de idioma y nuevas reglas de citado"
  }'

# ── Historial de ejecuciones (últimas 100) ────────────────────────────────────
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/executions'
curl 'http://localhost:8082/api/prompts/AIVA_RAG/executions'

# ── Estadísticas de latencia por versión ─────────────────────────────────────
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/stats'
curl 'http://localhost:8082/api/prompts/AIVA_RAG/stats'
```

---

### Flujo completo de prueba recomendado

```bash
# 1. Verificar que la app está lista
curl 'http://localhost:8082/actuator/health'

# 2. Ingestar conocimiento
curl -X POST 'http://localhost:8082/api/documents' \
  -H 'Content-Type: application/json' \
  -d '{"content": "La SBS supervisa bancos, seguros y AFPs en Perú.", "source": "sbs-info"}'

# 3. Indexar PDFs normativos
curl -X POST 'http://localhost:8082/api/sbs/index'

# 4. Hacer una consulta normativa
curl -X POST 'http://localhost:8082/api/sbs/ask' \
  -H 'Content-Type: application/json' \
  -d '{"question": "¿Qué entidades supervisa la SBS?"}'

# 5. Ver qué versión del prompt se usó y su latencia
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/stats'

# 6. Revisar el historial de la última ejecución
curl 'http://localhost:8082/api/prompts/SBS_AUDITOR/executions'
```
