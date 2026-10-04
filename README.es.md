# BLERemoteVESC — Acelerador Bluetooth para patos de pesca con VESC

[English](README.md) · **Español**

> 🧪 **Beta.** Funciona en el agua, pero es software joven que mueve un motor. Pruébala primero en tierra, ten a mano el corte del motor y, si encuentras un fallo, [abre una incidencia](https://github.com/Danii204/BLERemoteVESC/issues) contando qué estabas haciendo.

App Android que convierte el móvil en el acelerador de un motor eléctrico con controlador basado en [VESC](https://vesc-project.com/), conectado por Bluetooth Low Energy. Está pensada para un **pato de pesca** con motor inrunner: un acelerador vertical grande que se maneja sin mirar, tres modos de potencia, lecturas en directo de batería, potencia y velocidad, y varias capas de protección contra acelerones sin querer.

<p align="center">
  <img src="docs/screenshot-main.png" width="260" alt="Pantalla principal">
  <img src="docs/screenshot-menu.png" width="260" alt="Menú de ajustes">
  <img src="docs/screenshot-throttle-settings.png" width="260" alt="Ajustes del acelerador">
</p>

## Funciones

- **Acelerador vertical con enclavamiento central.** Arriba avante, abajo marcha atrás. Solo se mueve agarrando el tirador, cuesta sacarlo del centro y vuelve a parado con una vibración.
- **Control por par.** La app manda corriente al motor (`COMM_SET_CURRENT`), no duty: el arranque es suave y la respuesta en el agua, progresiva.
- **Modos de potencia** Eco, Crucero y Sport, cada uno con su tope de corriente y su suavizado.
- **Datos en directo del VESC**: porcentaje y tensión de batería, autonomía estimada al consumo actual, potencia que sale de la batería (W) y corriente de batería.
- **Velocidad por el GPS del móvil**, filtrada para mostrar cifra solo cuando la precisión es suficiente.
- **Rumbo por GPS** (rumbo sobre el fondo) con una rosa pequeña. No usa la brújula del móvil: en un pato se va sentado de espaldas y la brújula marcaría lo contrario. Funciona a partir de 2 km/h; parado muestra el último rumbo atenuado.
- **Pitido al conectar**: el propio motor da dos tonos cortos al establecer el enlace.
- **Temas claro, oscuro y AMOLED** (Material 3). El AMOLED usa negro puro y ahorra batería en pantallas OLED.
- **Bajo consumo**: GPS solo con la app delante, pantalla encendida solo mientras hay conexión, atenuado opcional tras 30 s sin tocar y tráfico Bluetooth adaptativo.
- **Actualizaciones desde GitHub Releases**, verificadas con SHA-256 e instaladas con un toque.

## Hardware

Es el montaje con el que se ha desarrollado y probado. Cualquier controlador basado en VESC con módulo BLE UART (Nordic UART Service) debería funcionar; los ajustes de motor y batería de abajo son propios de este hardware.

| Pieza | Modelo |
| --- | --- |
| Controlador | Flipsky FSESC 75100 Pro, carcasa de aluminio, con su módulo BLE (se anuncia como `VESC BLE UART`) |
| Motor | Flipsky 65121, inrunner de 130 KV, sin sensores, estanco |
| Batería | Li-ion NMC 10S8P (celdas 21700), 40 Ah, con BMS inteligente Daly |
| Móvil | Android 8.0 o superior con Bluetooth LE |

## Instalación

1. Descarga `BLERemoteVESC.apk` de la última [versión publicada](https://github.com/Danii204/BLERemoteVESC/releases). Cada versión publica también el SHA-256 del archivo.
2. Ábrelo. La primera vez, Android pedirá permitir instalar apps desde el navegador o el gestor de archivos.
3. Abre **BLERemoteVESC** y acepta los permisos de Bluetooth y ubicación (la ubicación es para la velocidad GPS y, hasta Android 11, para buscar dispositivos Bluetooth).
4. Pulsa **☰ → Conectar**. La app encuentra el controlador por su servicio Bluetooth, sin emparejar.

> No intentes emparejar el controlador desde los ajustes de Bluetooth del móvil o del ordenador: el módulo BLE no admite emparejamiento y el sistema dirá que «no se puede conectar». Es normal; la app se conecta directamente.

## Uso

| Acción | Cómo |
| --- | --- |
| Conectar / desconectar | ☰ → **Conectar** |
| Acelerar avante / atrás | Agarra el tirador y arrástralo arriba / abajo más allá del enclavamiento |
| Volver a parado | Lleva el tirador al centro; se enclava con una vibración |
| Parar | **Parar motor** (inmediato, y devuelve el acelerador al centro) |
| Cambiar de modo | **Eco · Crucero · Sport** arriba, también en marcha |
| Ajustes | ☰ (arriba a la izquierda); las secciones se despliegan al tocarlas |

El acelerador aparece en gris y no se mueve hasta que la app está conectada al controlador.

### Modos de potencia

| Modo | Tope de corriente | Suavizado de 0 a 100 % |
| --- | --- | --- |
| Eco | 6 A | 15 s |
| Crucero | 12 A | 10 s |
| Sport | 25 A | 5 s |

Los dos valores se cambian para cada modo en ☰ → **Acelerador**. Al pasar a un modo con menos tope el empuje baja al momento; a uno con más, sube con el suavizado.

## Seguridad

La app está pensada para que un toque sin querer no pueda arrancar el motor, y para que cualquier fallo acabe con el motor parado:

- **Acelerador bloqueado** hasta conectar con el controlador.
- **Se mueve agarrando.** Tocar el canal fuera del tirador no hace nada.
- **Enclavamiento duro.** Salir del centro exige arrastrar más del 20 % del recorrido; cruzar de avante a atrás de un solo gesto se queda en parado.
- **Suavizado de aceleración** (de 5 a 15 s según el modo) aunque se lleve el tirador al tope de golpe. **Soltar es siempre inmediato.**
- **Pérdida de enlace.** La app renueva la orden de 4 a 10 veces por segundo; el VESC para el motor por su cuenta si pasa 1 s sin órdenes (móvil apagado, fuera de alcance, app colgada).
- **Segundo plano.** Por defecto el motor se para cuando la app sale de la pantalla.

> ⚠️ Es un proyecto personal, no un producto náutico certificado. Lo usas bajo tu responsabilidad. Pruébalo en tierra y sin hélice antes de ir al agua, no confíes en la app como única forma de parar el motor y lleva chaleco salvavidas.

## Configuración del VESC

Son los ajustes usados con el hardware de arriba. Tómalos como referencia; tu montaje puede necesitar otros valores.

### ⚠️ FSESC 75100 / 75200: desactiva antes los filtros de fase

Flipsky vende estos controladores con el firmware `75_300_R2`, que declara unos filtros de fase que la placa **no tiene**. Con firmware 5.3 o superior, pon

`Motor Settings → FOC → Filters → Enable Phase Filters = False`

y escribe la configuración **antes de la detección del motor**. Detectar con los filtros activos puede destruir el controlador (el síntoma típico es una resistencia medida de alrededor de 1 mΩ). La opción vuelve a activarse con cada flasheo de firmware y al restaurar valores por defecto.

### Pasos

1. El **firmware** debe coincidir con la versión de VESC Tool; si no, VESC Tool funciona en *limited mode*. Flashea por USB, nunca por Bluetooth.
2. **Filtros de fase desactivados** (ver arriba).
3. **Detección del motor** con el asistente FOC, sin hélice y con el motor sujeto: uso *Generic*, *Large Inrunner*, datos de la batería, *Direct drive* y sensor de temperatura del motor *Disabled* (el 65121 no lo trae).
4. **Polos del motor.** La detección no los mide. Se deducen del flux linkage λ medido y del KV de placa: `KV = 60 / (√3 · 2π · λ · pares_de_polos)`. En el 65121, λ = 14,1 mWb da 130 KV con **3 pares de polos (6 polos)**. Se ponen en `Motor Settings → Additional Info → Setup`.
5. **Límites, arranque sensorless y ajustes de la app**, según la tabla.

| Ajuste | Valor | Motivo |
| --- | --- | --- |
| Motor Current Max / Brake | 50 A / −30 A | |
| Absolute Maximum Current | 150 A | La detección en firmware 6+ lo deja demasiado bajo (Flipsky recomienda 150–250 A en el 75100) |
| Battery Current Max / Regen | 50 A / −10 A | Deja margen bajo el disyuntor y la BMS de 60 A |
| Battery Voltage Cutoff Start / End | 34,0 V / 31,0 V | 10S; 1 V por encima de lo teórico porque este firmware lee la tensión desfasada 0,5–1 V |
| Battery Voltage Regen Cutoff Start / End | 41,5 V / 42,5 V | Evita sobrecargar el pack lleno al frenar |
| MOSFET Temp Cutoff Start / End | 80 °C / 100 °C | |
| Zero Vector Frequency | 30 kHz | Lo más silencioso; ya es el valor por defecto de este firmware |
| Observer | MXLEMMING lambda comp. | Por defecto, bueno a baja velocidad |
| Openloop sensorless | Preset *Propeller*, Current Boost 6 A | El boost resolvió los arranques que fallaban según la posición del rotor |
| App to Use | UART | |
| Timeout / Timeout Brake Current | 1000 ms / 0 A | Para a 1 s sin órdenes, dejando la hélice libre en vez de frenar |
| Shutdown Mode | Always on | Para que no se apague a la deriva |

Medido en el 65121 (con cable y conectores): R = 55,3 mΩ, L = 67,07 µH, Lq − Ld = 43,1 µH, λ = 14,107 mWb, observer gain 5,02.

### Lectura de la batería

El porcentaje se calcula con la tensión de bus que da el VESC y una curva de tensión en reposo de Li-ion NMC (0 % = 3,00 V y 100 % = 4,15 V por celda, los mismos límites que la BMS Daly). Dos cosas a saber:

- El porcentaje baja al acelerar y se recupera al soltar, porque la tensión cae con la carga.
- El firmware `75_300_R2` lee la tensión algo desfasada. Compárala en reposo con la app de la BMS y ajusta **Batería → Corrección de tensión** hasta que coincidan.

La potencia es `tensión de bus × corriente de batería`, las dos tal como las da el VESC. El VESC no tiene sensor de corriente en la entrada: estima la de batería a partir de las corrientes de fase que mide. La lectura de corriente de la BMS es la más precisa de las dos.

## Actualizaciones

Cuando se publica una versión nueva, la app muestra un punto en el botón del menú y un botón **Actualizar** en ☰ → **Actualizaciones**. Descarga el APK, comprueba su SHA-256 con el publicado en la versión y se lo pasa al instalador de Android.

La app consulta las [versiones publicadas](https://github.com/Danii204/BLERemoteVESC/releases) como mucho cada 12 horas al abrirse (durante la beta incluye las pre-releases). La comprobación automática se puede desactivar. Android solo acepta una actualización firmada con la misma clave que la versión instalada.

## Privacidad

- **No se recoge ni se envía ningún dato.** Sin telemetría, analíticas ni cuentas.
- La única conexión a Internet es una consulta anónima a `api.github.com` para buscar versiones nuevas, y la descarga del APK si aceptas una actualización.
- La ubicación se usa solo en el móvil para calcular la velocidad, y solo con la app en pantalla.

## Compilar

Hace falta JDK 17 o superior y el SDK de Android (plataforma 34).

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`. Las versiones publicadas se firman con una clave que no está en este repositorio; `assembleRelease` la lee de `~/.gradle/gradle.properties`.

## Licencia

[GPL-3.0](LICENSE). Puedes usarla, modificarla y redistribuirla; las versiones modificadas que se distribuyan deben mantener la misma licencia y publicar su código.

Hecho por **Daniel Acevedo** · [danilab.xyz](https://www.danilab.xyz)

*VESC es una marca de Benjamin Vedder. Flipsky y Daly son marcas de sus respectivos dueños. Este proyecto no está afiliado a ninguno de ellos ni cuenta con su aval.*
