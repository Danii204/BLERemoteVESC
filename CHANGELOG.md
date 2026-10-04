# Cambios

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); versiones según [SemVer](https://semver.org/lang/es/).

## [0.2.0] - 2026-10-04

### Añadido
- **Beta 2** (`0.2.0-beta.2`).
- **Rumbo** en la pantalla principal, bajo la batería: grados, punto cardinal y rosa con el norte en rojo. Sale del GPS (rumbo sobre el fondo), no de la brújula del móvil: en un pato se va sentado de espaldas al sentido de la marcha y la brújula marcaría lo contrario. Funciona a partir de 2 km/h; parado muestra el último rumbo atenuado.

## [0.1.0] - 2026-10-04

### Añadido
- Primera versión pública, **Beta 1** (`0.1.0-beta`).
- Acelerador vertical por corriente (par) con enclavamiento central, salida dura del centro, movimiento solo agarrando el tirador y bloqueo mientras no hay conexión.
- Modos de potencia **Eco**, **Crucero** y **Sport**, cada uno con su tope de corriente y su suavizado de aceleración.
- Telemetría del VESC por Bluetooth: batería (porcentaje, tensión y autonomía estimada), potencia y corriente de batería.
- Velocidad por GPS con filtro de precisión y media móvil.
- Pitido del motor al conectar (LispBM `foc-beep`).
- Temas claro, oscuro y AMOLED con Material 3.
- Ahorro de batería: GPS solo en primer plano, pantalla encendida solo con conexión, atenuado tras 30 s y tráfico Bluetooth adaptativo.
- Corrección de tensión para igualar la lectura con la de la BMS.
- Actualizaciones desde GitHub Releases verificadas con SHA-256.
