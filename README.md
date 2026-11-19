# QuizAI para Android — GPT-5.5

QuizAI consulta la API de OpenAI directamente desde el teléfono por HTTPS. Usa `gpt-5.5` de forma predeterminada para analizar texto y fotografías mediante la API Responses y respuestas JSON estructuradas.

## Instalar y usar

1. Compila o instala `app/build/outputs/apk/debug/app-debug.apk`.
2. Abre QuizAI e introduce tu clave personal de API de OpenAI en el campo de clave.
3. El modelo predeterminado es `gpt-5.5`.
4. Copia una pregunta, pulsa «Leer texto copiado» y luego «Analizar con GPT-5.5».

## Burbuja flotante y cámara

Activa la burbuja y concede el permiso de superposición. Dos toques abren sus opciones: CopyText, Cam, Ocultar, Portapapeles y Respuesta. En CopyText, QuizAI analiza el texto que copiaste. En Cam, mantén la burbuja presionada para encuadrar y suéltala para capturar y analizar una foto.

En fotografías se analiza la primera pregunta completa visible. La respuesta incluye la alternativa, una breve explicación y el nivel de confianza. Revisa siempre las respuestas generadas por IA.

## Clave y facturación

QuizAI guarda la clave cifrada con AES-GCM y Android Keystore. Puedes eliminarla desde «Borrar clave guardada». El cifrado local no protege frente a un teléfono comprometido. No incluyas claves en el código fuente ni en un APK distribuido.

La API requiere una clave de OpenAI Platform. El uso de la API se factura por separado de la suscripción de ChatGPT; consulta la [guía de facturación de OpenAI](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform).

## Compilación

Compila con Android Studio, Android SDK Platform 35 y JDK 17 o posterior:

```powershell
.\gradlew.bat :app:assembleDebug
```

GPT-5.5 admite imágenes y salidas estructuradas en la [API Responses](https://developers.openai.com/api/docs/models/gpt-5.5).
