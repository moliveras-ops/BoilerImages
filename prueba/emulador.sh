#!/bin/bash
# Prueba en emulador v3.9: evento por elementos, series numeradas, nombre desde imagen, anillo de selección.
OUT=prueba/salida; mkdir -p $OUT
PKG=cl.iqs.fotosap
n=0
cap() { n=$((n+1)); adb exec-out screencap -p > $OUT/$(printf %02d $n)_$1.png; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }
buscar() { # "x y" del primer nodo cuyo texto / descripción / id contiene $1 ("=texto" = texto exacto)
  dump
  python3 - "$1" <<'PY'
import sys,re
t=sys.argv[1].lower(); x=open('prueba/salida/ui.xml',encoding='utf-8').read()
for m in re.finditer(r'<node ([^>]*)>',x):
    a=m.group(1)
    campos=' '.join(re.findall(r'(?:text|content-desc|resource-id)="([^"]*)"',a)).lower()
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',a)
    exacto=t.startswith('=')
    ok=(t[1:] in [c.lower() for c in re.findall(r'text="([^"]*)"',a)]) if exacto else (t in campos)
    if ok and b:
        x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
}
editar() { # toca el ✎ de la fila cuyo título es exactamente $1
  dump
  b=$(python3 - "$1" <<'PY'
import sys,re
t=sys.argv[1].lower(); x=open('prueba/salida/ui.xml',encoding='utf-8').read()
nodos=[]
for m in re.finditer(r'<node ([^>]*)>',x):
    a=m.group(1); b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',a)
    if b: nodos.append((a,list(map(int,b.groups()))))
y=None
for a,(x1,y1,x2,y2) in nodos:
    if [c for c in re.findall(r'text="([^"]*)"',a) if c.lower()==t]: y=(y1+y2)//2; break
if y is not None:
    for a,(x1,y1,x2,y2) in nodos:
        if 'content-desc="Editar"' in a and y1<=y<=y2: print((x1+x2)//2,(y1+y2)//2); break
PY
)
  echo "editar '$1' -> $b"; [ -n "$b" ] && adb shell input tap $b; sleep 2
}
tap() { b=$(buscar "$1"); echo "tap '$1' -> $b"; [ -n "$b" ] && adb shell input tap $b; sleep 2; }
largo() { b=$(buscar "$1"); echo "largo '$1' -> $b"; [ -n "$b" ] && adb shell input swipe $b $b 1200; sleep 2; }
escribir() { # reemplaza el texto del diálogo y guarda ("%s" = espacio)
  adb shell input keyevent KEYCODE_MOVE_END
  adb shell input keyevent $(for i in $(seq 1 24); do printf 'KEYCODE_DEL '; done)
  [ -n "$1" ] && adb shell input text "$1"; sleep 1; tap "=Guardar"; sleep 1
}
campos() { # rellena los EditText del diálogo en orden (Tab pasa al siguiente); "%s" = espacio
  b=$(buscar "nombre base (ej"); [ -z "$b" ] && dump && b=$(python3 -c "
import re;x=open('prueba/salida/ui.xml',encoding='utf-8').read()
m=re.search(r'class=\"android.widget.EditText\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',x)
print((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2) if m else None")
  adb shell input tap $b; sleep 1
  for v in "$@"; do
    adb shell input keyevent KEYCODE_MOVE_END
    adb shell input keyevent $(for i in $(seq 1 12); do printf 'KEYCODE_DEL '; done)
    adb shell input text "$v"; sleep 0.5
    adb shell input keyevent KEYCODE_TAB; sleep 0.5
  done
}
teclado_fuera() { adb shell dumpsys input_method | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK; sleep 1; }
serie() { tap "generar carpetas numeradas"; campos "$1" "$2" "$3" "$4"; teclado_fuera; cap "serie_$1"; tap "=Generar"; sleep 2; }

adb logcat -c
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.CAMERA
adb push prueba/placa.png /sdcard/Download/placa.png
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/placa.png >/dev/null
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); WX=${W%x*}; WY=${W#*x}
abajo() { adb shell input swipe $((WX/2)) $((WY*80/100)) $((WX/2)) $((WY*25/100)) 400; sleep 2; }

adb shell am start -n $PKG/.MainActivity; sleep 4; cap inicio
tap "nuevo evento"; sleep 1; cap tipo_evento
tap "inspección por elementos"; escribir "Prueba"; sleep 3; cap raiz_vacia

# --- Serie de paneles en la raíz ---
serie "Panel" "1" "3" "P"; cap paneles
largo "p01"; cap menu_p01; tap "abrir carpeta"; sleep 2; cap panel01_vacio

# --- Serie de tubos dentro de Panel 01 ---
serie "Tubo" "1" "6" "T"; cap tubos
tap "t01"; sleep 2; cap menu_t01
tap "condición"; sleep 1; cap condiciones; tap "crítico"; sleep 3; cap menu_t01_critico
tap "nota (escribir"; sleep 1; adb shell input text "Desgaste%sen%scara%sfrontal"; tap "=Guardar"; sleep 2
cap tubos_t01_anillo
tap "t04"; sleep 2; adb shell input keyevent KEYCODE_BACK; sleep 2; cap tubos_t04_anillo
tap "t01"; sleep 2; tap "foto general"; sleep 3
for t in "while using" "permitir" "allow"; do b=$(buscar "$t"); [ -n "$b" ] && adb shell input tap $b && sleep 2; done
sleep 4; cap camara
tap "tomar foto"; sleep 4
tap "=Detalle"; tap "tomar foto"; sleep 4; cap camara_detalle
tap "t02"; sleep 2; tap "tomar foto"; sleep 4; cap camara_t02
adb shell input keyevent KEYCODE_BACK; sleep 4; cap tras_camara
tap "t01"; sleep 2; cap menu_t01_fotos
tap "ver fotos capturadas"; sleep 3; cap galeria
adb shell input keyevent KEYCODE_BACK; sleep 2
largo "t03"; cap menu_largo_t03; adb shell input keyevent KEYCODE_BACK; sleep 1

# --- Volver a la raíz: carpeta con nombre leído desde imagen ---
tap "=← Volver"; sleep 2; cap raiz_paneles
abajo; tap "agregar carpeta con nombre"; sleep 1; cap pedir_nombre
tap "imagen de la galería"; sleep 4; cap selector
tap "placa"; sleep 8; cap ocr_resultado
b=$(buscar "=PANEL 12"); [ -z "$b" ] && b=$(buscar "panel 12"); echo "ocr -> $b"; [ -n "$b" ] && adb shell input tap $b; sleep 2; cap ocr_revisar; tap "=Guardar"; sleep 2; cap codigo_sugerido; tap "=Guardar"; sleep 2
cap raiz_con_panel12

# --- Copiar los tubos de P01 a los demás paneles ---
largo "p01"; cap menu_p01_copiar
tap "copiar sus subcarpetas"; sleep 1; cap confirmar_copia; tap "=Sí"; sleep 2; cap paneles_copiados
largo "p02"; tap "abrir carpeta"; sleep 2; cap panel02
tap "=← Volver"; sleep 2

# --- Exportaciones ---
abajo; abajo; cap raiz_final
tap "exportar a excel"; sleep 6; cap excel
adb pull "/sdcard/Documents/BoilerImage/Prueba/BoilerImage Prueba.xlsx" $OUT/prueba.xlsx
adb pull "/sdcard/Documents/BoilerImage/Prueba/Notas Prueba.csv" $OUT/notas.csv
tap "=Cerrar"; sleep 1
tap "exportar evento (zip)"; sleep 2; tap "evento completo"; sleep 8; cap zip_completo
adb pull /sdcard/Documents/BoilerImage/Exportaciones $OUT/zips >/dev/null 2>&1
for z in $OUT/zips/*/*.zip $OUT/zips/*.zip; do [ -f "$z" ] && { echo "== $z"; unzip -l "$z"; } >> $OUT/zips.txt; done
rm -rf $OUT/zips
adb shell "find /sdcard/Documents/BoilerImage/Prueba" > $OUT/archivos.txt 2>&1

# --- Persistencia: reabrir la app ---
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity; sleep 4; cap reabrir
tap "inspección por elementos"; sleep 3; cap evento_reabierto
adb logcat -d > $OUT/logcat.txt
grep -A40 "FATAL EXCEPTION" $OUT/logcat.txt > $OUT/crash.txt || echo "sin crash" > $OUT/crash.txt
cat $OUT/crash.txt
