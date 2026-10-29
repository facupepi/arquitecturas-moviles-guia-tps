# Mis Gastos v5 (TP4: monetización)

Aplicación Android nativa (Kotlin) para registrar gastos e ingresos, con Firebase como base de datos. Esta versión (`ar.edu.utn.frsfco.finanzas.v5`) suma la estrategia de monetización del TP4 de Arquitecturas Móviles (UTN FRSFCO):

- Banner de AdMob en el resumen y en el historial de la versión gratuita.
- Suscripción **Plus** (semanal, mensual y anual) con la tienda de prueba de RevenueCat. Quita los anuncios y el límite mensual de escaneos de tickets.
- Cupo de tres escaneos de tickets por mes sin suscripción.
- Parámetros de Firebase Remote Config: `mostrar_banner`, `plan_destacado`, `oferta_plus` y `escaneos_gratis`.

Convive con las versiones anteriores en el mismo proyecto de Firebase: no modifica las reglas de Firestore y los parámetros nuevos de Remote Config no alteran el que ya usaban las otras versiones.

## Cómo compilar

1. JDK 17 y Android Studio con el SDK de Android.
2. Descargar `google-services.json` de la consola de Firebase (proyecto `finanzas-tp2-utn`, aplicación `Finanzas v5`) y copiarlo en `app/`. No se versiona.
3. Crear `local.properties` en esta carpeta, que tampoco se versiona:

```
sdk.dir=RUTA_DEL_SDK
revenuecat.testKey=CLAVE_DE_LA_TIENDA_DE_PRUEBA_DE_REVENUECAT
admob.appId=ID_DE_LA_APLICACION_DE_ADMOB
admob.bannerId=ID_DE_LA_UNIDAD_DE_BANNER
```

Si falta `admob.bannerId`, el banner usa la unidad de muestra de Google (anuncios de prueba). La clave de RevenueCat solo se incluye en la compilación de depuración: una versión de lanzamiento no puede usar la tienda de prueba.

4. `./gradlew testDebugUnitTest assembleDebug`

## Firebase

`remoteconfig.template.json` es la plantilla publicada (versión 2) y conserva `tamano_lote_historial`, que usan las versiones anteriores. Antes de volver a desplegarla, partir de `firebase remoteconfig:get` para no pisar cambios hechos en la consola.
