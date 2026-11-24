# Control Remoto (Android)

App para Android que controla por wifi **TVs Android / Google TV** y **otros teléfonos Android**.
Se instala solo en tu celular; no hay que instalar nada en la TV ni en el teléfono controlado.
Botones al estilo de un control moderno: reproducción, volumen, mute, subtítulos, brillo,
teclado numérico, atrás, inicio, menú, encendido y D-pad con OK. Incluye teclado para escribir texto.

## Cómo funciona
Envía las teclas con **ADB sobre wifi** (librería [dadb](https://github.com/mobile-dev-inc/dadb)).
Todos los botones usan códigos `KeyEvent` de Android (ver `Key` en `RemoteScreen.kt`).

## Preparar el dispositivo a controlar
**TV Android / Google TV**
1. Ajustes → Acerca de → toca varias veces "Compilación" para activar Opciones de desarrollador.
2. Opciones de desarrollador → activa **Depuración USB / ADB** y, si existe, **Depuración por red**.
3. Anota la IP de la TV (Ajustes → Red). El puerto suele ser `5555`.

**Teléfono Android**
1. Activa Opciones de desarrollador y Depuración USB.
2. Una sola vez, desde una PC con USB: `adb tcpip 5555` (queda activo hasta reiniciar el teléfono).
3. Anota su IP wifi.

La primera vez, el dispositivo mostrará "¿Permitir depuración?". Acepta (marca "Permitir siempre").

## Limitaciones (versión 1.0)
- **No** implementa el emparejamiento con código de "Depuración inalámbrica" (Android 11+),
  ni el protocolo Android TV Remote. Son los siguientes pasos.
- No hay descubrimiento automático: se agrega la IP a mano.
- El botón de brillo y algunas teclas dependen de que el dispositivo las acepte.
- No funciona con iPhone ni con TVs que no sean Android (Samsung, LG, Roku usan otros protocolos).

## Compilar
**Sin Android Studio:** sube el proyecto a GitHub. La pestaña *Actions* compila el APK
(`Build APK`) y lo deja como artefacto descargable `control-remoto-debug`.

**Con Android Studio:** abre la carpeta y ejecuta `app`.

## Subir a GitHub
```bash
git init
git add .
git commit -m "Control remoto Android"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/control-remoto.git
git push -u origin main
```
