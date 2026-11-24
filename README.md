# Control Remoto (Android)

Control remoto por **Bluetooth** para TVs, teléfonos y otros dispositivos. El celular se hace pasar por un
teclado + mouse + control multimedia Bluetooth (perfil HID), así que **no hay que activar nada en el
dispositivo controlado**: solo emparejarlo por Bluetooth una vez.

Pantallas:
- **Dispositivos:** lista de dispositivos Bluetooth emparejados (etiqueta "Auto" en el último usado).
- **Control:** reproducción, volumen, mute, subtítulos, brillo, teclado numérico, atrás, inicio, menú,
  encendido y cruceta con OK. Arriba: panel táctil (mouse), teclado para escribir y menú.

## Uso
1. Toca el ícono de Bluetooth y empareja tu TV o teléfono con este celular.
2. Vuelve a la app y toca el dispositivo en la lista.
3. Si no responde, en el otro dispositivo busca este celular en Bluetooth y empareja de nuevo.
   Cierra otras apps de control remoto Bluetooth (solo una puede usar el Bluetooth HID a la vez).

## Requisitos
- Android 9 o superior en el celular que hace de control. Algunos fabricantes no incluyen el perfil HID Device.
- Permiso de Bluetooth ("Dispositivos cercanos") la primera vez.

## Actualizaciones dentro de la app
Cada vez que subes cambios a GitHub, el flujo **Build APK** compila y publica un **Release** con el APK.
Al abrir la app, ella revisa el último Release; si es más nuevo pregunta
**"¿Desea descargar esta actualización?"** con **Aceptar** o **Rechazar**.
- El repositorio debe ser **público**.
- La primera vez Android pedirá permitir "instalar apps desconocidas" para esta app.
- Todos los builds se firman con la misma llave (`app/debug.keystore`) para poder actualizar encima.
  Es para uso personal, no para Play Store.

## Compilar
Sube el proyecto a GitHub: la pestaña *Actions* compila el APK y el Release queda en *Releases*.
También con Android Studio abriendo esta carpeta.
