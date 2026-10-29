package cl.iqs.fotosap

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BoilerImage — ordena fotos de singularidades de la caldera.
 *
 * Pantallas: Eventos → Tipos → Paredes → Singularidades
 *
 * Estructura en el teléfono (visible por USB):
 *   Documents/BoilerImage/<Evento>/<Tipo>/<Pared>/<Prefijo> 01/
 *       AP01_general_1.jpg, AP01_detalle_1.jpg ..., nota.txt
 *   y en cada pared: Resumen notas.txt
 */
class MainActivity : AppCompatActivity() {

    data class Pared(var nombre: String, var cantidad: Int)
    data class Tipo(var nombre: String, var prefijo: String, val paredes: MutableList<Pared>)

    // Documents permite guardar fotos y notas .txt en la misma carpeta
    private val raiz = "Documents/BoilerImage"
    private val coleccion: Uri get() = MediaStore.Files.getContentUri("external")
    private val soloImagenes = " AND ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%'"

    // Campo de texto de la nota abierta (para insertar lo dictado)
    private var notaEditando: EditText? = null

    // Elegir archivo de respaldo para restaurar
    private val abrirRespaldo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restaurarDesde(uri)
    }

    // Respaldo automático cada vez que cambia la configuración
    private var respaldoPendiente = false
    private val alCambiarPrefs = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, clave ->
        if (clave == null || clave.startsWith("pend") || respaldoPendiente) return@OnSharedPreferenceChangeListener
        respaldoPendiente = true
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ respaldoPendiente = false; respaldar(false) }, 1500)
    }

    private val dictado = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val txt = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val et = dictadoDestino ?: notaEditando
        if (!txt.isNullOrBlank() && et != null) {
            if (dictadoNumero) {
                val v = numeroDictado(txt)
                if (v.isBlank()) aviso("No entendí un número: \"$txt\"") else et.setText(v)
            } else {
                val actual = et.text.toString().trimEnd()
                et.setText(if (actual.isEmpty()) txt else "$actual $txt")
            }
            et.setSelection(et.text.length)
        }
        dictadoDestino = null; dictadoNumero = false
    }
    private var dictadoDestino: EditText? = null
    private var dictadoNumero = false
    private val prefs by lazy { getSharedPreferences("fotosap", Context.MODE_PRIVATE) }
    private var tipos = mutableListOf<Tipo>()
    private var enEvento = false
    private var enTerreno = false
    private var enCalderas = false
    private var plantaActual: String? = null
    private var plantilla: String? = null   // caldera que se está editando
    private var tipoActual = -1
    private var paredActual = -1

    private val camara = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = prefs.getString("pendUri", null)?.let(Uri::parse)
        val modo = prefs.getString("pendModo", "") ?: ""
        val ti = prefs.getInt("pendTipo", -1)
        val pi = prefs.getInt("pendPared", -1)
        val n = prefs.getInt("pendNum", -1)
        prefs.edit().remove("pendUri").apply()

        if (modo == "medBase" || modo == "medidor") {
            alVolverCamaraMed(modo, ok, uri)
            return@registerForActivityResult
        }

        if (!ok) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
        } else if (modo == "detalle" && ti >= 0 && pi >= 0 && n > 0) {
            // Modo ráfaga: vuelve a abrir la cámara hasta que el usuario cancele
            tomarFoto(ti, pi, n, "detalle")
            return@registerForActivityResult
        }
        if (ti >= 0) { enEvento = true; tipoActual = ti; paredActual = pi }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plantilla = savedInstanceState?.getString("plantilla")
        enCalderas = savedInstanceState?.getBoolean("enCalderas", false) ?: false
        plantaActual = savedInstanceState?.getString("plantaActual")
        migrarCalderas()
        prefs.registerOnSharedPreferenceChangeListener(alCambiarPrefs)
        cargar()
        enEvento = savedInstanceState?.getBoolean("enEvento", false) ?: false
        enTerreno = savedInstanceState?.getBoolean("enTerreno", false) ?: false
        tipoActual = savedInstanceState?.getInt("tipo", -1) ?: -1
        paredActual = savedInstanceState?.getInt("pared", -1) ?: -1
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    prefs.contains("pendMed") -> descartarMedicion()
                    enTerreno -> { enTerreno = false; enEvento = false; render() }
                    paredActual >= 0 -> { paredActual = -1; render() }
                    tipoActual >= 0 -> { tipoActual = -1; render() }
                    plantilla != null -> cerrarPlantilla()
                    plantaActual != null -> { plantaActual = null; render() }
                    enCalderas -> { enCalderas = false; render() }
                    enEvento -> { enEvento = false; render() }
                    else -> finish()
                }
            }
        })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("enEvento", enEvento)
        outState.putBoolean("enTerreno", enTerreno)
        outState.putBoolean("enCalderas", enCalderas)
        outState.putString("plantaActual", plantaActual)
        outState.putString("plantilla", plantilla)
        outState.putInt("tipo", tipoActual)
        outState.putInt("pared", paredActual)
    }

    // ---------- Datos ----------

    private var evento: String
        get() = prefs.getString("evento", "PGP 2026") ?: "PGP 2026"
        set(v) = prefs.edit().putString("evento", limpiar(v)).apply()

    private fun cuatro(n: Int) = mutableListOf(
        Pared("Pared Frontal", n), Pared("Pared Trasera", n),
        Pared("Pared Izquierda", n), Pared("Pared Derecha", n))

    private fun dos(n: Int) = mutableListOf(Pared("Pared Izquierda", n), Pared("Pared Derecha", n))

    private fun porDefecto() = mutableListOf(
        Tipo("Aires Primarios", "AP", cuatro(30)),
        Tipo("Aires Secundarios", "AS", cuatro(20)),
        Tipo("Aires Terciarios", "AT", cuatro(10)),
        Tipo("Quemadores de Partida", "QP", dos(2)),
        Tipo("Quemadores de Carga", "QC", dos(2)),
        Tipo("Boquillas de Licor", "BL", cuatro(4)))

    /** Clave donde se guarda la configuración: la caldera en edición o el evento actual. */
    private fun claveTipos() = plantilla?.let { "tpl_$it" } ?: "tipos_$evento"

    private fun cargar() {
        // Eventos antiguos sin configuración propia usan la configuración general anterior
        val s = prefs.getString(claveTipos(), null)
            ?: (if (plantilla == null) prefs.getString("tipos", null) else null)
        tipos = desdeJson(s) ?: porDefecto()
    }

    private fun desdeJson(s: String?): MutableList<Tipo>? {
        if (s == null) return null
        val tipos = mutableListOf<Tipo>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ps = o.getJSONArray("paredes")
                val lista = mutableListOf<Pared>()
                for (j in 0 until ps.length()) {
                    val p = ps.getJSONObject(j)
                    lista.add(Pared(p.getString("n"), p.getInt("c")))
                }
                tipos.add(Tipo(o.getString("nombre"), o.getString("prefijo"), lista))
            }
        } catch (e: Exception) {
            return null
        }
        return tipos
    }

    private fun guardar() {
        prefs.edit().putString(claveTipos(), aJson(tipos)).apply()
    }

    private fun aJson(lista: List<Tipo>): String {
        val arr = JSONArray()
        for (t in lista) {
            val ps = JSONArray()
            t.paredes.forEach { ps.put(JSONObject().put("n", it.nombre).put("c", it.cantidad)) }
            arr.put(JSONObject().put("nombre", t.nombre).put("prefijo", t.prefijo).put("paredes", ps))
        }
        return arr.toString()
    }

    private fun limpiar(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "Sin nombre" }

    private fun carpeta(t: Tipo, p: Pared, n: Int) =
        "$raiz/$evento/${t.nombre}/${p.nombre}/${t.prefijo} %02d/".format(n)

    private fun <T> MutableList<T>.mover(i: Int, d: Int) {
        val j = i + d
        if (i in indices && j in indices) { val x = this[i]; this[i] = this[j]; this[j] = x }
    }

    // ---------- Pantallas ----------

    private fun render() {
        if (tipoActual !in tipos.indices) { tipoActual = -1; paredActual = -1 }
        if (tipoActual >= 0 && paredActual !in tipos[tipoActual].paredes.indices) paredActual = -1
        when {
            prefs.contains("pendMed") -> pantallaMedicion()
            enTerreno -> pantallaTerreno()
            plantilla != null && tipoActual < 0 -> pantallaPlantilla()
            plantilla != null -> pantallaPlantillaParedes(tipoActual)
            enCalderas && plantaActual != null -> pantallaPlanta(plantaActual!!)
            enCalderas -> pantallaPlantas()
            !enEvento -> pantallaEventos()
            tipoActual < 0 -> pantallaTipos()
            paredActual < 0 -> pantallaTipo(tipoActual)
            else -> pantallaPared(tipoActual, paredActual)
        }
    }

    private fun pantallaEventos() {
        title = "BoilerImage — Eventos"
        val col = columna()
        val activo = eventoActivo()
        val etiqueta = if (activo == null) "📏  Notas en terreno\n(elige un evento)"
                       else "📏  Notas en terreno — $activo (${mediciones().count { it.optString("origen") == "terreno" }} mediciones)"
        col.addView(boton(etiqueta) { abrirTerreno() }.apply { setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE) })
        col.addView(espacio(16))
        col.addView(texto("Eventos en el teléfono  (✎ para eliminar)", 14f, true))

        val resumen = resumenEventos()
        val nombres = (eventosGuardados() + resumen.keys).distinct()
            .sortedByDescending { resumen[it]?.second ?: Long.MAX_VALUE }
        val fecha = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

        if (nombres.isEmpty()) col.addView(texto("Aún no hay eventos. Crea uno nuevo.", 13f))
        for (nombre in nombres) {
            val r = resumen[nombre]
            val detalle = if (r == null) "sin fotos"
                else "${r.first} fotos · última: ${fecha.format(Date(r.second * 1000))}"
            val marca = if (nombre == evento) "● " else ""
            val cald = prefs.getString("caldera_$nombre", null)?.let { "\n🏭 $it" } ?: ""
            col.addView(fila("$marca$nombre\n$detalle$cald", { abrirEvento(nombre) }) { menuEvento(nombre) })
        }
        col.addView(boton("＋ Nuevo evento") { nuevoEvento() })
        col.addView(espacio(12))
        col.addView(boton("🏭  Plantas y calderas (${plantas().size} plantas · ${calderas().size} calderas)") {
            enCalderas = true; plantaActual = null; render()
        })
        col.addView(boton("💾  Respaldo y restauración") { menuRespaldo() })
        col.addView(espacio(20))
        col.addView(texto("Carpeta en el teléfono:\nAlmacenamiento interno > $raiz", 13f))
        setContentView(scroll(col))
    }

    private fun pantallaTipos() {
        title = evento
        val col = columna()
        col.addView(texto("Tipos de singularidad  (✎ para editar)", 14f, true))

        val conteo = contarFotos()
        val notas = todasLasNotas()
        tipos.forEachIndexed { i, t ->
            val total = t.paredes.sumOf { it.cantidad }
            val listos = t.paredes.sumOf { p ->
                (1..p.cantidad).count { estado(conteo[carpeta(t, p, it)]) == 2 }
            }
            col.addView(fila("${t.nombre}\n$listos / $total completos",
                { tipoActual = i; render() }) { menuTipo(i) })
            val notasTipo = notas.filter { it.tipo == t.nombre }
            if (notasTipo.isNotEmpty()) {
                val ultimas = notasTipo.takeLast(2).joinToString("\n") {
                    "%s %02d: %s".format(it.prefijo, it.num, it.texto.replace("\n", " ").take(50))
                }
                col.addView(texto("📝 ${notasTipo.size} nota(s) — ver todas\n$ultimas", 12f).apply {
                    setPadding(dp(12), dp(2), dp(12), dp(10))
                    setOnClickListener { verNotas(t.nombre, notasTipo) }
                })
            }
        }
        col.addView(boton("＋ Agregar tipo") { agregarTipo() })
        col.addView(boton("💾  Guardar como plantilla de caldera") { guardarComoPlantilla() })
        col.addView(boton("📊  Exportar todas las notas a Excel") {
            val n = exportarNotas()
            aviso(if (n == 0) "No hay notas en este evento"
                  else "Listo: $n notas en $raiz/$evento/Notas $evento.csv")
        })
        col.addView(espacio(16))
        col.addView(boton("← Cambiar evento") { enEvento = false; render() })
        col.addView(espacio(20))
        col.addView(texto(
            "Carpeta en el teléfono:\nAlmacenamiento interno > $raiz/$evento\n\n" +
            "Conecta por USB (Transferencia de archivos) y copia la carpeta del evento.", 13f))
        setContentView(scroll(col))
    }

    private fun pantallaTipo(ti: Int) {
        val t = tipos[ti]
        title = t.nombre
        val col = columna()
        col.addView(texto("Paredes  (✎ para editar)", 14f, true))

        val conteo = contarFotos()
        t.paredes.forEachIndexed { pi, p ->
            val listos = (1..p.cantidad).count { estado(conteo[carpeta(t, p, it)]) == 2 }
            col.addView(fila("${p.nombre}\n$listos / ${p.cantidad} completos",
                { paredActual = pi; render() }) { menuPared(ti, pi) })
        }
        col.addView(boton("＋ Agregar pared") { agregarPared(ti) })
        col.addView(espacio(16))
        col.addView(boton("← Volver a tipos") { tipoActual = -1; render() })
        setContentView(scroll(col))
    }

    private fun pantallaPared(ti: Int, pi: Int) {
        val t = tipos[ti]
        val p = t.paredes[pi]
        title = "${t.prefijo} — ${p.nombre}"
        val col = columna()
        col.addView(texto("Gris: sin fotos · Naranjo: incompleto · Verde: general + detalle · 📝 tiene nota", 12f))
        col.addView(espacio(8))

        val conteo = contarFotos()
        val conNota = carpetasConNota()
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (n in 1..p.cantidad) {
            val info = conteo[carpeta(t, p, n)]
            val total = info?.let { it.first + it.second } ?: 0
            grid.addView(Button(this).apply {
                val nota = if (carpeta(t, p, n) in conNota) " 📝" else ""
                text = "%s %02d\n%d 📷%s".format(t.prefijo, n, total, nota)
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(when (estado(info)) {
                    2 -> Color.parseColor("#2E7D32")
                    1 -> Color.parseColor("#EF6C00")
                    else -> Color.parseColor("#757575")
                })
                setOnClickListener { menuSingularidad(ti, pi, n, info) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = dp(72)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            })
        }
        col.addView(grid)
        col.addView(espacio(16))
        col.addView(boton("← Volver a paredes") { paredActual = -1; render() })
        setContentView(scroll(col))
    }

    // ---------- Menús de edición ----------

    // ---------- Eventos ----------

    private fun eventosGuardados(): MutableList<String> {
        val l = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(prefs.getString("eventos", "[]"))
            for (i in 0 until arr.length()) l.add(arr.getString(i))
        }
        return l
    }

    private fun guardarEventos(l: List<String>) {
        val arr = JSONArray(); l.distinct().forEach { arr.put(it) }
        prefs.edit().putString("eventos", arr.toString()).apply()
    }

    /** Mapa evento -> (nº de fotos, fecha de la última foto en segundos). */
    private fun resumenEventos(): Map<String, Pair<Int, Long>> {
        val res = HashMap<String, Pair<Int, Long>>()
        val proy = arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_ADDED)
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" + soloImagenes
        contentResolver.query(coleccion, proy, sel,
            arrayOf("$raiz/%"), null)?.use { c ->
            while (c.moveToNext()) {
                val ruta = c.getString(0) ?: continue
                val ev = ruta.removePrefix("$raiz/").substringBefore("/")
                if (ev.isEmpty() || ev == "Notas en terreno") continue
                val f = c.getLong(1)
                val (n, ult) = res[ev] ?: (0 to 0L)
                res[ev] = (n + 1) to maxOf(ult, f)
            }
        }
        return res
    }

    private fun abrirEvento(nombre: String) {
        evento = nombre
        guardarEventos(eventosGuardados() + nombre)
        plantilla = null; enCalderas = false; plantaActual = null
        cargar()
        enEvento = true; tipoActual = -1; paredActual = -1
        render()
    }

    private fun nuevoEvento() {
        crearEvento { abrirEvento(it) }
    }

    private fun menuEvento(nombre: String) {
        AlertDialog.Builder(this).setTitle(nombre)
            .setItems(arrayOf("Abrir", "Eliminar evento y sus fotos")) { _, w ->
                when (w) {
                    0 -> abrirEvento(nombre)
                    1 -> confirmar("¿Eliminar \"$nombre\" y TODAS sus fotos del teléfono?\n\n" +
                                   "Asegúrate de haberlas copiado al PC. No se puede deshacer.") {
                        borrarEvento(nombre)
                    }
                }
            }.show()
    }

    private fun borrarEvento(nombre: String) {
        val borradas = try {
            contentResolver.delete(coleccion,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("$raiz/$nombre/%"))
        } catch (e: Exception) { 0 }
        guardarEventos(eventosGuardados().filter { it != nombre })
        prefs.edit().remove("terreno_$nombre").remove("med_$nombre").remove("tipos_$nombre").remove("caldera_$nombre").apply()
        val quedan = resumenEventos()[nombre]?.first ?: 0
        if (quedan > 0)
            aviso("Se borraron $borradas fotos. Quedan $quedan que la app no puede borrar: elimínalas desde Mis archivos.")
        else aviso("Evento eliminado ($borradas fotos)")
        render()
    }

    private fun agregarTipo() {
        dialogoTexto("Nombre del tipo (ej. Aires Secundarios)", "") { nombre ->
            dialogoTexto("Prefijo corto (ej. AS)", nombre.take(2).uppercase()) { pref ->
                tipos.add(Tipo(limpiar(nombre), limpiar(pref), cuatro(10)))
                guardar(); render()
            }
        }
    }

    private fun menuTipo(ti: Int) {
        val t = tipos[ti]
        AlertDialog.Builder(this).setTitle(t.nombre)
            .setItems(arrayOf("Renombrar", "Cambiar prefijo", "Subir", "Bajar", "Eliminar")) { _, w ->
                when (w) {
                    0 -> dialogoTexto("Nuevo nombre", t.nombre) { t.nombre = limpiar(it); guardar(); render() }
                    1 -> dialogoTexto("Nuevo prefijo", t.prefijo) { t.prefijo = limpiar(it); guardar(); render() }
                    2 -> { tipos.mover(ti, -1); guardar(); render() }
                    3 -> { tipos.mover(ti, 1); guardar(); render() }
                    4 -> confirmar("¿Eliminar ${t.nombre}? Las fotos ya tomadas no se borran.") {
                        tipos.removeAt(ti); guardar(); render()
                    }
                }
            }.show()
    }

    private fun agregarPared(ti: Int) {
        dialogoTexto("Nombre de la pared", "Pared ") { nombre ->
            dialogoTexto("Cantidad de singularidades", "10", true) { c ->
                val n = c.toIntOrNull()
                if (n != null && n in 1..300) {
                    tipos[ti].paredes.add(Pared(limpiar(nombre), n)); guardar(); render()
                } else aviso("Cantidad entre 1 y 300")
            }
        }
    }

    private fun menuPared(ti: Int, pi: Int) {
        val lista = tipos[ti].paredes
        val p = lista[pi]
        AlertDialog.Builder(this).setTitle(p.nombre)
            .setItems(arrayOf("Cambiar cantidad", "Renombrar", "Subir", "Bajar", "Eliminar")) { _, w ->
                when (w) {
                    0 -> dialogoTexto("Cantidad", p.cantidad.toString(), true) {
                        val n = it.toIntOrNull()
                        if (n != null && n in 1..300) { p.cantidad = n; guardar(); render() }
                        else aviso("Cantidad entre 1 y 300")
                    }
                    1 -> dialogoTexto("Nuevo nombre", p.nombre) { p.nombre = limpiar(it); guardar(); render() }
                    2 -> { lista.mover(pi, -1); guardar(); render() }
                    3 -> { lista.mover(pi, 1); guardar(); render() }
                    4 -> confirmar("¿Eliminar ${p.nombre}? Las fotos ya tomadas no se borran.") {
                        lista.removeAt(pi); guardar(); render()
                    }
                }
            }.show()
    }

    private fun menuSingularidad(ti: Int, pi: Int, n: Int, info: Pair<Int, Int>?) {
        val t = tipos[ti]
        val p = t.paredes[pi]
        val nota = leerNota(carpeta(t, p, n))
        val col = columna()
        col.addView(texto("Generales: ${info?.first ?: 0}   ·   Detalles: ${info?.second ?: 0}", 14f))
        col.addView(texto(if (nota.isBlank()) "Sin nota" else "📝 $nota", 13f))
        col.addView(espacio(8))
        val dlg = AlertDialog.Builder(this)
            .setTitle("%s %02d — %s".format(t.prefijo, n, p.nombre))
            .setView(scroll(col))
            .setNegativeButton("Cerrar", null)
            .create()
        col.addView(boton("📷  Foto general") { dlg.dismiss(); tomarFoto(ti, pi, n, "general") })
        col.addView(boton("🔍  Detalles (ráfaga)") { dlg.dismiss(); tomarFoto(ti, pi, n, "detalle") })
        col.addView(boton("📝  Nota (escribir o dictar)") { dlg.dismiss(); editarNota(ti, pi, n) })
        col.addView(boton("📍  Foto con puntos de espesor") { dlg.dismiss(); medicionSingularidad(ti, pi, n) })
        col.addView(texto("Ráfaga: la cámara se vuelve a abrir después de cada foto. " +
                          "Presiona Atrás en la cámara para terminar.", 12f))
        dlg.show()
    }

    // ---------- Notas ----------

    private fun editarNota(ti: Int, pi: Int, n: Int) {
        val t = tipos[ti]
        val p = t.paredes[pi]
        val ruta = carpeta(t, p, n)
        val et = EditText(this).apply {
            setText(leerNota(ruta))
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        notaEditando = et
        val col = columna()
        col.addView(et)
        col.addView(boton("🎤  Dictar") { dictar() })
        AlertDialog.Builder(this)
            .setTitle("Nota %s %02d — %s".format(t.prefijo, n, p.nombre))
            .setView(col)
            .setPositiveButton("Guardar") { _, _ ->
                guardarNota(ruta, et.text.toString().trim())
                actualizarResumen(t, p)
                exportarNotas()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { notaEditando = null }
            .show()
    }

    /** Dictado por voz. [destino] = campo que recibe el texto; [numero] = convertir a número (espesor). */
    private fun dictar(destino: EditText? = null, numero: Boolean = false) {
        dictadoDestino = destino; dictadoNumero = numero
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (numero) "Dicta el espesor (ej. 18 coma 29)" else "Dicta la nota")
        }
        try { dictado.launch(i) }
        catch (e: Exception) { aviso("El dictado por voz no está disponible en este teléfono") }
    }

    private val palabrasNum = mapOf(
        "cero" to 0, "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
        "trece" to 13, "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17,
        "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20, "veintiuno" to 21, "veintiun" to 21,
        "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26,
        "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30, "cuarenta" to 40,
        "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90)

    /** "18,29", "18 coma 29" o "dieciocho coma veintinueve" → "18.29". "" si no hay número. */
    private fun numeroDictado(t: String): String {
        val s = java.text.Normalizer.normalize(t.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("\\s*(coma|punto)\\s*"), " , ").replace('.', ',')
        if (Regex("\\d").containsMatchIn(s)) {
            val d = s.replace(Regex("\\s*,\\s*"), ".").replace(Regex("(?<=\\d)\\s+(?=\\d)"), "")
            return Regex("\\d+(\\.\\d+)?").find(d)?.value ?: ""
        }
        val partes = s.split(",")
        fun entero(p: String): Int? {
            val v = p.split(Regex("\\s+")).filter { it.isNotBlank() && it != "y" }.map { palabrasNum[it] ?: return null }
            return if (v.isEmpty()) null else v.sum()
        }
        val e = entero(partes[0]) ?: return ""
        if (partes.size < 2) return e.toString()
        // Decimales: "cero cinco" → 05, "veintinueve" → 29
        val sb = StringBuilder(); var acum = 0; var hay = false
        for (w in partes[1].split(Regex("\\s+")).filter { it.isNotBlank() && it != "y" }) {
            val v = palabrasNum[w] ?: break
            if (v == 0 && !hay) sb.append('0') else { acum += v; hay = true }
        }
        if (hay) sb.append(acum)
        return if (sb.isEmpty()) e.toString() else "$e.$sb"
    }

    /** Busca un archivo por nombre dentro de una carpeta. */
    private fun buscarArchivo(ruta: String, nombre: String): Uri? {
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        contentResolver.query(coleccion, arrayOf(MediaStore.MediaColumns._ID), sel,
            arrayOf(ruta, nombre), null)?.use { c ->
            if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(coleccion, c.getLong(0))
        }
        return null
    }

    private fun leerTexto(uri: Uri): String =
        runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "" }
            .getOrDefault("")

    private fun leerNota(ruta: String): String =
        buscarArchivo(ruta, "nota.txt")?.let { leerTexto(it) }?.trim() ?: ""

    /** Crea o reemplaza un .txt; si el texto está vacío, borra el archivo. */
    private fun escribirTexto(ruta: String, nombre: String, texto: String) {
        val existente = buscarArchivo(ruta, nombre)
        if (texto.isBlank()) {
            existente?.let { runCatching { contentResolver.delete(it, null, null) } }
            return
        }
        try {
            val uri = existente ?: contentResolver.insert(coleccion, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, when {
                    nombre.endsWith(".csv") -> "text/csv"
                    nombre.endsWith(".json") -> "application/json"
                    else -> "text/plain"
                })
                put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
            }) ?: run { aviso("No se pudo guardar $nombre"); return }
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(texto + "\n") }
        } catch (e: Exception) {
            aviso("No se pudo guardar $nombre")
        }
    }

    private fun guardarNota(ruta: String, texto: String) {
        escribirTexto(ruta, "nota.txt", texto)
        aviso(if (texto.isBlank()) "Nota eliminada" else "Nota guardada")
    }

    private fun carpetasConNota(): Set<String> {
        val res = HashSet<String>()
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        contentResolver.query(coleccion, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), sel,
            arrayOf("$raiz/$evento/%", "nota.txt"), null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { res.add(it) }
        }
        return res
    }

    /** Archivo "Resumen notas.txt" en la carpeta de la pared con todas sus notas. */
    private fun actualizarResumen(t: Tipo, p: Pared) {
        val sb = StringBuilder()
        sb.append("$evento — ${t.nombre} — ${p.nombre}\n")
        sb.append("Actualizado: ${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())}\n\n")
        var hay = false
        for (n in 1..p.cantidad) {
            val nota = leerNota(carpeta(t, p, n))
            if (nota.isNotBlank()) {
                hay = true
                sb.append("%s %02d:\n%s\n\n".format(t.prefijo, n, nota))
            }
        }
        escribirTexto("$raiz/$evento/${t.nombre}/${p.nombre}/", "Resumen notas.txt",
            if (hay) sb.toString().trim() else "")
    }

    // ---------- Respaldo y restauración ----------
    // Guarda plantas, calderas, eventos, su configuración y las notas en terreno en un .json.

    private val archivoRespaldo = "Respaldo BoilerImage.json"

    private fun menuRespaldo() {
        AlertDialog.Builder(this)
            .setTitle("Respaldo y restauración")
            .setMessage("El respaldo se actualiza solo cada vez que cambias algo, en:\n" +
                        "$raiz/$archivoRespaldo\n\n" +
                        "Incluye plantas, calderas, eventos y notas en terreno. Las fotos y notas " +
                        "de singularidades ya están como archivos en la carpeta.\n\n" +
                        "Para cambiar de teléfono: copia ese archivo al teléfono nuevo y usa Restaurar.")
            .setPositiveButton("Respaldar ahora") { _, _ -> respaldar(true) }
            .setNeutralButton("Restaurar…") { _, _ ->
                aviso("Elige el archivo $archivoRespaldo")
                runCatching { abrirRespaldo.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun respaldar(avisar: Boolean) {
        val o = JSONObject()
        o.put("app", "BoilerImage").put("version", 1).put("fecha", System.currentTimeMillis())
        val datos = JSONObject()
        for ((k, v) in prefs.all) {
            if (k.startsWith("pend") || v == null) continue
            val t = when (v) {
                is String -> "s"; is Int -> "i"; is Long -> "l"; is Boolean -> "b"; is Float -> "f"
                else -> continue
            }
            datos.put(k, JSONObject().put("t", t).put("v", v))
        }
        o.put("datos", datos)
        escribirTexto("$raiz/", archivoRespaldo, o.toString(2))
        if (avisar) aviso("Respaldo guardado en $raiz/$archivoRespaldo")
    }

    private fun restaurarDesde(uri: Uri) {
        val o = runCatching { JSONObject(leerTexto(uri)) }.getOrNull()
        val datos = o?.optJSONObject("datos")
        if (o == null || o.optString("app") != "BoilerImage" || datos == null) {
            aviso("Ese archivo no es un respaldo de BoilerImage"); return
        }
        val fecha = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date(o.optLong("fecha")))
        confirmar("¿Restaurar el respaldo del $fecha?\n\nReemplaza plantas, calderas, eventos y notas en " +
                  "terreno actuales. Las fotos no se tocan.") {
            prefs.unregisterOnSharedPreferenceChangeListener(alCambiarPrefs)
            val ed = prefs.edit().clear()
            for (k in datos.keys()) {
                val e = datos.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "s" -> ed.putString(k, e.optString("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                }
            }
            ed.commit()
            prefs.registerOnSharedPreferenceChangeListener(alCambiarPrefs)
            migrarCalderas()
            plantilla = null; plantaActual = null; enCalderas = false; enTerreno = false
            enEvento = false; tipoActual = -1; paredActual = -1
            cargar()
            respaldar(false)
            aviso("Respaldo restaurado")
            render()
        }
    }

    // ---------- Plantas y calderas (plantillas) ----------
    // Cada caldera se identifica como "Planta — Caldera" y guarda su configuración en tpl_<id>.

    private val SEP = " — "
    private fun idCaldera(planta: String, caldera: String) = "$planta$SEP$caldera"
    private fun plantaDe(id: String) = id.substringBefore(SEP)
    private fun calderaDe(id: String) = id.substringAfter(SEP)

    private fun listaPrefs(clave: String): MutableList<String> {
        val l = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(prefs.getString(clave, "[]"))
            for (i in 0 until arr.length()) l.add(arr.getString(i))
        }
        return l
    }

    private fun guardarListaPrefs(clave: String, l: List<String>) {
        val arr = JSONArray(); l.distinct().forEach { arr.put(it) }
        prefs.edit().putString(clave, arr.toString()).apply()
    }

    private fun plantas() = listaPrefs("plantas")
    private fun calderas() = listaPrefs("calderas")
    private fun calderasDe(planta: String) = calderas().filter { plantaDe(it) == planta }

    /** Calderas creadas en la versión anterior (sin planta) pasan a la planta "General". */
    private fun migrarCalderas() {
        val viejas = calderas().filter { !it.contains(SEP) }
        if (viejas.isEmpty()) return
        val ed = prefs.edit()
        val nuevas = calderas().map { c ->
            if (c.contains(SEP)) c else {
                val id = idCaldera("General", c)
                ed.putString("tpl_$id", prefs.getString("tpl_$c", null)).remove("tpl_$c")
                id
            }
        }
        ed.apply()
        guardarListaPrefs("calderas", nuevas)
        guardarListaPrefs("plantas", plantas() + "General")
    }

    private fun resumenPlantilla(id: String): String {
        val l = desdeJson(prefs.getString("tpl_$id", null)) ?: return ""
        return "${l.size} tipos · ${l.sumOf { t -> t.paredes.sumOf { it.cantidad } }} singularidades"
    }

    /** Nombre del evento → planta → caldera. Llama [luego] con el nombre del evento. */
    private fun crearEvento(luego: (String) -> Unit) {
        dialogoTexto("Nombre del evento (ej. PGP Octubre 2026)", "") { n0 ->
            val nombre = limpiar(n0)
            val aplicar: (String?) -> Unit = { id ->
                if (!prefs.contains("tipos_$nombre")) {
                    val json = id?.let { prefs.getString("tpl_$it", null) }
                        ?: prefs.getString("tipos", null) ?: aJson(porDefecto())
                    val ed = prefs.edit().putString("tipos_$nombre", json)
                    if (id != null) ed.putString("caldera_$nombre", id)
                    ed.apply()
                }
                luego(nombre)
            }
            val conCalderas = plantas().filter { calderasDe(it).isNotEmpty() }
            if (conCalderas.isEmpty() || prefs.contains("tipos_$nombre")) { aplicar(null); return@dialogoTexto }

            val elegirCaldera: (String) -> Unit = { planta ->
                val lista = calderasDe(planta)
                if (lista.size == 1) aplicar(lista[0])
                else AlertDialog.Builder(this)
                    .setTitle("$planta — ¿qué caldera?")
                    .setItems(lista.map { "🔥 ${calderaDe(it)}" }.toTypedArray()) { _, i -> aplicar(lista[i]) }
                    .setCancelable(false).show()
            }
            AlertDialog.Builder(this)
                .setTitle("¿En qué planta?")
                .setItems((conCalderas.map { "🏭 $it" } + "Sin plantilla (configuración por defecto)").toTypedArray()) { _, i ->
                    conCalderas.getOrNull(i)?.let(elegirCaldera) ?: aplicar(null)
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun pantallaPlantas() {
        title = "Plantas y calderas"
        val col = columna()
        col.addView(texto("Crea cada planta y sus calderas una sola vez. Al crear un evento eliges " +
                          "planta y caldera, y se cargan sus tipos, paredes y cantidades.", 13f))
        col.addView(espacio(8))
        val lista = plantas()
        if (lista.isEmpty()) col.addView(texto("Aún no hay plantas.", 13f))
        for (pl in lista) {
            val n = calderasDe(pl).size
            col.addView(fila("🏭 $pl\n$n caldera(s)", { plantaActual = pl; render() }) { menuPlanta(pl) })
        }
        col.addView(boton("＋ Nueva planta") {
            dialogoTexto("Nombre de la planta (ej. Planta Valdivia)", "") { n0 ->
                val n = limpiar(n0)
                if (n in plantas()) { aviso("Ya existe esa planta"); return@dialogoTexto }
                guardarListaPrefs("plantas", plantas() + n)
                plantaActual = n; render()
            }
        })
        col.addView(espacio(16))
        col.addView(boton("← Volver a eventos") { enCalderas = false; render() })
        setContentView(scroll(col))
    }

    private fun menuPlanta(pl: String) {
        AlertDialog.Builder(this).setTitle(pl)
            .setItems(arrayOf("Abrir", "Renombrar", "Eliminar")) { _, w ->
                when (w) {
                    0 -> { plantaActual = pl; render() }
                    1 -> dialogoTexto("Nuevo nombre", pl) { n0 ->
                        val n = limpiar(n0)
                        if (n == pl) return@dialogoTexto
                        if (n in plantas()) { aviso("Ya existe esa planta"); return@dialogoTexto }
                        val ed = prefs.edit()
                        val nuevas = calderas().map { id ->
                            if (plantaDe(id) != pl) id else {
                                val nid = idCaldera(n, calderaDe(id))
                                ed.putString("tpl_$nid", prefs.getString("tpl_$id", null)).remove("tpl_$id")
                                nid
                            }
                        }
                        ed.apply()
                        guardarListaPrefs("calderas", nuevas)
                        guardarListaPrefs("plantas", plantas().map { if (it == pl) n else it })
                        render()
                    }
                    2 -> confirmar("¿Eliminar la planta \"$pl\" y sus ${calderasDe(pl).size} caldera(s)? " +
                                   "Los eventos ya creados no cambian.") {
                        val ed = prefs.edit()
                        calderasDe(pl).forEach { ed.remove("tpl_$it") }
                        ed.apply()
                        guardarListaPrefs("calderas", calderas().filter { plantaDe(it) != pl })
                        guardarListaPrefs("plantas", plantas().filter { it != pl })
                        render()
                    }
                }
            }.show()
    }

    private fun pantallaPlanta(pl: String) {
        title = "🏭 $pl"
        val col = columna()
        col.addView(texto("Calderas  (✎ para renombrar, duplicar o eliminar)", 14f, true))
        val lista = calderasDe(pl)
        if (lista.isEmpty()) col.addView(texto("Aún no hay calderas en esta planta.", 13f))
        for (id in lista) {
            col.addView(fila("🔥 ${calderaDe(id)}\n${resumenPlantilla(id)}", { abrirPlantilla(id) }) { menuCaldera(id) })
        }
        col.addView(boton("＋ Nueva caldera") {
            dialogoTexto("Nombre de la caldera (ej. Caldera Recuperadora)", "") { n0 ->
                val id = idCaldera(pl, limpiar(n0))
                if (id in calderas()) { aviso("Ya existe esa caldera"); return@dialogoTexto }
                prefs.edit().putString("tpl_$id", aJson(porDefecto())).apply()
                guardarListaPrefs("calderas", calderas() + id)
                abrirPlantilla(id)
            }
        })
        col.addView(espacio(16))
        col.addView(boton("← Volver a plantas") { plantaActual = null; render() })
        setContentView(scroll(col))
    }

    private fun menuCaldera(id: String) {
        val pl = plantaDe(id)
        AlertDialog.Builder(this).setTitle(calderaDe(id))
            .setItems(arrayOf("Editar", "Renombrar", "Duplicar", "Eliminar")) { _, w ->
                when (w) {
                    0 -> abrirPlantilla(id)
                    1 -> dialogoTexto("Nuevo nombre", calderaDe(id)) { n0 ->
                        val nid = idCaldera(pl, limpiar(n0))
                        if (nid == id) return@dialogoTexto
                        if (nid in calderas()) { aviso("Ya existe esa caldera"); return@dialogoTexto }
                        prefs.edit().putString("tpl_$nid", prefs.getString("tpl_$id", null)).remove("tpl_$id").apply()
                        guardarListaPrefs("calderas", calderas().map { if (it == id) nid else it })
                        render()
                    }
                    2 -> dialogoTexto("Nombre de la copia", "${calderaDe(id)} (copia)") { n0 ->
                        val nid = idCaldera(pl, limpiar(n0))
                        if (nid in calderas()) { aviso("Ya existe esa caldera"); return@dialogoTexto }
                        prefs.edit().putString("tpl_$nid", prefs.getString("tpl_$id", null)).apply()
                        guardarListaPrefs("calderas", calderas() + nid)
                        render()
                    }
                    3 -> confirmar("¿Eliminar la caldera \"${calderaDe(id)}\"? Los eventos ya creados no cambian.") {
                        prefs.edit().remove("tpl_$id").apply()
                        guardarListaPrefs("calderas", calderas().filter { it != id })
                        render()
                    }
                }
            }.show()
    }

    private fun abrirPlantilla(id: String) {
        plantilla = id; enCalderas = true; plantaActual = plantaDe(id)
        tipoActual = -1; paredActual = -1
        cargar(); render()
    }

    private fun cerrarPlantilla() {
        plantilla = null; tipoActual = -1; paredActual = -1
        cargar(); render()
    }

    private fun pantallaPlantilla() {
        title = "🔥 ${plantilla?.let { calderaDe(it) }}"
        val col = columna()
        col.addView(texto("${plantilla?.let { plantaDe(it) }} — tipos de singularidad  (✎ para editar)", 14f, true))
        tipos.forEachIndexed { i, t ->
            col.addView(fila("${t.nombre} (${t.prefijo})\n${t.paredes.size} paredes · " +
                             "${t.paredes.sumOf { it.cantidad }} singularidades",
                { tipoActual = i; render() }) { menuTipo(i) })
        }
        col.addView(boton("＋ Agregar tipo") { agregarTipo() })
        col.addView(espacio(16))
        col.addView(boton("✓ Listo — volver a la planta") { cerrarPlantilla() })
        setContentView(scroll(col))
    }

    private fun pantallaPlantillaParedes(ti: Int) {
        val t = tipos[ti]
        title = "🔥 ${t.nombre}"
        val col = columna()
        col.addView(texto("Paredes — toca una para cambiar cantidad, nombre u orden", 14f, true))
        t.paredes.forEachIndexed { pi, p ->
            col.addView(fila("${p.nombre}\n${p.cantidad} singularidades",
                { menuPared(ti, pi) }) { menuPared(ti, pi) })
        }
        col.addView(boton("＋ Agregar pared") { agregarPared(ti) })
        col.addView(espacio(16))
        col.addView(boton("← Volver a tipos") { tipoActual = -1; render() })
        setContentView(scroll(col))
    }

    /** Guarda la configuración del evento actual como caldera de una planta. */
    private fun guardarComoPlantilla() {
        val previo = prefs.getString("caldera_$evento", null)
        dialogoTexto("Planta (ej. Planta Valdivia)", previo?.let { plantaDe(it) } ?: "") { p0 ->
            val pl = limpiar(p0)
            dialogoTexto("Caldera (ej. Caldera Recuperadora)", previo?.let { calderaDe(it) } ?: "") { c0 ->
                val id = idCaldera(pl, limpiar(c0))
                val hacer = {
                    prefs.edit().putString("tpl_$id", aJson(tipos)).putString("caldera_$evento", id).apply()
                    guardarListaPrefs("plantas", plantas() + pl)
                    guardarListaPrefs("calderas", calderas() + id)
                    aviso("Guardada: $id")
                    render()
                }
                if (id in calderas()) confirmar("Ya existe \"$id\". ¿Reemplazarla con esta configuración?") { hacer() }
                else hacer()
            }
        }
    }

    // ---------- Notas en terreno (mediciones de espesor) ----------

    data class Registro(val num: Int, val fecha: Long, var valor: String, var nota: String, val foto: String)

    private val carpetaTerreno get() = "$raiz/$evento/Notas en terreno/"

    /** Evento activo (marcado con ●) si existe; null si no hay ninguno. */
    private fun eventoActivo(): String? {
        if (!prefs.contains("evento")) return null
        val existentes = eventosGuardados() + resumenEventos().keys
        return evento.takeIf { it in existentes }
    }

    private fun abrirTerreno() {
        if (eventoActivo() != null) { enTerreno = true; render(); return }
        val existentes = (eventosGuardados() + resumenEventos().keys).distinct()
        val irA: (String) -> Unit = { nombre ->
            evento = nombre
            guardarEventos(eventosGuardados() + nombre)
            enTerreno = true; render()
        }
        val crear = {
            crearEvento { irA(it) }
        }
        if (existentes.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Notas en terreno")
                .setMessage("Las notas se guardan dentro de un evento. Crea uno primero.")
                .setPositiveButton("＋ Nuevo evento") { _, _ -> crear() }
                .setNegativeButton("Cancelar", null).show()
        } else {
            AlertDialog.Builder(this).setTitle("¿En qué evento guardar las notas?")
                .setItems(existentes.toTypedArray()) { _, i -> irA(existentes[i]) }
                .setPositiveButton("＋ Nuevo evento") { _, _ -> crear() }
                .setNegativeButton("Cancelar", null).show()
        }
    }
    private val fmtFecha = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val fmtHora = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun registros(): MutableList<Registro> {
        val l = mutableListOf<Registro>()
        runCatching {
            val arr = JSONArray(prefs.getString("terreno_$evento", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                l.add(Registro(o.getInt("num"), o.getLong("fecha"), o.optString("valor"),
                    o.optString("nota"), o.optString("foto")))
            }
        }
        return l
    }

    private fun guardarRegistros(l: List<Registro>) {
        val arr = JSONArray()
        l.sortedBy { it.num }.forEach {
            arr.put(JSONObject().put("num", it.num).put("fecha", it.fecha).put("valor", it.valor)
                .put("nota", it.nota).put("foto", it.foto))
        }
        prefs.edit().putString("terreno_$evento", arr.toString()).apply()
        exportarCsv(l)
    }

    private fun pantallaTerreno() {
        title = "Notas en terreno — $evento"
        val col = columna()
        col.addView(boton("📍  Nueva medición de espesores") { medicionTerreno() }.apply {
            setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE); minHeight = dp(72); textSize = 17f
        })
        col.addView(texto("1. Foto de la zona   2. Toca cada punto medido   3. Valor con foto del medidor, " +
                          "escrito o dictado   4. Guardar: queda la foto con el espesor en cada punto.", 12f))
        col.addView(espacio(8))

        val meds = mediciones().filter { it.optString("origen") == "terreno" }.sortedByDescending { it.optLong("fecha") }
        col.addView(texto("Mediciones (${meds.size})  — toca para ver, ✎ para editar o eliminar", 14f, true))
        if (meds.isEmpty()) col.addView(texto("Aún no hay mediciones.", 13f))
        meds.forEach { col.addView(filaMedicion(it)) }

        // Lecturas sin foto de zona (creadas con la versión anterior)
        val enMed = meds.flatMap { m -> puntosDe(m).map { it.num } }.toSet()
        val sueltas = registros().filter { it.num !in enMed }.sortedByDescending { it.num }
        if (sueltas.isNotEmpty()) {
            col.addView(espacio(12))
            col.addView(texto("Lecturas sueltas (versión anterior)", 14f, true))
            for (r in sueltas) {
                val f = Date(r.fecha)
                val valor = if (r.valor.isBlank()) "sin valor" else "${r.valor.replace('.', ',')} mm"
                val nota = if (r.nota.isBlank()) "" else "\n📝 " + r.nota.take(80) + if (r.nota.length > 80) "…" else ""
                col.addView(boton("#%02d · %s · %s %s%s".format(r.num, valor, fmtFecha.format(f), fmtHora.format(f), nota)) {
                    editarRegistro(r, null, "")
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
            }
        }
        col.addView(espacio(16))
        col.addView(boton("← Volver a eventos") { enTerreno = false; enEvento = false; render() })
        col.addView(espacio(16))
        col.addView(texto("Se guardan en: $carpetaTerreno\nMedicion_NN.jpg (foto con espesores) y Registros.csv para Excel.", 12f))
        setContentView(scroll(col))
    }

    /** Formulario de un registro. [uriNueva] != null cuando es un registro recién fotografiado. */
    private fun editarRegistro(r: Registro, uriNueva: Uri?, textoLeido: String) {
        val esNuevo = uriNueva != null
        val uriFoto = uriNueva ?: buscarArchivo(carpetaTerreno, r.foto)
        val col = columna()

        uriFoto?.let { u ->
            runCatching { contentResolver.loadThumbnail(u, Size(800, 800), null) }.getOrNull()?.let { bmp ->
                col.addView(ImageView(this).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }
        }
        if (esNuevo) col.addView(texto(
            if (textoLeido.isBlank()) "No se pudo leer texto en la foto. Escribe el valor."
            else "Texto leído: " + textoLeido.replace("\n", " ").take(60), 12f))

        col.addView(texto("Espesor (mm)", 13f, true))
        val etValor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(r.valor); setSelection(text.length)
        }
        col.addView(etValor)
        col.addView(texto("Nota", 13f, true))
        val etNota = EditText(this).apply {
            setText(r.nota); minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        col.addView(etNota)
        notaEditando = etNota
        col.addView(boton("🎤  Dictar nota") { dictar() })
        val f = Date(r.fecha)
        col.addView(texto("${fmtFecha.format(f)} ${fmtHora.format(f)}", 12f))

        val dlg = AlertDialog.Builder(this)
            .setTitle("Registro #%02d".format(r.num))
            .setView(scroll(col))
            .setCancelable(false)
            .setPositiveButton("Guardar") { _, _ ->
                r.valor = etValor.text.toString().trim().replace(',', '.')
                r.nota = etNota.text.toString().trim()
                val l = registros().filter { it.num != r.num }.toMutableList()
                l.add(r)
                guardarRegistros(l)
                escribirTexto(carpetaTerreno, "Registro_%02d.txt".format(r.num), textoRegistro(r))
                aviso("Registro guardado")
                render()
            }
            .setNegativeButton(if (esNuevo) "Descartar" else "Cancelar") { _, _ ->
                if (esNuevo) runCatching { contentResolver.delete(uriNueva!!, null, null) }
            }
            .setOnDismissListener { notaEditando = null }
        if (!esNuevo) dlg.setNeutralButton("Eliminar") { _, _ ->
            confirmar("¿Eliminar el registro #%02d y su foto?".format(r.num)) { borrarRegistro(r) }
        }
        dlg.show()
    }

    private fun textoRegistro(r: Registro): String {
        val f = Date(r.fecha)
        return "Registro #%02d\nFecha: %s\nHora: %s\nEspesor: %s mm\nNota: %s\nFoto: %s".format(
            r.num, fmtFecha.format(f), fmtHora.format(f), r.valor.replace('.', ','), r.nota, r.foto)
    }

    private fun borrarRegistro(r: Registro) {
        buscarArchivo(carpetaTerreno, r.foto)?.let { runCatching { contentResolver.delete(it, null, null) } }
        escribirTexto(carpetaTerreno, "Registro_%02d.txt".format(r.num), "")
        guardarRegistros(registros().filter { it.num != r.num })
        render()
    }

    /** Registros.csv (separador ; y coma decimal, para Excel en español). */
    private fun exportarCsv(l: List<Registro>) {
        if (l.isEmpty()) { escribirTexto(carpetaTerreno, "Registros.csv", ""); return }
        val sb = StringBuilder("﻿N°;Fecha;Hora;Espesor (mm);Nota;Foto\n")
        for (r in l.sortedBy { it.num }) {
            val f = Date(r.fecha)
            val nota = r.nota.replace("\"", "\"\"").replace("\n", " ")
            sb.append("${r.num};${fmtFecha.format(f)};${fmtHora.format(f)};${r.valor.replace('.', ',')};\"$nota\";${r.foto}\n")
        }
        escribirTexto(carpetaTerreno, "Registros.csv", sb.toString().trimEnd())
    }

    // ---------- Resumen y exportación de notas ----------

    data class NotaInfo(val tipo: String, val prefijo: String, val pared: String, val num: Int, val texto: String)

    /** Todas las notas del evento, en el orden de tipos y paredes configurado. */
    private fun todasLasNotas(): List<NotaInfo> {
        val conNota = carpetasConNota()
        val res = mutableListOf<NotaInfo>()
        for (t in tipos) for (p in t.paredes) for (n in 1..p.cantidad) {
            val ruta = carpeta(t, p, n)
            if (ruta in conNota) {
                val txt = leerNota(ruta)
                if (txt.isNotBlank()) res.add(NotaInfo(t.nombre, t.prefijo, p.nombre, n, txt))
            }
        }
        return res
    }

    private fun verNotas(titulo: String, lista: List<NotaInfo>) {
        val col = columna()
        var paredPrevia = ""
        for (nI in lista) {
            if (nI.pared != paredPrevia) {
                col.addView(texto(nI.pared, 14f, true)); paredPrevia = nI.pared
            }
            col.addView(texto("%s %02d: %s".format(nI.prefijo, nI.num, nI.texto), 13f)
                .apply { setPadding(dp(8), dp(2), 0, dp(6)) })
        }
        AlertDialog.Builder(this).setTitle("Notas — $titulo")
            .setView(scroll(col)).setPositiveButton("Cerrar", null).show()
    }

    /** "Notas <evento>.csv" en la carpeta del evento (se abre en Excel). Devuelve nº de notas. */
    private fun exportarNotas(): Int {
        val lista = todasLasNotas()
        val nombre = "Notas $evento.csv"
        if (lista.isEmpty()) { escribirTexto("$raiz/$evento/", nombre, ""); return 0 }
        val sb = StringBuilder("﻿Tipo;Pared;Singularidad;Nota\n")
        for (nI in lista) {
            val nota = nI.texto.replace("\"", "\"\"").replace("\n", " ")
            val sing = "%s %02d".format(nI.prefijo, nI.num)
            sb.append("${nI.tipo};${nI.pared};$sing;\"$nota\"\n")
        }
        escribirTexto("$raiz/$evento/", nombre, sb.toString().trimEnd())
        return lista.size
    }

    // ---------- Medición: foto con puntos de espesor ----------
    // Un solo flujo: foto de la zona → tocar cada punto medido → valor por foto del medidor
    // (lectura automática) o escrito / dictado → la foto muestra el espesor en cada punto →
    // Guardar crea la foto anotada. La sesión en curso vive en prefs ("pendMed", no va al
    // respaldo) para no perderse si Android cierra la app con la cámara abierta. Las mediciones
    // guardadas quedan en "med_<evento>" para verlas, editarlas o eliminarlas.

    data class Punto(var x: Float, var y: Float, var valor: String, var nota: String, val foto: String, val num: Int)

    private var cacheMed: Pair<String, Bitmap>? = null

    private fun sesionMed(): JSONObject? =
        prefs.getString("pendMed", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun guardarSesionMed(s: JSONObject?) {
        if (s == null) { prefs.edit().remove("pendMed").apply(); cacheMed = null }
        else prefs.edit().putString("pendMed", s.toString()).apply()
    }

    private fun puntosDe(s: JSONObject): MutableList<Punto> {
        val l = mutableListOf<Punto>()
        val arr = s.optJSONArray("puntos") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            l.add(Punto(p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.optString("valor"),
                p.optString("nota"), p.optString("foto"), p.optInt("num")))
        }
        return l
    }

    private fun ponerPuntos(s: JSONObject, l: List<Punto>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("valor", it.valor)
                .put("nota", it.nota).put("foto", it.foto).put("num", it.num))
        }
        s.put("puntos", arr)
    }

    private fun textos(a: JSONArray?): MutableList<String> {
        val l = mutableListOf<String>()
        if (a != null) for (i in 0 until a.length()) l.add(a.optString(i))
        return l
    }

    private fun mediciones(): MutableList<JSONObject> {
        val l = mutableListOf<JSONObject>()
        runCatching {
            val arr = JSONArray(prefs.getString("med_$evento", "[]"))
            for (i in 0 until arr.length()) l.add(arr.getJSONObject(i))
        }
        return l
    }

    private fun guardarMediciones(l: List<JSONObject>) {
        val arr = JSONArray(); l.forEach { arr.put(it) }
        prefs.edit().putString("med_$evento", arr.toString()).apply()
    }

    /** Primer número libre para un nombre como "Medicion_%02d.jpg". */
    private fun libre(ruta: String, formato: String, desde: Int = 1): Int {
        var k = desde
        while (buscarArchivo(ruta, formato.format(k)) != null) k++
        return k
    }

    private fun crearImagen(ruta: String, nombre: String): Uri? = runCatching {
        contentResolver.insert(coleccion, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
        })
    }.getOrNull()

    private fun borrarUri(u: String) {
        if (u.isNotBlank()) runCatching { contentResolver.delete(Uri.parse(u), null, null) }
    }

    private fun lanzarCamara(uri: Uri, modo: String) {
        prefs.edit().putString("pendUri", uri.toString()).putString("pendModo", modo)
            .putInt("pendTipo", -1).putInt("pendPared", -1).putInt("pendNum", -1).apply()
        try { camara.launch(uri) }
        catch (e: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            aviso("No se encontró app de cámara")
            if (modo == "medBase") { val s = sesionMed(); guardarSesionMed(null); volverDeMedicion(s) }
        }
    }

    private fun medicionTerreno() {
        val usados = mediciones().map { it.optString("base") }.toSet()
        var k = libre(carpetaTerreno, "Medicion_%02d.jpg")
        while ("Medicion_%02d".format(k) in usados) k++
        iniciarMedicion("terreno", carpetaTerreno, carpetaTerreno + "Originales/",
            "Medicion_%02d".format(k), "$evento — Medición %02d".format(k))
    }

    private fun medicionSingularidad(ti: Int, pi: Int, n: Int) {
        val t = tipos[ti]; val p = t.paredes[pi]
        val ruta = carpeta(t, p, n)
        val pre = "%s%02d_espesores_".format(t.prefijo, n)
        val k = libre(ruta, "$pre%d.jpg")
        iniciarMedicion("sing", ruta, ruta + "Espesores/", "$pre$k",
            "$evento — %s %02d — %s".format(t.prefijo, n, p.nombre), ti, pi, n)
    }

    private fun iniciarMedicion(origen: String, ruta: String, extra: String, base: String, titulo: String,
                                ti: Int = -1, pi: Int = -1, n: Int = -1) {
        val uri = crearImagen(extra, "${base}_original.jpg") ?: run { aviso("No se pudo crear el archivo"); return }
        val s = JSONObject().put("origen", origen).put("ruta", ruta).put("extra", extra)
            .put("base", base).put("titulo", titulo).put("foto", uri.toString())
            .put("fecha", System.currentTimeMillis()).put("ti", ti).put("pi", pi).put("n", n)
            .put("puntos", JSONArray())
        guardarSesionMed(s)
        aviso("1. Toma una foto de la zona medida")
        lanzarCamara(uri, "medBase")
    }

    private fun alVolverCamaraMed(modo: String, ok: Boolean, uri: Uri?) {
        val s = sesionMed()
        if (!ok || uri == null || s == null) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            if (modo == "medBase") { guardarSesionMed(null); volverDeMedicion(s) } else render()
            return
        }
        render()
        if (modo == "medBase") { aviso("2. Toca en la foto el punto donde mediste"); return }
        val espera = AlertDialog.Builder(this).setMessage("Leyendo el valor del medidor…").setCancelable(false).show()
        leerMedidor(uri) { valor, texto -> espera.dismiss(); dialogoPunto(uri, valor, texto, -1) }
    }

    private fun volverDeMedicion(s: JSONObject?) {
        if (s?.optString("origen") == "terreno") enTerreno = true
        else if (s != null && s.optInt("ti") >= 0) {
            enEvento = true; tipoActual = s.optInt("ti"); paredActual = s.optInt("pi")
        }
        render()
    }

    /** Toque en un lugar libre de la foto: elegir cómo registrar el valor del nuevo punto. */
    private fun nuevoPunto(x: Float, y: Float) {
        val s = sesionMed() ?: return
        AlertDialog.Builder(this).setTitle("Punto P${puntosDe(s).size + 1} — ¿cómo registras el valor?")
            .setItems(arrayOf("📷  Foto del medidor (lee el valor)", "⌨️  Escribir o 🎤 dictar el valor")) { _, w ->
                prepararPunto(x, y)
                if (w == 0) fotoMedidor() else dialogoPunto(null, "", "", -1)
            }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Guarda en la sesión la posición y el número del punto que se está agregando. */
    private fun prepararPunto(x: Float, y: Float) {
        val s = sesionMed() ?: return
        val puntos = puntosDe(s)
        val num = if (s.getString("origen") == "terreno") {
            val desde = maxOf(registros().maxOfOrNull { it.num } ?: 0,
                              puntos.maxOfOrNull { it.num } ?: 0,
                              textos(s.optJSONArray("prevNums")).maxOfOrNull { it.toIntOrNull() ?: 0 } ?: 0) + 1
            libre(s.getString("ruta"), "Registro_%02d.jpg", desde)
        } else libre(s.getString("extra"), "${s.getString("base")}_medidor_%d.jpg", (puntos.maxOfOrNull { it.num } ?: 0) + 1)
        s.put("px", x.toDouble()).put("py", y.toDouble()).put("pnum", num)
        guardarSesionMed(s)
    }

    /** Abre la cámara para fotografiar el medidor del punto preparado. */
    private fun fotoMedidor() {
        val s = sesionMed() ?: return
        val num = s.optInt("pnum")
        val (carpetaFoto, nombre) = if (s.getString("origen") == "terreno")
            s.getString("ruta") to "Registro_%02d.jpg".format(num)
            else s.getString("extra") to "${s.getString("base")}_medidor_$num.jpg"
        buscarArchivo(carpetaFoto, nombre)?.let { runCatching { contentResolver.delete(it, null, null) } }
        val uri = crearImagen(carpetaFoto, nombre) ?: run { aviso("No se pudo crear el archivo"); return }
        lanzarCamara(uri, "medidor")
    }

    /** Valor de un punto: nuevo (indice = -1) o corrección de uno existente. Foto del medidor opcional. */
    private fun dialogoPunto(uriMedidor: Uri?, valorLeido: String, textoLeido: String, indice: Int) {
        val s = sesionMed() ?: return
        val existente = puntosDe(s).getOrNull(indice)
        val etiqueta = "P${if (existente != null) indice + 1 else puntosDe(s).size + 1}"
        val col = columna()
        uriMedidor?.let { u ->
            runCatching { contentResolver.loadThumbnail(u, Size(800, 800), null) }.getOrNull()?.let { bmp ->
                col.addView(ImageView(this).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }
        }
        if (existente == null) col.addView(texto(when {
            uriMedidor == null -> "Escribe el espesor o tócalo en 🎤 para dictarlo (ej. \"dieciocho coma veintinueve\")."
            valorLeido.isBlank() -> "No se pudo leer el valor. Escríbelo o díctalo."
            else -> "Valor leído de la foto: revísalo antes de agregar."
        }, 12f))
        col.addView(texto("Espesor (mm)", 13f, true))
        val etValor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 22f
            setText(existente?.valor ?: valorLeido); setSelection(text.length)
        }
        col.addView(etValor)
        col.addView(boton("🎤  Dictar espesor") { dictar(etValor, true) })
        col.addView(texto("Nota (opcional)", 13f, true))
        val etNota = EditText(this).apply {
            setText(existente?.nota ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        col.addView(etNota)
        col.addView(boton("🎤  Dictar nota") { dictar(etNota, false) })

        val dlg = AlertDialog.Builder(this)
            .setTitle("Punto $etiqueta")
            .setView(scroll(col))
            .setCancelable(false)
            .setPositiveButton(if (existente == null) "Agregar punto" else "Guardar") { _, _ ->
                val s2 = sesionMed() ?: return@setPositiveButton
                val l = puntosDe(s2)
                val v = etValor.text.toString().trim().replace(',', '.')
                val nt = etNota.text.toString().trim()
                if (existente != null && indice in l.indices) { l[indice].valor = v; l[indice].nota = nt }
                else l.add(Punto(s2.optDouble("px").toFloat(), s2.optDouble("py").toFloat(), v, nt,
                                 uriMedidor?.toString() ?: "", s2.optInt("pnum")))
                ponerPuntos(s2, l); guardarSesionMed(s2); render()
                if (v.isBlank()) aviso("Punto agregado sin valor: tócalo para completarlo")
            }
            .setNegativeButton(if (existente == null) "Descartar" else "Cancelar") { _, _ ->
                if (existente == null) uriMedidor?.let { runCatching { contentResolver.delete(it, null, null) } }
            }
            .setOnDismissListener { notaEditando = null }
        if (existente == null) dlg.setNeutralButton(if (uriMedidor != null) "Repetir foto" else "📷 Foto") { _, _ ->
            uriMedidor?.let { runCatching { contentResolver.delete(it, null, null) } }
            fotoMedidor()
        }
        dlg.show()
    }

    private fun menuPunto(i: Int) {
        val s = sesionMed() ?: return
        val p = puntosDe(s).getOrNull(i) ?: return
        AlertDialog.Builder(this).setTitle("Punto P${i + 1}: ${textoValor(p.valor)}")
            .setItems(arrayOf("Corregir valor o nota", "Eliminar punto")) { _, w ->
                when (w) {
                    0 -> dialogoPunto(p.foto.takeIf { it.isNotBlank() }?.let(Uri::parse), p.valor, "", i)
                    1 -> confirmar("¿Eliminar el punto P${i + 1}?") {
                        val s2 = sesionMed() ?: return@confirmar
                        val l = puntosDe(s2)
                        if (i in l.indices) {
                            // La foto del medidor se borra al guardar (por si se cancela la edición)
                            if (l[i].foto.isNotBlank()) s2.put("borrar", (s2.optJSONArray("borrar") ?: JSONArray()).put(l[i].foto))
                            l.removeAt(i)
                        }
                        ponerPuntos(s2, l); guardarSesionMed(s2); render()
                    }
                }
            }.show()
    }

    private fun descartarMedicion() {
        val s = sesionMed()
        val editar = s?.optBoolean("editar") == true
        confirmar(if (editar) "¿Salir sin guardar los cambios?"
                  else "¿Salir sin guardar? Se borran la foto y las fotos del medidor de esta medición.") {
            if (s != null) {
                val orig = textos(s.optJSONArray("orig")).toSet()
                (puntosDe(s).map { it.foto } + textos(s.optJSONArray("borrar")))
                    .filter { it !in orig }.forEach { borrarUri(it) }
                if (!editar) borrarUri(s.optString("foto"))
            }
            guardarSesionMed(null)
            volverDeMedicion(s)
        }
    }

    /** Carga una imagen ya girada según la cámara, reducida a ~[maxLado] px. */
    private fun cargarBitmap(uri: Uri, maxLado: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val lado = maxOf(info.size.width, info.size.height)
            var m = 1
            while (lado / (m + 1) >= maxLado) m++
            if (m > 1) dec.setTargetSampleSize(m)
        }

    private fun pantallaMedicion() {
        val s = sesionMed() ?: run { guardarSesionMed(null); render(); return }
        title = "📍 " + s.optString("titulo")
        val puntos = puntosDe(s)
        val fotoUri = s.optString("foto")
        val bmp = cacheMed?.takeIf { it.first == fotoUri }?.second
            ?: runCatching { cargarBitmap(Uri.parse(fotoUri), 1600) }.getOrNull()?.also { cacheMed = fotoUri to it }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        col.addView(texto(if (puntos.isEmpty()) "Toca en la foto el punto donde mediste."
                          else "Toca otro punto para agregarlo, o un punto marcado para corregirlo.", 13f, true))
        if (bmp != null) {
            col.addView(VistaPuntos(bmp, puntos).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
        } else {
            col.addView(texto("Esperando la foto de la zona…", 14f).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
        }
        val resumen = if (puntos.isEmpty()) "Sin puntos todavía"
            else puntos.mapIndexed { i, p -> "P${i + 1}: ${textoValor(p.valor)}" }.joinToString(" · ")
        col.addView(texto(resumen, 13f, true))
        val filaBotones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filaBotones.addView(boton("✕ Salir") { descartarMedicion() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        filaBotones.addView(boton("💾 Guardar (${puntos.size})") { guardarMedicion() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundColor(Color.parseColor("#2E7D32")); setTextColor(Color.WHITE)
        })
        col.addView(filaBotones)
        setContentView(col)
    }

    private fun textoValor(v: String) = if (v.isBlank()) "—" else "${v.replace('.', ',')} mm"

    /** Muestra la foto y los puntos; un toque agrega un punto o abre uno existente. */
    private inner class VistaPuntos(val bmp: Bitmap, val puntos: List<Punto>) : View(this@MainActivity) {
        private val dst = RectF()

        override fun onDraw(c: Canvas) {
            val esc = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val w = bmp.width * esc; val h = bmp.height * esc
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawBitmap(bmp, null, dst, null)
            dibujarPuntos(c, dst, puntos)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP && dst.contains(e.x, e.y)) {
                val radio = dp(28).toFloat()
                val cerca = puntos.indexOfFirst {
                    Math.hypot((dst.left + it.x * dst.width() - e.x).toDouble(),
                               (dst.top + it.y * dst.height() - e.y).toDouble()) < radio
                }
                performClick()
                if (cerca >= 0) menuPunto(cerca)
                else nuevoPunto((e.x - dst.left) / dst.width(), (e.y - dst.top) / dst.height())
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }

    /** Dibuja cada punto (círculo con mira) y una etiqueta "P1: 12,45 mm" en el rectángulo de la foto. */
    private fun dibujarPuntos(c: Canvas, r: RectF, puntos: List<Punto>) {
        val u = minOf(r.width(), r.height()) / 360f
        val amarillo = Color.parseColor("#FFEB3B")
        val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * u; color = amarillo }
        val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * u; color = Color.BLACK }
        val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * u; typeface = Typeface.DEFAULT_BOLD }
        val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 0, 0, 0) }
        val fm = letra.fontMetrics
        puntos.forEachIndexed { i, p ->
            val cx = r.left + p.x * r.width()
            val cy = r.top + p.y * r.height()
            val rad = 8f * u
            for (pz in listOf(sombra, linea)) {
                c.drawCircle(cx, cy, rad, pz)
                c.drawLine(cx - rad * 1.7f, cy, cx - rad * 0.5f, cy, pz)
                c.drawLine(cx + rad * 0.5f, cy, cx + rad * 1.7f, cy, pz)
                c.drawLine(cx, cy - rad * 1.7f, cx, cy - rad * 0.5f, pz)
                c.drawLine(cx, cy + rad * 0.5f, cx, cy + rad * 1.7f, pz)
            }
            val txt = "P${i + 1}: ${textoValor(p.valor)}"
            val pad = 4f * u
            val bw = letra.measureText(txt) + 2 * pad
            val bh = fm.descent - fm.ascent + 2 * pad
            var lx = cx + rad * 2f
            var ly = cy - rad * 2f - bh
            if (lx + bw > r.right) lx = cx - rad * 2f - bw
            if (ly < r.top) ly = cy + rad * 2f
            lx = lx.coerceIn(r.left, maxOf(r.left, r.right - bw))
            ly = ly.coerceIn(r.top, maxOf(r.top, r.bottom - bh))
            val caja = RectF(lx, ly, lx + bw, ly + bh)
            val ex = cx.coerceIn(caja.left, caja.right)
            val ey = cy.coerceIn(caja.top, caja.bottom)
            c.drawLine(cx, cy, ex, ey, sombra); c.drawLine(cx, cy, ex, ey, linea)
            c.drawRoundRect(caja, 3f * u, 3f * u, fondo)
            c.drawRoundRect(caja, 3f * u, 3f * u, linea)
            c.drawText(txt, caja.left + pad, caja.top + pad - fm.ascent, letra)
        }
    }

    private fun guardarMedicion() {
        val s = sesionMed() ?: return
        val puntos = puntosDe(s)
        if (puntos.isEmpty()) { aviso("Marca al menos un punto"); return }
        val espera = AlertDialog.Builder(this).setMessage("Guardando foto con espesores…").setCancelable(false).show()
        Thread {
            val res = runCatching { crearFotoAnotada(s, puntos) }
            runOnUiThread {
                espera.dismiss()
                res.onSuccess { terminarMedicion(s, puntos, it) }
                    .onFailure { aviso("No se pudo guardar la foto anotada") }
            }
        }.start()
    }

    /** Crea "<base>.jpg": la foto con los puntos y una franja inferior con título, fecha y valores. */
    private fun crearFotoAnotada(s: JSONObject, puntos: List<Punto>): String {
        val foto = cargarBitmap(Uri.parse(s.getString("foto")), 3200)
        val w = foto.width; val h = foto.height
        val u = minOf(w, h) / 360f
        val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * u }
        val negrita = Paint(letra).apply { typeface = Typeface.DEFAULT_BOLD }
        val pad = 8f * u
        val alto = letra.fontSpacing

        // Líneas de la franja: título + fecha, luego los valores repartidos en el ancho
        val f = Date(s.getLong("fecha"))
        val lineas = mutableListOf("${s.optString("titulo")} · ${fmtFecha.format(f)} ${fmtHora.format(f)}")
        var actual = "Espesores:"
        puntos.forEachIndexed { i, p ->
            val item = " P${i + 1} ${textoValor(p.valor)}" + if (i < puntos.size - 1) " ·" else ""
            if (letra.measureText(actual + item) > w - 2 * pad) { lineas.add(actual); actual = " " + item.trim() }
            else actual += item
        }
        lineas.add(actual)
        val franja = (lineas.size * alto + 2 * pad).toInt()

        val out = Bitmap.createBitmap(w, h + franja, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(foto, 0f, 0f, null)
        foto.recycle()
        dibujarPuntos(c, RectF(0f, 0f, w.toFloat(), h.toFloat()), puntos)
        c.drawRect(0f, h.toFloat(), w.toFloat(), (h + franja).toFloat(), Paint().apply { color = Color.BLACK })
        lineas.forEachIndexed { i, l ->
            c.drawText(l, pad, h + pad + i * alto - letra.fontMetrics.ascent, if (i == 0) negrita else letra)
        }

        val nombre = "${s.getString("base")}.jpg"
        val ruta = s.getString("ruta")
        buscarArchivo(ruta, nombre)?.let { runCatching { contentResolver.delete(it, null, null) } }
        val uri = crearImagen(ruta, nombre) ?: error("sin archivo")
        contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            ?: error("sin archivo")
        out.recycle()
        return nombre
    }

    private fun terminarMedicion(s: JSONObject, puntos: List<Punto>, nombre: String) {
        val base = s.getString("base")
        val f = Date(s.getLong("fecha"))
        textos(s.optJSONArray("borrar")).forEach { borrarUri(it) }
        if (s.getString("origen") == "terreno") {
            // Cada punto queda también como registro en Registros.csv
            val nuevos = puntos.mapIndexed { i, p ->
                val nota = "$base P${i + 1}" + if (p.nota.isBlank()) "" else " — ${p.nota}"
                val foto = if (p.foto.isBlank()) "" else "Registro_%02d.jpg".format(p.num)
                Registro(p.num, s.getLong("fecha"), p.valor, nota, foto)
            }
            val nums = nuevos.map { it.num }.toSet()
            val previos = textos(s.optJSONArray("prevNums")).mapNotNull { it.toIntOrNull() }.toSet()
            (previos - nums).forEach { escribirTexto(carpetaTerreno, "Registro_%02d.txt".format(it), "") }
            guardarRegistros(registros().filter { it.num !in nums && it.num !in previos } + nuevos)
            nuevos.forEach { escribirTexto(carpetaTerreno, "Registro_%02d.txt".format(it.num), textoRegistro(it)) }
        } else {
            val sb = StringBuilder("${s.optString("titulo")}\nFecha: ${fmtFecha.format(f)} ${fmtHora.format(f)}\n" +
                                   "Foto anotada: $nombre\n\n")
            puntos.forEachIndexed { i, p ->
                sb.append("P${i + 1}: ${textoValor(p.valor)}")
                if (p.nota.isNotBlank()) sb.append(" — ${p.nota}")
                sb.append("\n")
            }
            escribirTexto(s.getString("extra"), "$base.txt", sb.toString().trim())
        }
        val m = JSONObject(s.toString()).put("anotada", nombre)
        listOf("editar", "orig", "borrar", "prevNums", "px", "py", "pnum").forEach { m.remove(it) }
        guardarMediciones(mediciones().filter { it.optString("base") != base || it.optString("ruta") != m.optString("ruta") } + m)
        guardarSesionMed(null)
        volverDeMedicion(s)
        verMedicion(m)
    }

    // ---------- Mediciones guardadas ----------

    private fun uriAnotada(m: JSONObject) = buscarArchivo(m.optString("ruta"), m.optString("anotada"))

    private fun nombreMedicion(m: JSONObject) = m.optString("base").replace('_', ' ').replace("Medicion", "Medición")

    /** Muestra la foto con los espesores (con opción de compartir). */
    private fun verMedicion(m: JSONObject) {
        val uri = uriAnotada(m) ?: run { aviso("No se encontró la foto ${m.optString("anotada")}"); return }
        val bmp = runCatching { cargarBitmap(uri, 2000) }.getOrNull() ?: run { aviso("No se pudo abrir la foto"); return }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(nombreMedicion(m))
            .setView(scroll(iv))
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Compartir") { _, _ -> compartir(uri) }
        if (m.optString("origen") == "terreno") dlg.setNegativeButton("Editar puntos") { _, _ -> editarMedicion(m) }
        dlg.show()
    }

    private fun compartir(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, "Compartir foto")) }
            .onFailure { aviso("No hay app para compartir") }
    }

    private fun menuMedicion(m: JSONObject) {
        AlertDialog.Builder(this).setTitle(nombreMedicion(m))
            .setItems(arrayOf("Ver foto con espesores", "Editar puntos", "Eliminar medición")) { _, w ->
                when (w) {
                    0 -> verMedicion(m)
                    1 -> editarMedicion(m)
                    2 -> confirmar("¿Eliminar ${nombreMedicion(m)}, su foto y sus lecturas?") { eliminarMedicion(m) }
                }
            }.show()
    }

    private fun editarMedicion(m: JSONObject) {
        val s = JSONObject(m.toString())
        val puntos = puntosDe(s)
        val orig = JSONArray(); puntos.forEach { if (it.foto.isNotBlank()) orig.put(it.foto) }
        val nums = JSONArray(); puntos.forEach { nums.put(it.num.toString()) }
        s.put("editar", true).put("orig", orig).put("prevNums", nums).put("borrar", JSONArray())
        guardarSesionMed(s)
        render()
    }

    private fun eliminarMedicion(m: JSONObject) {
        uriAnotada(m)?.let { runCatching { contentResolver.delete(it, null, null) } }
        borrarUri(m.optString("foto"))
        val puntos = puntosDe(m)
        puntos.forEach { borrarUri(it.foto) }
        if (m.optString("origen") == "terreno") {
            val nums = puntos.map { it.num }.toSet()
            nums.forEach { escribirTexto(carpetaTerreno, "Registro_%02d.txt".format(it), "") }
            guardarRegistros(registros().filter { it.num !in nums })
        } else escribirTexto(m.optString("extra"), "${m.optString("base")}.txt", "")
        guardarMediciones(mediciones().filter { it.optString("base") != m.optString("base") || it.optString("ruta") != m.optString("ruta") })
        aviso("Medición eliminada")
        render()
    }

    /** Fila de la lista: miniatura de la foto anotada + resumen de valores. */
    private fun filaMedicion(m: JSONObject): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E3E7EB"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, dp(4)) }
            setOnClickListener { verMedicion(m) }
            setOnLongClickListener { menuMedicion(m); true }
        }
        uriAnotada(m)?.let { u -> runCatching { contentResolver.loadThumbnail(u, Size(300, 300), null) }.getOrNull() }
            ?.let { bmp ->
                fila.addView(ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(10) }
                })
            }
        val puntos = puntosDe(m)
        val f = Date(m.optLong("fecha"))
        val valores = puntos.mapIndexed { i, p -> "P${i + 1} ${textoValor(p.valor)}" }.joinToString(" · ")
        fila.addView(texto("${nombreMedicion(m)} · ${puntos.size} punto(s)\n${fmtFecha.format(f)} ${fmtHora.format(f)}\n$valores", 13f)
            .apply {
                setTextColor(Color.parseColor("#14202B"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        fila.addView(boton("✎") { menuMedicion(m) }.apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)) })
        return fila
    }

    // ---------- Lectura del medidor de espesores ----------

    /**
     * Lee el espesor de la foto del medidor sin internet. Elige el número de letra más grande
     * (la lectura principal), ignora la línea de velocidad (m/s) y códigos como G2 D0096.
     * Si no encuentra nada prueba con la imagen girada 90° y 270° (medidor de lado).
     */
    private fun leerMedidor(uri: Uri, listo: (valor: String, texto: String) -> Unit) {
        val bmp = runCatching { cargarBitmap(uri, 2000) }.getOrNull() ?: run { listo("", ""); return }
        val cliente = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val giros = listOf(0, 90, 270)
        var primerTexto = ""
        fun intentar(i: Int) {
            if (i >= giros.size) { listo("", primerTexto); return }
            cliente.process(InputImage.fromBitmap(bmp, giros[i]))
                .addOnSuccessListener { t ->
                    if (primerTexto.isBlank()) primerTexto = t.text
                    val v = valorPrincipal(t)
                    if (v != null) listo(v, t.text) else intentar(i + 1)
                }
                .addOnFailureListener { intentar(i + 1) }
        }
        intentar(0)
    }

    private fun valorPrincipal(t: com.google.mlkit.vision.text.Text): String? {
        var mejor: String? = null
        var mejorAlto = 0
        for (b in t.textBlocks) for (l in b.lines) {
            if (l.text.contains("m/s", true) || l.text.contains("/s")) continue
            for (e in l.elements) {
                val caja = e.boundingBox ?: continue
                val s = e.text.replace(',', '.').replace('O', '0').replace('o', '0')
                    .trimEnd('m', 'M').trim()
                val ok = Regex("^\\d{1,3}\\.\\d{1,3}$").matches(s) || Regex("^\\d{3,5}$").matches(s)
                val alto = minOf(caja.width(), caja.height())
                if (ok && alto > mejorAlto) { mejorAlto = alto; mejor = s }
            }
        }
        // Si se perdió el punto decimal (ej. "1829"), el medidor muestra 2 decimales
        return mejor?.let { if ('.' in it) it else it.dropLast(2) + "." + it.takeLast(2) }
    }

    // ---------- Cámara / MediaStore ----------

    private fun tomarFoto(ti: Int, pi: Int, n: Int, modo: String) {
        if (ti !in tipos.indices || pi !in tipos[ti].paredes.indices) return
        val t = tipos[ti]
        val p = t.paredes[pi]
        val ruta = carpeta(t, p, n)
        val previas = contarFotos()[ruta]
        val previasModo = if (modo == "general") previas?.first ?: 0 else previas?.second ?: 0
        val nombre = "%s%02d_%s_%d.jpg".format(t.prefijo, n, modo, previasModo + 1)

        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ruta)
        }
        val uri = contentResolver.insert(coleccion, valores)
        if (uri == null) { aviso("No se pudo crear el archivo"); return }

        prefs.edit()
            .putString("pendUri", uri.toString())
            .putString("pendModo", modo)
            .putInt("pendTipo", ti)
            .putInt("pendPared", pi)
            .putInt("pendNum", n)
            .apply()
        try {
            camara.launch(uri)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            aviso("No se encontró app de cámara")
        }
    }

    /** Mapa carpeta -> (nº generales, nº detalles) para el evento actual. */
    private fun contarFotos(): Map<String, Pair<Int, Int>> {
        val res = HashMap<String, Pair<Int, Int>>()
        val proy = arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME)
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" + soloImagenes
        val args = arrayOf("$raiz/$evento/%")
        contentResolver.query(coleccion, proy, sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                val ruta = c.getString(0) ?: continue
                val nombre = c.getString(1) ?: ""
                val (g, d) = res[ruta] ?: (0 to 0)
                res[ruta] = if (nombre.contains("_general")) (g + 1 to d) else (g to d + 1)
            }
        }
        return res
    }

    /** 0 = sin fotos, 1 = incompleto, 2 = general + al menos un detalle */
    private fun estado(info: Pair<Int, Int>?): Int = when {
        info == null || info.first + info.second == 0 -> 0
        info.first > 0 && info.second > 0 -> 2
        else -> 1
    }

    // ---------- Diálogos ----------

    private fun dialogoTexto(titulo: String, inicial: String, numero: Boolean = false, ok: (String) -> Unit) {
        val et = EditText(this).apply {
            if (numero) inputType = InputType.TYPE_CLASS_NUMBER
            setText(inicial); setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(conMargen(et))
            .setPositiveButton("Guardar") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty()) aviso("No puede quedar vacío") else ok(v)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmar(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sí") { _, _ -> ok() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun aviso(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Helpers de UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun columna() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun texto(s: String, size: Float, negrita: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (negrita) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun boton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Fila con botón principal + botón ✎ de edición. */
    private fun fila(s: String, onClick: () -> Unit, onEditar: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(boton(s, onClick).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(80), 1f)
            })
            addView(boton("✎", onEditar).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(80))
            })
        }

    private fun espacio(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun conMargen(v: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0); addView(v)
    }
}
