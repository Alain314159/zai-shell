# ⚡ Z.ai Shell

> Native Android shell for [chat.z.ai](https://chat.z.ai) — anti-freeze, foreground service, DOM cleanup, wake lock, and more.

[![Build APK](https://github.com/Alain314159/zai-shell/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Alain314159/zai-shell/actions/workflows/build-apk.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Capacitor](https://img.shields.io/badge/Capacitor-8.5.3-blue.svg)](https://capacitorjs.com/)

`chat.z.ai` no tiene app nativa oficial para Android. Usarlo en el navegador móvil sufre de congelamientos, tab freezing, Doze mode matando la conexión SSE, y peso del DOM que dispara el garbage collector cada pocos segundos.

**Z.ai Shell** resuelve todo eso envolviendo la web en una APK nativa con Capacitor 8, dándole superpoderes que el navegador no tiene:

- 🛡️ **Foreground Service** — notificación persistente que evita que Android mate la app
- ⚡ **Wake Lock nativo** — la CPU no duerme mientras esperas respuesta del modelo
- 🔋 **Battery Optimization Bypass** — excluye la app del Doze mode
- 🧹 **DOM Cleanup automático** — elimina mensajes antiguos del DOM cuando hay >50 (libera RAM)
- 💓 **Keep-Alive ping** — evita que el WebView se considere "idle" y se congele
- 📳 **Notificaciones nativas** — vibración cuando el modelo termina de responder
- 🎨 **CSS optimizado para móvil** — oculta paneles pesados, safe-area para notch
- 🔄 **Network resilience** — auto-recarga cuando vuelve la conexión
- ⚙️ **FAB panel de control** — botón flotante ⚡ con stats en vivo y acciones

---

## 📥 Instalación

### Opción A: Descargar APK precompilado (recomendado)

1. Ve a [**Actions → Build APK**](https://github.com/Alain314159/zai-shell/actions/workflows/build-apk.yml)
2. Click en la última ejecución exitosa ✅
3. Descarga el artifact `zai-shell-debug` (o `zai-shell-release`)
4. Descomprime el ZIP, obtendrás un archivo `app-debug.apk`
5. Transfiérelo a tu Android y permite instalar desde orígenes desconocidos
6. Instala y abre. Te pedirá permisos de batería y notificaciones.

### Opción B: Compilar tú mismo

```bash
git clone https://github.com/Alain314159/zai-shell.git
cd zai-shell
npm install
npm run build
npx cap sync android
cd android
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

Necesitas: Node 20+, JDK 17, Android SDK 35.

---

## 🚀 Uso

1. **Primera apertura**: splash screen → carga chat.z.ai → te pide permisos
2. **Permiso de batería**: cuando aparezca el FAB ⚡, ábrelo → "Solicitar" sin restricción → autoriza
3. **Wake lock**: el botón ⚡ → "Activar" (activa el wake lock del plugin comunitario)
4. **Chatea normalmente**: la app se comporta como la web, pero sin congelamientos
5. **Cuando la app pasa a background**: la notificación persistente la mantiene viva
6. **Cuando el modelo termina de responder**: vibración corta de feedback

### El FAB ⚡

Botón flotante abajo a la derecha que abre un panel con:

| Acción | Función |
|--------|---------|
| Mensajes en DOM | Estadística en vivo (cuántos nodos de mensaje hay) |
| Ocultos (RAM) | Cuántos mensajes antiguos ha limpiado el auto-cleanup |
| Batería sin restricción | Pide el permiso especial de Android |
| Limpiar ahora | Fuerza el cleanup de mensajes antiguos |
| Forzar recarga | `location.reload()` |
| Wake lock | Activa el wake lock del plugin `@capacitor-community/keep-awake` |

---

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│  APK nativa (com.zai.shell)                                 │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  MainActivity (BridgeActivity)                        │  │
│  │  ├─ registra ZaiShellPlugin                           │  │
│  │  ├─ arranca ZaiForegroundService                      │  │
│  │  └─ hookea ZaiWebViewClient en el WebView             │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│                          ▼                                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  WebView (carga https://chat.z.ai)                    │  │
│  │  └─ onPageFinished → inyecta zai-shell.js             │  │
│  │     ├─ CSS optimizado (safe-area, sin paneles pesados)│  │
│  │     ├─ Keep-alive ping cada 30s                       │  │
│  │     ├─ DOM cleanup cada 60s (max 50 msgs)             │  │
│  │     ├─ Network resilience (auto-reload on online)     │  │
│  │     ├─ Watch response state (vibra al terminar)       │  │
│  │     └─ FAB ⚡ con panel de control                     │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│                          ▼                                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  ZaiForegroundService (notificación persistente)      │  │
│  │  ├─ START_STICKY (Android lo reinicia si lo mata)     │  │
│  │  ├─ WakeLock PARTIAL_WAKE_LOCK (CPU activa)           │  │
│  │  └─ foregroundServiceType=specialUse                  │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│                          ▼                                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Plugins Capacitor activos                            │  │
│  │  ├─ @capacitor-community/keep-awake (wake lock extra) │  │
│  │  ├─ @capacitor/local-notifications (vibración)        │  │
│  │  ├─ @capacitor/network (detección reconexión)         │  │
│  │  ├─ @capacitor/status-bar (color dark)                │  │
│  │  ├─ @capacitor/preferences (settings persistentes)    │  │
│  │  ├─ @capacitor/haptics (vibración feedback)           │  │
│  │  └─ @capacitor/splash-screen (splash 1.5s)            │  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 📁 Estructura del proyecto

```
zai-shell/
├── .github/workflows/
│   └── build-apk.yml              # CI: compila APK en cada push
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── assets/
│   │   │   │   └── capacitor.config.json
│   │   │   ├── java/com/zai/shell/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   └── plugin/
│   │   │   │       ├── ZaiShellPlugin.kt        # JS/CSS injection + battery opt
│   │   │   │       ├── ZaiForegroundService.kt  # Notificación persistente
│   │   │   │       └── ZaiWebViewClient.kt      # Hook onPageFinished
│   │   │   ├── res/
│   │   │   │   ├── values/strings.xml
│   │   │   │   ├── values/styles.xml
│   │   │   │   └── xml/network_security_config.xml
│   │   │   └── AndroidManifest.xml
│   │   ├── build.gradle
│   │   ├── capacitor.build.gradle
│   │   └── proguard-rules.pro
│   ├── build.gradle
│   ├── capacitor.settings.gradle
│   ├── gradle.properties
│   └── gradle/wrapper/gradle-wrapper.properties
├── src/
│   ├── css/
│   ├── js/
│   │   └── zai-shell.js           # Script principal que se inyecta
│   ├── index.html                 # HTML mínimo (solo loader)
│   └── main.ts                    # Bootstrap Capacitor
├── capacitor.config.ts            # Configuración Capacitor
├── package.json
├── tsconfig.json
├── vite.config.ts
├── LICENSE
└── README.md
```

---

## 🔧 Cómo funciona el anti-congelamiento

### El problema
1. Chrome Android congela pestañas en background después de 5 minutos
2. Android Doze mode mata conexiones de red de apps en background
3. z.ai tiene un DOM pesado (~200 nodos por mensaje) que dispara el GC

### La solución (3 capas)

**Capa 1: Foreground Service (Kotlin)**
- Arranca en `MainActivity.onCreate()`
- Muestra notificación persistente ("Z.ai Shell - Activo")
- Pide `START_STICKY` → Android lo reinicia si lo mata
- Adquiere `PARTIAL_WAKE_LOCK` → CPU no duerme
- Tipo `foregroundServiceType="specialUse"` (Android 14+)

**Capa 2: Battery Optimization Bypass (Plugin nativo)**
- `ZaiShellPlugin.requestIgnoreBatteryOptimization()` lanza `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- El usuario autoriza → la app se excluye del Doze
- Verificable con `isBatteryOptimized()`

**Capa 3: DOM Cleanup + Keep-Alive (JS inyectado)**
- Keep-alive: `fetch('/favicon.ico', {mode:'no-cors'})` cada 30s
- DOM cleanup: cada 60s, si hay >50 mensajes, los antiguos se ocultan (con placeholder para preservar scroll)
- CSS: `content-visibility:auto` en mensajes → el navegador no pinta lo que no se ve

---

## 🛠️ Desarrollo

### Requisitos
- Node.js 20+
- JDK 17 (Temurin recomendado)
- Android SDK 35 (Android Studio o command-line tools)

### Comandos

```bash
npm install              # Instalar dependencias
npm run dev             # Servidor de desarrollo (no relevante para prod)
npm run build           # Build web assets (vite)
npm run cap:sync        # Sync a android
npm run cap:open        # Abrir en Android Studio
npm run apk:debug       # Build APK debug
npm run apk:release     # Build APK release
```

### Modificar el JS inyectado

Edita `src/js/zai-shell.js`. Después:
```bash
npm run build
npx cap sync android
cd android && ./gradlew installDebug
```

### Modificar la UI nativa

- **MainActivity**: `android/app/src/main/java/com/zai/shell/MainActivity.kt`
- **Foreground Service**: `android/app/src/main/java/com/zai/shell/plugin/ZaiForegroundService.kt`
- **Plugin JS/CSS injection**: `android/app/src/main/java/com/zai/shell/plugin/ZaiShellPlugin.kt`
- **WebViewClient hook**: `android/app/src/main/java/com/zai/shell/plugin/ZaiWebViewClient.kt`

---

## 🔄 CI/CD

El workflow de GitHub Actions (`.github/workflows/build-apk.yml`) se ejecuta en:
- **Push a `main`**: build debug + release APK, sube artifacts
- **Tags `v*`**: build + crea GitHub Release con el APK firmado
- **PR**: build debug para verificar que no se rompe nada
- **Manual**: `workflow_dispatch` desde la tab Actions

### Secrets opcionales para firma release

Si quieres APK release firmados con tu propia key (no la debug autogenerada), añade estos secrets al repo:

| Secret | Descripción |
|--------|-------------|
| `SIGNING_KEY_BASE64` | Keystore base64-encoded (`base64 < release.keystore`) |
| `SIGNING_STORE_PASSWORD` | Password del keystore |
| `SIGNING_KEY_PASSWORD` | Password de la key |
| `SIGNING_KEY_ALIAS` | Alias de la key |

Si no los añades, el workflow genera una key temporal para firmar el release APK (no es ideal para producción, pero funciona para uso personal).

---

## ⚠️ Limitaciones conocidas

1. **Captcha de slider de z.ai**: la primera vez que abras la app, z.ai puede mostrar un captcha de slider. Tienes que pasarlo manualmente. Es anti-bot de z.ai, no podemos bypassarlo.
2. **Detección de WebView**: algunas webs detectan que están en WebView y cambian el comportamiento. z.ai no parece hacerlo, pero puede cambiar.
3. **Login**: tu sesión de chat.z.ai se mantiene en el WebView. Si cierras sesión en la web, también la cierras en la app.
4. **El peso del DOM es del propio z.ai**: nosotros lo mitigamos con cleanup, pero la causa raíz es la implementación React/Tailwind de z.ai.

---

## 📜 Licencia

MIT © [Alain314159](https://github.com/Alain314159)

## 🙏 Créditos

- [Capacitor](https://capacitorjs.com/) por el runtime nativo
- [z.ai / Zhipu AI](https://z.ai) por GLM y el chat web
- Comunidad de plugins Capacitor
