# Backend de QuizAI

Este servidor recibe el texto de la pregunta desde Android y consulta la API de OpenAI.
La clave de OpenAI permanece en el servidor, nunca dentro del APK.

## Requisitos
- Python 3.10 o superior.
- Una clave de API creada en https://platform.openai.com/api-keys.
- Facturación/créditos de API configurados, si la organización los requiere.

## Ejecutar localmente en Windows (PowerShell)

```powershell
cd backend
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
notepad .env
```

Completa en `.env`:
- `OPENAI_API_KEY`: tu clave privada de OpenAI.
- `OPENAI_MODEL`: por defecto `gpt-5.5`; puedes cambiarlo si tu proyecto no tiene acceso a ese modelo.
- `QUIZAI_ACCESS_TOKEN`: crea una contraseña larga y aleatoria. La misma se introduce en la app Android.

Luego ejecuta:

```powershell
uvicorn main:app --host 0.0.0.0 --port 8000
```

Prueba desde el PC:
- `http://127.0.0.1:8000/health`

## Conectar el POCO X3 Pro a un servidor local

1. PC y teléfono deben estar conectados a la misma red Wi-Fi.
2. Averigua la IPv4 del PC con `ipconfig`.
3. En QuizAI, escribe `http://IP_DEL_PC:8000` en URL del servidor.
4. Escribe el mismo `QUIZAI_ACCESS_TOKEN` configurado en `.env`.
5. Permite QuizAI en el firewall de Windows para la red privada.

La conexión HTTP local es solo para pruebas en una red de confianza. Para usarlo fuera de casa o en producción, despliega el backend detrás de HTTPS y desactiva el tráfico HTTP sin cifrar en Android.

## Endpoints
- `GET /health`
- `POST /analyze` con JSON `{"text":"..."}` y cabecera `X-QuizAI-Token`.

No compartas el archivo `.env`, tu clave API ni el token.
