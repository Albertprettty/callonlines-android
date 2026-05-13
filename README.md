# CallOnLines Android (WebView wrapper)

App Android nativa (Kotlin) que envuelve el panel `https://callonlines.com/login.php`.

## Características

- WebView con JS, cookies, DOM storage y soporte para descargas.
- Permisos de **micrófono y cámara** para WebRTC (si tu panel lo usa).
- Selector de archivos para subir comprobantes.
- Pull-to-refresh.
- Botón atrás de Android = atrás del WebView.
- Links externos (que no sean `callonlines.com`) se abren en el navegador del teléfono.
- Splash screen + icono adaptativo con la identidad CallOnLines (cyan/púrpura).
- Modo oscuro automático.

## Build automático (GitHub Actions)

Al hacer push a `main` o `master`, GitHub compila la APK automáticamente.

Para descargar la APK:

1. Entra a tu repo en GitHub.
2. Click en la pestaña **Actions**.
3. Click en el último workflow exitoso ("Build CallOnLines APK").
4. Abajo en **Artifacts** descarga `CallOnLines-APK`.
5. Descomprime el zip → `CallOnLines-debug.apk`.

## Instalación en el teléfono

1. Pasa el `.apk` al Android (Drive, Telegram, USB, AirDrop…).
2. Abre el archivo desde el gestor de archivos.
3. Android te pedirá permiso para "Instalar aplicaciones desconocidas" — actívalo para ese gestor.
4. Pulsa Instalar.

## Configuración rápida

- Cambiar URL de inicio: `app/src/main/java/com/callonlines/app/MainActivity.kt` → `START_URL`.
- Cambiar hosts permitidos: misma clase → `ALLOWED_HOSTS`.
- Cambiar nombre de la app: `app/src/main/res/values/strings.xml`.
- Cambiar colores: `app/src/main/res/values/colors.xml`.
- Cambiar icono: reemplaza `ic_launcher_foreground.xml` y `ic_launcher_background.xml`.

## Build local (opcional)

Requiere JDK 17 y Android SDK con `platform-34`, `build-tools 34.0.0`.

```bash
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
```

APK resultante: `app/build/outputs/apk/debug/app-debug.apk`.

## Versión release firmada

La APK del workflow es **debug**. Para publicar en Google Play necesitas:

1. Generar un keystore: `keytool -genkey -v -keystore release.keystore -alias callonlines -keyalg RSA -keysize 2048 -validity 10000`.
2. Subirlo como secret en GitHub.
3. Añadir la firma al `app/build.gradle.kts` en `signingConfigs`.
4. Cambiar el job a `assembleRelease`.

Si necesitas eso, dímelo y te lo armo.
