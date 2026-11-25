# Control Remoto (Android) — por wifi

Controla otro teléfono Android desde el tuyo **por wifi**. La misma app va en los dos teléfonos:
- **Teléfono controlado:** pantalla "Este teléfono" → activar el servicio en Accesibilidad. Muestra un PIN.
- **Teléfono que controla:** la lista "Dispositivos" encuentra solo al otro teléfono en la wifi (o se agrega por IP),
  pide el PIN la primera vez y abre el control.

## Qué controla
La cruceta y OK funcionan como un control de TV (mueven el foco en las apps). En Android 13 o superior en el teléfono
controlado usan las teclas de dirección reales del sistema; en versiones anteriores se mueve el foco por accesibilidad.
El ícono ⋯ abre "Más botones", el teclado numérico y el teclado de texto con flechas, espacio, borrar e intro,
y el ícono del mouse abre el panel táctil con franja de desplazamiento.

Inicio, atrás, recientes, notificaciones, bloquear pantalla, volumen y mute, reproducir/pausar/adelantar/retroceder,
brillo (con permiso), escribir texto y números, cursor táctil (mover, clic, mantener pulsado, desplazar) y las flechas
(que deslizan la pantalla). El botón OK hace clic donde está el cursor.

## Requisitos
- Android 9 o superior, ambos teléfonos en la misma wifi.
- En el teléfono controlado: activar el servicio en Accesibilidad. Si Android dice "Configuración restringida",
  abre Info de la app → menú ⋮ → "Permitir ajustes restringidos".
- Para brillo: permitir "Modificar ajustes del sistema" (botón en "Este teléfono").

## Seguridad
El tráfico va por tu wifi sin cifrar, protegido por un PIN de 6 dígitos. Úsalo en redes de confianza.

## Actualizaciones dentro de la app
Cada push a GitHub compila y publica un **Release** con el APK. Al abrir la app, revisa el último Release y, si es más
nuevo, pregunta **"¿Desea descargar esta actualización?"** con **Aceptar** o **Rechazar**.
El repositorio debe ser **público**. Ajustes (engrane) → "Buscar actualización" explica por qué no hay una si falla.
Todos los builds se firman con la misma llave (`app/debug.keystore`) para poder actualizar encima.

## Limitaciones
- Las TVs y otros dispositivos sin esta app no se controlan en esta versión (la TV por wifi sería el siguiente paso).
- Las flechas no mueven el foco como un control de TV: deslizan la pantalla.
- Tocar en pantalla y escribir dependen de lo que permita cada app (algunas bloquean la accesibilidad).

## Compilar
Sube el proyecto a GitHub: *Actions* compila y el APK queda en *Releases*. O ábrelo con Android Studio.
