<!-- app-release:start -->
[**Descargar APK v20**](https://github.com/ricardoyf/TTSReader/releases/download/v20/TTSReader-v20-app-debug.apk) · [SHA-256](https://github.com/ricardoyf/TTSReader/releases/download/v20/TTSReader-v20-app-debug.apk.sha256)

`7ae32bef4035b3d3f132de534a8c61bf4e8e9894b4751687cb67aebeed825f8a`
<!-- app-release:end -->

# TTSReader v20

App Android nativa en **Kotlin + Jetpack Compose** para leer archivos **.txt, .md y .markdown** en el móvil y escucharlos con el TTS nativo de Android.

## APK

La versión compilada incluida en el repositorio es:

[`TTSReader-v20-app-debug.apk`](./TTSReader-v20-app-debug.apk)

## Qué hace

- Selección de carpeta local con **Storage Access Framework (SAF)**
- Navegación simple por **carpetas, subcarpetas y archivos .txt, .md y .markdown**
- **Lector limpio** con tipografía cuidada
- Renderizado básico de Markdown y tablas
- Lectura en voz alta con el **TTS nativo de Android** en español
- Resaltado sincronizado de la palabra pronunciada y desplazamiento automático
- Pausa y reanudación desde la posición exacta
- Reproducción continua opcional del siguiente archivo
- Controles compactos de velocidad para maximizar el área de lectura
- **Swipe horizontal** izquierda/derecha para ir al archivo siguiente/anterior
- Guarda la **última carpeta**, el **último archivo**, la posición de lectura y el **tamaño de letra** con **DataStore**
- Importación y exportación del estado mediante JSON
- Todo funciona **en local**, sin red, sin backend y sin permisos peligrosos clásicos de almacenamiento

## Arquitectura

```text
com.ricardo.txtreader/  # paquete interno heredado de TXT Reader
├── data/
│   ├── PreferencesRepository.kt
│   └── TxtRepository.kt
├── model/
│   └── ReaderModels.kt
├── navigation/
│   ├── Destinations.kt
│   └── TxtReaderApp.kt
├── ui/
│   ├── components/
│   │   └── EmptyStateCard.kt
│   ├── screens/
│   │   ├── LibraryScreen.kt
│   │   └── ReaderScreen.kt
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   └── viewmodel/
│       ├── ReaderUiState.kt
│       ├── ReaderViewModel.kt
│       └── ReaderViewModelFactory.kt
└── MainActivity.kt
```

## Cómo abrir en Android Studio

1. Instala Android Studio Jellyfish o superior.
2. Ten disponible JDK 17 y Android SDK 34.
3. Abre la carpeta del repositorio como proyecto.
4. Espera la sincronización de Gradle.
5. Ejecuta en emulador o dispositivo físico.

## Compilar APK

Cuando el entorno tenga toolchain Android real:

```bash
./gradlew assembleDebug
```

APK esperado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 17 y Android SDK 34.
