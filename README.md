
<img width="384" height="256" alt="logo" src="https://github.com/user-attachments/assets/029b4427-f2fe-4421-8fe2-8998f1bc9312" />



# 📱 AIVA — AI Voice Assistant (Local-First)

**AIVA** es un asistente de voz de alto rendimiento diseñado para operar de manera 100% local, garantizando privacidad absoluta y soberanía de datos. El sistema integra una aplicación móvil nativa en Android con un backend de inferencia de IA desplegado sobre Fedora Linux, optimizado para hardware con VRAM limitada.

| Componente | Tecnología |
| :--- | :--- |
| **Plataforma Móvil** | Android (Kotlin, API 26+) |
| **Arquitectura App** | MVVM + Clean Architecture |
| **Backend OS** | Fedora Linux (Podman) |
| **IA Engine** | Ollama + Qwen2.5:3b (100% GPU) |
| **Gateway** | OpenClaw |
| **Proxy/Seguridad** | Nginx + SSL (Self-signed) |

---

## 🏗️ Arquitectura del Sistema

El flujo de datos garantiza una respuesta fluida mediante el procesamiento local en una GPU **NVIDIA RTX 3050 6GB**:

1. **Captura (STT):** La App utiliza `SpeechRecognitionManager` para capturar la voz del usuario en español (es-PE).
2. **Tránsito:** Se envía una petición HTTP POST compatible con la API de OpenAI al gateway local.
3. **Inferencia:** **OpenClaw** actúa como puente hacia **Ollama**, donde el modelo **Qwen2.5:3b** procesa la consulta íntegramente en la VRAM.
4. **Respuesta (TTS):** El texto generado se devuelve a la App y es reproducido mediante `TtsManager`.

---

## 🛠️ Guía de Instalación (Backend)

### 1. Servidor de Modelos (Ollama)
Levanta el contenedor habilitando el soporte para GPU NVIDIA:
```bash
podman run -d \
  --name ollama-server \
  --device [nvidia.com/gpu=all](https://nvidia.com/gpu=all) \
  -p 11434:11434 \
  -v ollama:/root/.ollama \
  docker.io/ollama/ollama:latest

```

Descarga el modelo optimizado para evitar latencia por CPU
```bash
podman exec ollama-server ollama pull qwen2.5:3b
```

### 2. Gateway e Interfaz (OpenClaw)
Configura OpenClaw para gestionar las peticiones de la App:
```bash
podman run -d --name openclaw --network host \
  -e OLLAMA_API_KEY="ollama-local" \
  -v ~/openclaw_config:/home/node/.openclaw:Z \
  docker.io/alpine/openclaw:main
```
[!IMPORTANT]
Es obligatorio habilitar el endpoint /v1/chat/completions en el archivo openclaw.json para que la App pueda comunicarse correctamente.

---
## 📱 Configuración de la App Android

1. **Permisos:**  Requiere RECORD_AUDIO e INTERNET.

2. **Conectividad:** En AivaHttpClient.kt, configura la IP de tu servidor Fedora:

private val HTTP_URL = "http://192.168.1.X:18789/v1/chat/completions" // Reemplaza con la IP de tu servidor

3. **Seguridad:** La App incluye un X509TrustManager para permitir conexiones con certificados autofirmados en entornos locales.

---
## 🔍 Troubleshooting (Solución de Problemas)
Error 404 en la API: Verifica que el endpoint de Chat Completions esté activo en la configuración de OpenClaw.


Lentitud extrema: Ejecuta podman exec ollama-server ollama ps y confirma que el modelo muestre 100% GPU.

CLEARTEXT communication not permitted: Verifica que android:usesCleartextTraffic="true" esté definido en el AndroidManifest.xml.

Error de voz 7: Problemas de conexión o servicios de Google Speech no disponibles en el smartphone.

---

## 🚀 Futuras Mejoras (Roadmap)

RAG Local: Implementación de base de datos vectorial para consultas sobre documentos personales.

Memoria de Contexto: Optimización del historial de mensajes para mantener conversaciones coherentes a largo plazo.

Wake Word: Activación por voz ("Hey Aiva") sin necesidad de interacción manual.

---
Desarrollado por: David Raymundo Lache Alvarez

Proyecto de Portafolio - Ingeniería de IA y Desarrollo Móvil

---
