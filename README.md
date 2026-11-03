# Fotos AP

App Android para ordenar las fotos de los aires primarios de la caldera recuperadora.

## Estructura de carpetas que genera

```
Interno/Pictures/FotosAP/
└── <Evento>/
    ├── Pared Frontal/
    │   ├── AP 01/  AP01_general_1.jpg, AP01_detalle_1.jpg, AP01_detalle_2.jpg ...
    │   └── AP 02/
    ├── Pared Trasera/
    ├── Pared Izquierda/
    └── Pared Derecha/
```

## Uso
1. Editar nombre del evento y cantidad de AP por pared (botón ✎).
2. Entrar a una pared → tocar el AP → **General** (1 foto) o **Detalles** (ráfaga: la cámara se reabre tras cada foto; Atrás para terminar).
3. Colores: gris sin fotos · naranjo incompleto · verde general + detalle.
4. **Planos de ubicación** (botón 🗺 en el evento): elegir la pared, indicar N° de tubos y elevaciones, y tocar el plano para marcar cada hallazgo (adelgazamiento, abolladura, erosión, fisura, reparación u otro). La lámina con plano, leyenda y tabla se guarda como PNG en `<Evento>/Planos/` para pegarla en el informe. Para editar un hallazgo: tocar su marca, su fila en la tabla, o el botón **Editar** (lista de hallazgos y datos de la pared).
5. Conectar por USB en modo "Transferencia de archivos" y copiar `Pictures/FotosAP/<Evento>`.

## Compilar con GitHub
1. Crear repositorio y subir todo el contenido de esta carpeta (incluida `.github/`).
2. Pestaña **Actions** → "Compilar APK" se ejecuta solo en cada push (o "Run workflow").
3. Al terminar, descargar el artefacto **FotosAP-apk** (zip con `app-debug.apk`) e instalarlo en el teléfono.

Requiere Android 10 o superior.
