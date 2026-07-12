# DreamWeb

DreamWeb es un navegador web optimizado para **Android TV**, diseñado para ofrecer una experiencia de navegación fluida, rápida y segura en pantallas grandes.

## 🚀 Características

- **Optimizado para Android TV:** Interfaz diseñada específicamente para navegación con control remoto (D-Pad) y orientación horizontal (landscape).
- **AdBlock Inteligente:** Bloqueador de anuncios nativo que utiliza **Bloom Filters** para una detección ultra-rápida con un consumo mínimo de memoria.
- **Pre-carga de WebView:** Implementación de un pool de WebViews (`WebViewPool`) para una carga de páginas casi instantánea mediante el reciclaje de instancias.
- **Motor de Privacidad:** Gestión centralizada de políticas de privacidad (`PrivacyEngine`) para mitigar el rastreo y mejorar la seguridad.
- **Monitor de Rendimiento:** Sistema interno para supervisar la fluidez de la interfaz y el consumo de recursos en hardware de TV.
- **Navegación Persistente:** Gestión de historial y marcadores optimizada con almacenamiento local.

## 🛠️ Tecnologías y Arquitectura

- **Kotlin:** Lenguaje principal enfocado en seguridad de tipos y concisión.
- **Android SDK:** Uso intensivo de componentes Leanback para la mejor experiencia en TV.
- **Inyección de Dependencias Manual:** Uso de `AppContainer` para una gestión eficiente de recursos sin la sobrecarga de frameworks externos.
- **View Binding:** Interacción segura y directa con los elementos de la interfaz de usuario.
- **Arquitectura Limpia:** Organización por módulos de responsabilidad:
  - `adblock/`: Lógica probabilística de filtrado.
  - `engine/`: Core de renderizado y gestión de WebViews.
  - `managers/`: Controladores de navegación, base de datos y eventos de entrada.
  - `metrics/`: Telemetría de rendimiento.

## 📦 Estructura Detallada

- `com.example.dreamweb.BrowserApp`: Punto de entrada que inicializa el contenedor de dependencias y la pre-carga.
- `com.example.dreamweb.ui`:
    - `HomeActivity`: Pantalla de inicio con accesos rápidos.
    - `BrowserActivity`: Experiencia de navegación inmersiva.
- `com.example.dreamweb.managers.DpadHandler`: Lógica personalizada para manejar la navegación por control remoto en páginas web complejas.
- `com.example.dreamweb.adblock.BloomFilter`: Estructura de datos eficiente para el filtrado de dominios publicitarios.

## 🛠️ Instalación y Uso

1. Clona el repositorio:
   ```bash
   git clone https://github.com/tu-usuario/DreamWeb.git
   ```
2. Abre el proyecto en **Android Studio**.
3. Requisitos de SDK: **Android SDK 36** o superior.
4. Configura un emulador de **Android TV** o un dispositivo físico con Android 9.0 (API 28) o superior.
5. Ejecuta la aplicación.

---
Desarrollado con ❤️ para mejorar la experiencia web en la televisión.
