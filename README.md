# Mis Gastos v6 (TP6: retención de usuarios)

Aplicación Android nativa (Kotlin) para registrar gastos e ingresos, con Firebase como base de datos. Esta versión (`ar.edu.utn.frsfco.finanzas.v6`) suma las estrategias de retención del TP6 de Arquitecturas Móviles (UTN FRSFCO) sobre la v5 (monetización):

- **Recordatorio diario.** A la hora elegida (por defecto las 21:00), solo si ese día no se registró ningún gasto. Menciona la racha y abre la carga de un gasto.
- **Resumen semanal.** Los domingos a las 20:00, con el total de la semana, la categoría principal y la variación frente a la anterior. Se calcula en el teléfono.
- **Racha y logros.** Días seguidos con gastos y cinco logros, calculados con los gastos ya guardados.
- **Aviso por inactividad.** Se reprograma cada vez que se abre la aplicación; llega solo tras 7 días sin uso.
- **Campañas push.** Firebase Cloud Messaging, con temas `v6_todos`, `v6_gratuito` y `v6_plus`, y un dato `destino` (`registrar`, `logros`, `planes`, `resumen`) que indica qué pantalla abrir.

Convive con las versiones anteriores en el mismo proyecto de Firebase: es una aplicación registrada aparte, no cambia la base de datos ni sus reglas, y la configuración remota solo sumó parámetros.

## Cómo compilar

1. JDK 17 y Android Studio con el SDK de Android.
2. Descargar `google-services.json` de la consola de Firebase (proyecto `finanzas-tp2-utn`, aplicación `Finanzas v6`) y copiarlo en `app/`. No se versiona.
3. Crear `local.properties` en esta carpeta, que tampoco se versiona:

```
sdk.dir=RUTA_DEL_SDK
revenuecat.testKey=CLAVE_DE_LA_TIENDA_DE_PRUEBA_DE_REVENUECAT
admob.appId=ID_DE_LA_APLICACION_DE_ADMOB
admob.bannerId=ID_DE_LA_UNIDAD_DE_BANNER
```

Si falta `admob.bannerId`, el banner usa la unidad de muestra de Google. La clave de RevenueCat solo se incluye en la compilación de depuración.

4. `./gradlew testDebugUnitTest assembleDebug`

## Probar los avisos programados

La versión de depuración incluye un receptor (`app/src/debug`) que ejecuta en el momento la tarea de un aviso, con el mismo código que corre a su hora:

```
adb shell am broadcast -a ar.edu.utn.frsfco.finanzas.PROBAR_AVISO --es tarea resumen -p ar.edu.utn.frsfco.finanzas.v6
```

Valores de `tarea`: `recordatorio`, `resumen`, `inactividad`. No existe en la versión de lanzamiento.

## Firebase

`remoteconfig.template.json` es la plantilla publicada (versión 3). Conserva los parámetros de las versiones anteriores y suma `recordatorio_hora` y `dias_inactividad`. Antes de volver a desplegarla, partir de `firebase remoteconfig:get` para no pisar cambios hechos en la consola, y no desplegar la plantilla de una versión anterior, que no tiene estos parámetros.
