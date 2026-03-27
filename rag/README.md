# Proyecto RAG AIVA (Retrieval-Augmented Generation)

Este proyecto implementa un sistema RAG (Retrieval-Augmented Generation) en Java utilizando **Spring Boot** y **Spring AI**. Actúa como el backend de conocimiento y procesamiento del asistente de voz AIVA, permitiendo ingerir documentos (texto y PDFs) y responder preguntas basadas exclusivamente en el contexto almacenado.

---

## 🏗️ Arquitectura y Tecnologías

![Arquitectura General End-to-End](../docs/Arquitectura%20General_End-to-End.png)

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

![Pipeline de Ingesta de Documentos](../docs/Pipeline%20de%20Ingesta%20de%20Documentos.png)

---

## 🛠️ Entorno de Base de Datos (PostgreSQL + PGVector)

El proyecto requiere una base de datos PostgreSQL con la extensión **pgvector** habilitada. Puedes levantarla rápidamente usando Docker o Podman:

```bash
docker run --name aiva-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=<YOUR_DB_PASSWORD> \
  -e POSTGRES_DB=postgres \
  -p 25432:5432 \
  -d pgvector/pgvector:pg16
```

Una vez levantada, aunque Spring AI puede encargarse automáticamente de inicializar las tablas, es altamente recomendable ejecutar el siguiente script SQL directo en tu cliente de PostgreSQL (DBeaver, pgAdmin) para preparar la base de datos para tus normativas e índices HNSW de alta velocidad:

```sql
-- 1. Habilitar la extensión de vectores
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Crear la tabla para tus ISOs (27001 y 42001)
CREATE TABLE IF NOT EXISTS vector_store (
	id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
	content text,
	metadata jsonb,
	embedding vector(384) -- 384 es la dimensión de TransformersEmbeddingModel
);

-- 3. Crear un índice HNSW (Para búsquedas semánticas eficientes)
CREATE INDEX ON vector_store USING hnsw (embedding vector_cosine_ops);

-- 4. Crear un índice GIN (Para optimizar la búsqueda Full-Text Keyword Search)
CREATE INDEX IF NOT EXISTS vector_store_content_idx ON vector_store USING GIN (to_tsvector('spanish', content));
```

---

## 🔍 Recuperación Avanzada (Hybrid Search & Top-K)

![Pipeline RAG Detalle Interno](../docs/Pipeline%20RAG_Detalle%20Interno.png)

Para optimizar la precisión y recall de los resultados, el sistema implementa técnicas avanzadas de recuperación en los servicios `RagService` y `SbsRagService`:

1. **Top-K Paramétrico**: El número de documentos recuperados (`rag.retrieval.topK`) y los umbrales de similitud se controlan desde `application.properties` para poder afinarse sin requiere recompilación.
2. **Búsqueda Híbrida (Vector + Keyword Search)**: Las consultas no solo se basan en similitud de cosenos (Vector Search) proveniente de los *Transformers Embeddings*, sino que cruzan los datos simultáneamente con una búsqueda por coincidencia de palabras exacta (Full-Text Search) nativa mediante el `JdbcTemplate` de PostgreSQL.
3. **Fusión RRF (Reciprocal Rank Fusion)**: Los resultados provenientes de ambas listas (semántica y por palabra clave) se unifican algorítmicamente ponderando sus rankings mediante la fórmula de *Reciprocal Rank Fusion*, garantizando que los fragmentos de contexto más valiosos se envíen al LLM.

---

## 🛡️ Ingeniería de Prompts (Guardrails & Anti-Hallucination)

Para llevar la confiabilidad del sistema de "experimental" a "grado de producción" (nivel Enterprise), se han implementado técnicas avanzadas de Prompt Engineering:

1. **System Prompts Estructurados**: Separación clara de contexto, directivas y restricciones usando meta-etiquetas (ej. `[ROLE]`, `[CONSTRAINTS]`, `<context>`). Esto permite que modelos de lenguaje reaccionen a reglas de manera asombrosamente estricta sin confundir instrucciones con datos.
2. **Output Guardrails (Formato Resiliente)**: El servicio `SbsRagService` fuerza al LLM a estructurar la respuesta exclusivamente como un objeto JSON validado (`SbsResponse`). Se integró un mecanismo *"Fail-Safe"* de extracción nativa que repara la respuesta en milisegundos en caso de que el modelo intente inyectar texto o etiquetas Markdown alrededor del JSON.
3. **Input Guardrails & Anti-Hallucination**: Restricción explícita de *Groundedness* que anula las alucinaciones. Si la pregunta busca temas prohibidos (Prompt Injection), recetas o información inexistente en la Base de Datos PGVector, el modelo está rígidamente parametrizado para responder que no tiene la información en vez de especular.
4. **Context Steering (Few-Shot Prompting)**: En la clase `RagService`, la memoria se inicializa dinámicamente inyectando ejemplos previos *Chain-of-Thought* (Razonamiento Paso a Paso). Esto alinea forzosamente el tono e inteligencia del bot para que imite esa misma estructura lógica en tu pregunta.

---

## ⚙️ Compilación del Proyecto (Build)

Antes de generar una nueva versión de la imagen Docker o si se han hecho cambios en el código (como inyectar los Módulos RAG Avanzados), debes compilar el artefacto ejecutable.

**Comando:**
```bash
./gradlew clean build -x test
```

**¿Para qué se usa?**
- `clean`: Elimina la carpeta `build/` anterior garantizando que no haya código "fantasma" o residual.
- `build`: Empaqueta y compila el código `.java` generando el archivo ejecutable (`.jar`).
- `-x test`: Instruye al compilador a **excluir u omitir deliberadamente** la ejecución de las pruebas unitarias.

**¿Cuándo se usa?**
- Cuando estás desarrollando rápido e iterativamente y no quieres perder tiempo corriendo tests o levantando contextos (Spring Boot demora en levantar para testear).
- Cuando tu servidor o entorno local se queda sin memoria RAM al intentar correr las pruebas (`OutOfMemoryError`) pero el código funciona estáticamente bien.
- Como paso estrictamente previo antes de correr `podman build / docker build`.

---

## 🚀 Despliegue en Servidor Fedora con Docker/Podman

![Despliegue Nivel Enterprise](../docs/Despliegue_Nivel%20Enterprise.png)

El proyecto cuenta con un `Dockerfile` optimizado en dos etapas (Builder y Runtime) para asegurar un peso mínimo en producción y el cacheo de dependencias de Gradle.

**1. Construir la imagen:**
```bash
podman build -t aiva-rag-backend .
```

**2. Ejecutar la aplicación (con volumen para el File Watcher):**
Debido a que cuentas con un File Watcher interno que escucha sobre `/app/fedora-docs`, necesitas crear el directorio local y montarlo. Como estás en Fedora/RHEL, SELinux bloquea los accesos a volúmenes por defecto, por ello es obligatorio pasar el flag de seguridad `:Z`:

```bash
# Paso 1: Crear directorio de PDFs en la ruta local actual de Fedora
mkdir -p $(pwd)/fedora-docs

# Paso 2: Iniciar contenedor exponiendo el puerto 8082 y montando el volumen con permisos de SELinux (:Z)
podman run -d \
  --name aiva-rag-backend \
  -p 8082:8082 \
  -v $(pwd)/fedora-docs:/app/fedora-docs:Z \
  aiva-rag-backend
```

---

## 🧪 Instrucciones de Prueba (Postman / cURL)

A continuación, los comandos y el orden exacto de ejecución sugerido para probar las funcionalidades del flujo RAG en Postman tras levantar tu servidor:

### Paso 1: Ingestar conocimiento explícito manual (DocumentController)
Puedes forzar inyección de conocimiento en el `vector_store` mediante strings crudos para enriquecer la IA con contexto personalizado.

**Request:**
```bash
curl --location 'http://[IP_ADDRESS]:8082/api/documents' \
--header 'Content-Type: application/json' \
--data '{
    "content": "El asistente AIVA fue desarrollado de forma exclusiva por Saamcito en el año 2026. AIVA es un asistente de voz avanzado para Android.",
    "source": "conocimiento_interno"
}'
```

### Paso 2: Interactuar con el Chat General (RagController)
Realiza preguntas generales, la aplicación embeberá el mensaje y recuperará los documentos más semánticamente similares guardados en el Paso 1.

**Request:**
```bash
curl --location 'http://localhost:8082/api/chat' \
--header 'Content-Type: application/json' \
--data '{
    "message": "¿que es Programacion orientada a objetos?",
    "conversationId": "sesion-prueba"
}'
```

### Paso 3: Disparar la Indexación manual de Normativas SBS (SbsRagController)
*Nota:* El mecanismo de **File Watcher** lo hace atomáticamente al crear o mover un archivo a la carpeta `fedora-docs`. Sin embargo, puedes triggear manualmente la indexación de los PDFs de los resources:

**Request:**
```bash
curl --location --request POST 'http://[IP_ADDRESS]:8082/api/sbs/index'
```

### Paso 4: Preguntar al asistente de normativas SBS (SbsRagController)
Usamos el endpoint diseñado estrictamente para devolver contexto de la Superintendencia de Banca de los PDFs procesados en el Paso 3.

**Request:**
```bash
curl --location 'http://[IP_ADDRESS]:8082/api/sbs/ask?question=%C2%BFque%20dice%20la%20norma%20ISO%2027001%3F'
```

**Respuesta Exitosa Esperada (Output Guardrail JSON):**
```json
{
  "respuesta": "La norma ISO/IEC 27001 detalla los requisitos para establecer, implementar, mantener y mejorar continuamente un SGSI.",
  "referencia_legal": "Norma ISO 27001"
}
```

### Paso 5: Probar el Sistema de Evaluación Automática (Hybrid Scoring)

Este módulo expone un endpoint enfocado en medir qué tan correcta fue la respuesta de nuestro asistente frente a una respuesta "Ground Truth" esperada.

### ⚡ Prueba 1: Fast Path (Similitud Lexical Alta)
Esta prueba no utiliza el LLM y se ejecuta en **< 5 milisegundos**, devolviendo un *score* de 100 automático por solapamiento de texto.

**Request:**
```bash
curl --location 'http://localhost:8082/api/evaluate/scoring' \
--header 'Content-Type: application/json' \
--data '{
    "generatedAnswer": "La SBS es la superintendencia de banca",
    "expectedAnswer": "la superintendencia de banca es la sbs"
}'
```

**Respuesta Esperada:**
```json
{
  "score": 100.0,
  "methodUsed": "SIMPLE",
  "reasoning": "Fast lexical similarity matched with high confidence."
}
```

### 🧠 Prueba 2: Slow Path (Fallback al LLM - Cross-Encoder Semántico)
Esta prueba falla el motor numérico rápido (por diferencias léxicas extremas) y dispara el LLM interno para juzgarlas, con un tiempo promedio de **~500ms**, validando con éxito que las frases sigan significando lo mismo en el fondo.

**Request:**
```bash
curl --location 'http://localhost:8082/api/evaluate/scoring' \
--header 'Content-Type: application/json' \
--data '{
    "generatedAnswer": "El organismo fiscalizador del sistema financiero y de seguros peruano.",
    "expectedAnswer": "La Superintendencia encargada de supervisar los bancos y sus afiliadas en el Perú."
}'
```

**Respuesta Esperada:**
```json
{
  "score": 90.0,
  "methodUsed": "LLM",
  "reasoning": "Ambas respuestas describen el mismo rol normativo e institucional."
}
```

---

### Paso 6: Monitoreo en Tiempo Real (Actuator + Micrometer)

El sistema integra telemetría en vivo para auditar el desempeño y tráfico del evaluador (cuántas llamadas salieron gratis y cuántas requirieron coste de LLM).

> ⚠️ **Nota Importante sobre Error 404 (Not Found):**  
> Las métricas de código (`rag.scoring.*`) se inicializan de forma "Perezosa" (Lazy) en la RAM. **Si tu petición a Actuator te devuelve un Error HTTP 404, significa que aún no has evaluado ninguna respuesta desde que encendiste el servidor.** Para solucionarlo, simplemente ejecuta primero una validación RAG del **Paso 5** para que el contador nazca internamente, y luego vuelve a consultar Actuator.

**Request (Consultar Tráfico total diferenciado por tags):**
```bash
curl 'http://localhost:8082/actuator/metrics/rag.scoring.validations'
```

**Request (Filtrar Tráfico exclusivamente procesado por LLM Fallback):**
```bash
curl 'http://localhost:8082/actuator/metrics/rag.scoring.validations?tag=method:LLM'
```

**Request (Consultar Latencia y Tiempos de Ejecución):**
```bash
curl 'http://localhost:8082/actuator/metrics/rag.scoring.latency'
```
