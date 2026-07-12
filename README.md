# DreamWeb

DreamWeb es un navegador web optimizado para **Android TV**, diseñado para ofrecer una experiencia de navegación fluida, rápida y segura en pantallas grandes.

## 🚀 Características

- **Optimizado para Android TV:** Interfaz diseñada para navegación con control remoto y orientación landscape.
- **AdBlock Integrado:** Bloqueador de anuncios nativo para una navegación sin interrupciones.
- **Pre-carga de WebView:** Sistema de pool de WebViews para una carga de páginas casi instantánea.
- **Monitor de Rendimiento:** Seguimiento interno para garantizar la fluidez en hardware de TV.
- **Interfaz Fluida:** Pantalla de inicio personalizada y gestión de pestañas optimizada.

## 🛠️ Tecnologías Usadas

- **Kotlin:** Lenguaje principal de desarrollo.
- **Android SDK:** Con enfoque en Leanback (Android TV).
- **WebView:** Motor de renderizado web.
- **Inyección de Dependencias Manual:** Uso de `AppContainer` para una gestión eficiente de recursos.

## 📦 Estructura del Proyecto

- `app/src/main/java/com/example/dreamweb/BrowserApp.kt`: Clase Application que inicializa los componentes principales.
- `ui/`: Actividades y componentes de la interfaz de usuario.
    - `HomeActivity`: Pantalla principal del navegador.
    - `BrowserActivity`: Actividad dedicada a la visualización de contenido web.
- `AdBlockManager`: Gestión del filtrado de contenido publicitario.

## 🛠️ Instalación y Uso

1. Clona el repositorio:
   ```bash
   git clone https://github.com/tu-usuario/DreamWeb.git
   ```
2. Abre el proyecto en **Android Studio**.
3. Asegúrate de tener configurado un emulador de **Android TV** o un dispositivo físico.
4. Ejecuta la aplicación.

---
Desarrollado con ❤️ para mejorar la experiencia web en la televisión.
