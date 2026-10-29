package cl.iqs.fotosap

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Size
import android.view.Gravity
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

    private val dictado = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val txt = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val et = notaEditando
        if (!txt.isNullOrBlank() && et != null) {
            val actual = et.text.toString().trimEnd()
            et.setText(if (actual.isEmpty()) txt else "$actual $txt")
            et.setSelection(et.text.length)
        }
    }
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

        if (modo == "terreno") {
            enTerreno = true
            render()
            if (ok && uri != null) procesarRegistro(uri, n)
            else uri?.let { runCatching { contentResolver.delete(it, null, null) } }
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
        cargar()
        enEvento = savedInstanceState?.getBoolean("enEvento", false) ?: false
        enTerreno = savedInstanceState?.getBoolean("enTerreno", false) ?: false
        tipoActual = savedInstanceState?.getInt("tipo", -1) ?: -1
        paredActual = savedInstanceState?.getInt("pared", -1) ?: -1
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
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
                       else "📏  Notas en terreno — $activo (${registros().size})"
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
        prefs.edit().remove("terreno_$nombre").remove("tipos_$nombre").remove("caldera_$nombre").apply()
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

    private fun dictar() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicta la nota")
        }
        try { dictado.launch(i) }
        catch (e: Exception) { aviso("El dictado por voz no está disponible en este teléfono") }
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
                put(MediaStore.MediaColumns.MIME_TYPE, if (nombre.endsWith(".csv")) "text/csv" else "text/plain")
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
        col.addView(boton("📷  Nuevo registro (foto del medidor)") { nuevoRegistro() }
            .apply { setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE) })
        col.addView(texto("La app lee el valor de la foto; revísalo siempre antes de guardar.", 12f))
        col.addView(espacio(8))

        val lista = registros().sortedByDescending { it.num }
        if (lista.isEmpty()) col.addView(texto("Aún no hay registros.", 13f))
        for (r in lista) {
            val f = Date(r.fecha)
            val valor = if (r.valor.isBlank()) "sin valor" else "${r.valor.replace('.', ',')} mm"
            val nota = if (r.nota.isBlank()) "" else "\n📝 " + r.nota.take(80) + if (r.nota.length > 80) "…" else ""
            col.addView(boton("#%02d · %s · %s %s%s".format(r.num, valor, fmtFecha.format(f), fmtHora.format(f), nota)) {
                editarRegistro(r, null, "")
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        }
        col.addView(espacio(16))
        col.addView(boton("← Volver a eventos") { enTerreno = false; enEvento = false; render() })
        col.addView(espacio(16))
        col.addView(texto("Se guardan en: $carpetaTerreno\nIncluye Registros.csv para abrir en Excel.", 12f))
        setContentView(scroll(col))
    }

    private fun nuevoRegistro() {
        val num = (registros().maxOfOrNull { it.num } ?: 0) + 1
        val nombre = "Registro_%02d.jpg".format(num)
        val uri = contentResolver.insert(coleccion, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, carpetaTerreno)
        }) ?: run { aviso("No se pudo crear el archivo"); return }
        prefs.edit()
            .putString("pendUri", uri.toString())
            .putString("pendModo", "terreno")
            .putInt("pendTipo", -1).putInt("pendPared", -1)
            .putInt("pendNum", num)
            .apply()
        try { camara.launch(uri) }
        catch (e: Exception) { contentResolver.delete(uri, null, null); aviso("No se encontró app de cámara") }
    }

    /** Foto lista: leer el número con reconocimiento de texto (sin internet) y abrir el formulario. */
    private fun procesarRegistro(uri: Uri, num: Int) {
        val r = Registro(num, System.currentTimeMillis(), "", "", "Registro_%02d.jpg".format(num))
        val espera = AlertDialog.Builder(this).setMessage("Leyendo el valor de la foto…").setCancelable(false).show()
        val listo: (String) -> Unit = { texto ->
            espera.dismiss()
            r.valor = extraerNumero(texto)
            editarRegistro(r, uri, texto)
        }
        try {
            val img = InputImage.fromFilePath(this, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                .addOnSuccessListener { listo(it.text) }
                .addOnFailureListener { listo("") }
        } catch (e: Exception) { listo("") }
    }

    /** Elige el número más probable: primero uno con decimales (ej. 12.45). */
    private fun extraerNumero(texto: String): String {
        val limpio = texto.replace(" ", "")
        val conDecimal = Regex("\\d{1,3}[.,]\\d{1,3}").find(limpio)?.value
        val entero = Regex("\\d+").findAll(limpio).map { it.value }.firstOrNull()
        return (conDecimal ?: entero ?: "").replace(',', '.')
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
