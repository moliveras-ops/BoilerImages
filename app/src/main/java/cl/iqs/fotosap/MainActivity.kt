package cl.iqs.fotosap

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 *   Pictures/FotosAP/<Evento>/<Tipo>/<Pared>/<Prefijo> 01/
 *       AP01_general_1.jpg, AP01_detalle_1.jpg ...
 */
class MainActivity : AppCompatActivity() {

    data class Pared(var nombre: String, var cantidad: Int)
    data class Tipo(var nombre: String, var prefijo: String, val paredes: MutableList<Pared>)

    private val raiz = "Pictures/FotosAP"
    private val prefs by lazy { getSharedPreferences("fotosap", Context.MODE_PRIVATE) }
    private var tipos = mutableListOf<Tipo>()
    private var enEvento = false
    private var tipoActual = -1
    private var paredActual = -1

    private val camara = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = prefs.getString("pendUri", null)?.let(Uri::parse)
        val modo = prefs.getString("pendModo", "") ?: ""
        val ti = prefs.getInt("pendTipo", -1)
        val pi = prefs.getInt("pendPared", -1)
        val n = prefs.getInt("pendNum", -1)
        prefs.edit().remove("pendUri").apply()

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
        cargar()
        enEvento = savedInstanceState?.getBoolean("enEvento", false) ?: false
        tipoActual = savedInstanceState?.getInt("tipo", -1) ?: -1
        paredActual = savedInstanceState?.getInt("pared", -1) ?: -1
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    paredActual >= 0 -> { paredActual = -1; render() }
                    tipoActual >= 0 -> { tipoActual = -1; render() }
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

    private fun cargar() {
        val s = prefs.getString("tipos", null)
        if (s == null) { tipos = porDefecto(); guardar(); return }
        tipos = mutableListOf()
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
            tipos = porDefecto(); guardar()
        }
    }

    private fun guardar() {
        val arr = JSONArray()
        for (t in tipos) {
            val ps = JSONArray()
            t.paredes.forEach { ps.put(JSONObject().put("n", it.nombre).put("c", it.cantidad)) }
            arr.put(JSONObject().put("nombre", t.nombre).put("prefijo", t.prefijo).put("paredes", ps))
        }
        prefs.edit().putString("tipos", arr.toString()).apply()
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
            !enEvento -> pantallaEventos()
            tipoActual < 0 -> pantallaTipos()
            paredActual < 0 -> pantallaTipo(tipoActual)
            else -> pantallaPared(tipoActual, paredActual)
        }
    }

    private fun pantallaEventos() {
        title = "BoilerImage — Eventos"
        val col = columna()
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
            col.addView(fila("$marca$nombre\n$detalle", { abrirEvento(nombre) }) { menuEvento(nombre) })
        }
        col.addView(boton("＋ Nuevo evento") { nuevoEvento() })
        col.addView(espacio(20))
        col.addView(texto("Carpeta en el teléfono:\nAlmacenamiento interno > $raiz", 13f))
        setContentView(scroll(col))
    }

    private fun pantallaTipos() {
        title = evento
        val col = columna()
        col.addView(texto("Tipos de singularidad  (✎ para editar)", 14f, true))

        val conteo = contarFotos()
        tipos.forEachIndexed { i, t ->
            val total = t.paredes.sumOf { it.cantidad }
            val listos = t.paredes.sumOf { p ->
                (1..p.cantidad).count { estado(conteo[carpeta(t, p, it)]) == 2 }
            }
            col.addView(fila("${t.nombre}\n$listos / $total completos",
                { tipoActual = i; render() }) { menuTipo(i) })
        }
        col.addView(boton("＋ Agregar tipo") { agregarTipo() })
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
        col.addView(texto("Gris: sin fotos · Naranjo: incompleto · Verde: general + detalle", 12f))
        col.addView(espacio(8))

        val conteo = contarFotos()
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (n in 1..p.cantidad) {
            val info = conteo[carpeta(t, p, n)]
            val total = info?.let { it.first + it.second } ?: 0
            grid.addView(Button(this).apply {
                text = "%s %02d\n%d 📷".format(t.prefijo, n, total)
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
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proy, sel,
            arrayOf("$raiz/%"), null)?.use { c ->
            while (c.moveToNext()) {
                val ruta = c.getString(0) ?: continue
                val ev = ruta.removePrefix("$raiz/").substringBefore("/")
                if (ev.isEmpty()) continue
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
        enEvento = true; tipoActual = -1; paredActual = -1
        render()
    }

    private fun nuevoEvento() {
        dialogoTexto("Nombre del evento (ej. PGP Santa Fe 2026)", "") { abrirEvento(limpiar(it)) }
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
            contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("$raiz/$nombre/%"))
        } catch (e: Exception) { 0 }
        guardarEventos(eventosGuardados().filter { it != nombre })
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
        AlertDialog.Builder(this)
            .setTitle("%s %02d — %s".format(t.prefijo, n, p.nombre))
            .setMessage("Generales: ${info?.first ?: 0}\nDetalles: ${info?.second ?: 0}\n\n" +
                        "Detalles = ráfaga: la cámara se vuelve a abrir después de cada foto. " +
                        "Presiona Atrás en la cámara para terminar.")
            .setPositiveButton("📷 General") { _, _ -> tomarFoto(ti, pi, n, "general") }
            .setNeutralButton("🔍 Detalles") { _, _ -> tomarFoto(ti, pi, n, "detalle") }
            .setNegativeButton("Cancelar", null)
            .show()
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
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
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
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$raiz/$evento/%")
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proy, sel, args, null)?.use { c ->
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
