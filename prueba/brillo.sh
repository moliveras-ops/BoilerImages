#!/bin/bash
# Prueba v3.7: brillo (compensación de exposición) y anillo de enfoque restringido a la foto.
OUT=prueba/salida; mkdir -p $OUT
PKG=cl.iqs.fotosap
n=0
cap() { n=$((n+1)); adb exec-out screencap -p > $OUT/$(printf %02d $n)_$1.png; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }
buscar() {
  dump
  python3 - "$1" <<'PY'
import sys,re
t=sys.argv[1]; x=open('prueba/salida/ui.xml',encoding='utf-8').read()
exacto=t.startswith('='); t=t.lstrip('=').lower()
for m in re.finditer(r'<node ([^>]*)>',x):
    a=m.group(1)
    tx=[c.lower() for c in re.findall(r'(?:text|content-desc)="([^"]*)"',a)]
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',a)
    ok=(t in tx) if exacto else any(t in c for c in tx)
    if ok and b:
        x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
}
tap() { b=$(buscar "$1"); echo "tap '$1' -> $b"; [ -n "$b" ] && adb shell input tap $b; sleep ${2:-2}; }
textos() { dump; grep -o 'text="[^"]*"' $OUT/ui.xml | grep -v 'text=""' | tr '\n' ' '; echo; }
RUTA="Documents/BoilerImage/Prueba/Aires Primarios/Frontal/AP 01/"
abrir() {
  adb shell am start -n $PKG/.CamaraActivity --esal rutas "'$RUTA'" --esal codigos AP01 --esal titulos "'AP01 · Frontal'" --es modo general
  sleep 7
}
foto() { tap "tomar foto" 5; }

adb logcat -c
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.CAMERA
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); WX=${W%x*}; WY=${W#*x}; echo "pantalla $WX x $WY"

abrir; cap camara_inicio
echo "textos inicio: $(textos)"
foto; echo "foto 0 EV"
tap "☀" 2; cap panel_brillo
echo "textos panel: $(textos)"
for i in 1 2 3 4 5 6; do tap "=+" 1; done; sleep 2; cap brillo_mas
echo "textos +: $(textos)"
foto; echo "foto +"
for i in $(seq 1 12); do tap "=−" 1; done; sleep 2; cap brillo_menos
echo "textos -: $(textos)"
foto; echo "foto -"
tap "volver el brillo a 0" 2; cap brillo_cero; echo "textos 0: $(textos)"
# deja +2 pasos para probar que se recuerda
tap "=+" 1; tap "=+" 1
tap "☀" 2   # cerrar el panel para probar el anillo

# --- Anillo de enfoque: tocar dentro de la foto, en la franja negra y cerca del borde ---
adb exec-out screencap -p > $OUT/ref.png
adb shell input tap $((WX/2)) $((WY*45/100)); sleep 0.2; cap anillo_centro
sleep 3
adb shell input tap $((WX*3/4)) $((WY*86/100)); sleep 0.2; cap anillo_franja_abajo
sleep 3
adb shell input tap 4 $((WY*45/100)); sleep 0.2; cap anillo_borde_izq
sleep 3

# --- Reabrir: el brillo debe recordarse ---
adb shell input keyevent KEYCODE_BACK; sleep 2
abrir; cap reabrir; echo "textos reabrir: $(textos)"
tap "☀" 2; tap "volver el brillo a 0" 2; adb shell input keyevent KEYCODE_BACK; sleep 2

adb pull "/sdcard/$RUTA" $OUT/fotos >/dev/null 2>&1
ls -la $OUT/fotos/* 
adb logcat -d > $OUT/logcat.txt
grep -A40 "FATAL EXCEPTION" $OUT/logcat.txt > $OUT/crash.txt || echo "sin crash" > $OUT/crash.txt
cat $OUT/crash.txt
pip install -q pillow >/dev/null 2>&1
python3 - <<'PY' | tee $OUT/brillo_fotos.txt
import glob
from PIL import Image, ImageStat
for f in sorted(glob.glob('prueba/salida/fotos/**/*.jpg', recursive=True)):
    im=Image.open(f).convert('L'); print(f.split('/')[-1], im.size, 'brillo medio %.1f' % ImageStat.Stat(im).mean[0])
for f in sorted(glob.glob('prueba/salida/*_brillo_*.png')+glob.glob('prueba/salida/*camara_inicio.png')):
    im=Image.open(f).convert('L'); w,h=im.size; c=im.crop((0,int(h*.3),w,int(h*.6)))
    print(f.split('/')[-1], 'pantalla (zona central) %.1f' % ImageStat.Stat(c).mean[0])
PY
