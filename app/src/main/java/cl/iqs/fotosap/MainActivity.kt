package cl.iqs.fotosap

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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

    // Permiso de cámara: la app lo declara (por la medición con RA), así que Android lo exige
    // también para abrir la app de cámara.
    private var trasPermiso: Pair<() -> Unit, () -> Unit>? = null
    private val pedirCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = trasPermiso; trasPermiso = null
        if (ok) f?.first?.invoke()
        else { f?.second?.invoke(); aviso("La app necesita permiso de cámara para tomar fotos") }
    }

    private fun conCamara(siNo: () -> Unit = {}, f: () -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) f()
        else { trasPermiso = f to siNo; pedirCamara.launch(android.Manifest.permission.CAMERA) }
    }

    // Medición con realidad aumentada: el destino se guarda en prefs ("pendRA") por si Android
    // cierra la app mientras está abierta la pantalla de RA.
    private val medirRA = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val d = prefs.getString("pendRA", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        prefs.edit().remove("pendRA").apply()
        if (d == null) return@registerForActivityResult
        if (d.optString("origen") == "terreno") enTerreno = true
        else if (d.optInt("ti") >= 0) { enEvento = true; tipoActual = d.optInt("ti"); paredActual = d.optInt("pi") }
        if (res.resultCode != RESULT_OK || res.data == null) { render(); return@registerForActivityResult }
        val nombre = res.data!!.getStringExtra("nombre") ?: return@registerForActivityResult
        val resumen = res.data!!.getStringExtra("resumen") ?: ""
        val m = JSONObject(d.toString()).put("anotada", nombre).put("tipo", "ra")
            .put("resumen", resumen).put("modo", res.data!!.getStringExtra("modo") ?: "")
            .put("fecha", System.currentTimeMillis()).put("puntos", JSONArray())
        escribirTexto(d.optString("ruta"), "${d.optString("base")}.txt",
            "${d.optString("titulo")}\nMedición con realidad aumentada · ${m.optString("modo")}\n" +
            "Fecha: ${fmtFecha.format(Date())} ${fmtHora.format(Date())}\nFoto: $nombre\n\n${resumen.replace(" · ", "\n")}")
        guardarMediciones(mediciones() + m)
        render()
        verMedicion(m)
    }
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
        android.util.Log.i("BoilerImage", "camara ok=$ok modo=$modo uri=$uri")

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
        capturarErrores()
        supportActionBar?.apply {
            // Logo de BoilerImage junto al título de cada pantalla
            setDisplayShowHomeEnabled(true); setDisplayUseLogoEnabled(true)
            setLogo(android.graphics.drawable.InsetDrawable(logo(34), 0, 0, dp(12), 0))
        }
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
        avisarErrorPrevio()
    }

    // ---------- Registro de errores ----------
    // Si la app se cierra por un error, guarda el detalle. Al abrir de nuevo lo muestra, lo deja en
    // "Error BoilerImage.txt" y pausa la medición en curso para que la app no vuelva a caerse.

    private fun capturarErrores() {
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                val ed = prefs.edit().putString("pendError",
                    "BoilerImage $version · ${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())}\n$sw")
                prefs.getString("pendMed", null)?.let { ed.putString("pendMedPausada", it).remove("pendMed") }
                ed.commit()
            }
            previo?.uncaughtException(hilo, e)
        }
    }

    private fun avisarErrorPrevio() {
        val err = prefs.getString("pendError", null) ?: return
        prefs.edit().remove("pendError").apply()
        escribirTexto("$raiz/", "Error BoilerImage.txt", err)
        val pausada = prefs.getString("pendMedPausada", null)
        val dlg = AlertDialog.Builder(this)
            .setTitle("La app se cerró por un error")
            .setMessage("Quedó guardado en $raiz/Error BoilerImage.txt. Envíaselo a Claude para corregirlo.\n\n" +
                        err.lines().take(6).joinToString("\n") +
                        if (pausada != null) "\n\nHabía una medición en curso." else "")
            .setPositiveButton("Copiar error") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("Error BoilerImage", err))
                aviso("Error copiado: pégalo en el chat")
            }
            .setNegativeButton("Cerrar", null)
        if (pausada != null) {
            dlg.setNeutralButton("Recuperar medición") { _, _ ->
                prefs.edit().putString("pendMed", pausada).remove("pendMedPausada").apply(); render()
            }
            dlg.setOnDismissListener { prefs.edit().remove("pendMedPausada").apply() }
        }
        dlg.show()
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
        title = "BoilerImage"
        val col = columna()
        col.addView(cabecera())
        val activo = eventoActivo()
        val etiqueta = if (activo == null) "📏  Notas en terreno\nElige un evento"
                       else "📏  Notas en terreno\n$activo · ${mediciones().count { it.optString("origen") == "terreno" }} mediciones"
        col.addView(boton(etiqueta, Estilo.PRIMARIO) { abrirTerreno() }.apply { minHeight = dp(76); textSize = 17f })
        col.addView(espacio(16))
        col.addView(texto("Eventos", 14f, true))

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
            val cald = prefs.getString("caldera_$nombre", null)?.let { "\n$it" } ?: ""
            col.addView(fila("📅 $marca$nombre\n$detalle$cald", { abrirEvento(nombre) }) { menuEvento(nombre) })
        }
        col.addView(boton("＋ Nuevo evento", Estilo.ACENTO) { nuevoEvento() })
        col.addView(texto("Configuración", 14f, true))
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
        col.addView(texto("Tipos de singularidad", 14f, true))

        val conteo = contarFotos()
        val notas = todasLasNotas()
        tipos.forEachIndexed { i, t ->
            val total = t.paredes.sumOf { it.cantidad }
            val listos = t.paredes.sumOf { p ->
                (1..p.cantidad).count { estado(conteo[carpeta(t, p, it)]) == 2 }
            }
            col.addView(fila("${t.nombre}\n$listos de $total completas · prefijo ${t.prefijo}",
                { tipoActual = i; render() }, listos to total) { menuTipo(i) })
            val notasTipo = notas.filter { it.tipo == t.nombre }
            if (notasTipo.isNotEmpty()) {
                val ultimas = notasTipo.takeLast(2).joinToString("\n") {
                    "%s %02d: %s".format(it.prefijo, it.num, it.texto.replace("\n", " ").take(50))
                }
                col.addView(texto("${notasTipo.size} nota(s) · ver todas ›\n$ultimas", 12.5f).apply {
                    setPadding(dp(16), dp(2), dp(12), dp(10))
                    setCompoundDrawablesRelative(icono(R.drawable.ic_nota, C.tenue, 18), null, null, null)
                    compoundDrawablePadding = dp(8)
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
        col.addView(texto("Paredes", 14f, true))

        val conteo = contarFotos()
        t.paredes.forEachIndexed { pi, p ->
            val listos = (1..p.cantidad).count { estado(conteo[carpeta(t, p, it)]) == 2 }
            col.addView(fila("${p.nombre}\n$listos de ${p.cantidad} completas",
                { paredActual = pi; render() }, listos to p.cantidad) { menuPared(ti, pi) })
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
        col.addView(leyenda())

        val conteo = contarFotos()
        val conNota = carpetasConNota()
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (n in 1..p.cantidad) {
            val info = conteo[carpeta(t, p, n)]
            val total = info?.let { it.first + it.second } ?: 0
            grid.addView(Button(this).apply {
                val nota = if (carpeta(t, p, n) in conNota) "\ncon nota" else ""
                val codigo = "%s%02d".format(t.prefijo, n)
                val sub = (if (total == 1) "1 foto" else "$total fotos") + nota
                text = android.text.SpannableString("$codigo\n$sub").apply {
                    setSpan(android.text.style.RelativeSizeSpan(0.72f), codigo.length + 1, length, 0)
                }
                isAllCaps = false; typeface = medio; textSize = 15f
                setPadding(dp(2), dp(6), dp(2), dp(6)); maxLines = 3; minWidth = 0; minimumWidth = 0
                stateListAnimator = null; elevation = 0f
                val est = estado(info)
                setTextColor(if (est == 0) C.tinta else Color.WHITE)
                background = when (est) {
                    2 -> fondoRedondeado(C.ok, 12)
                    1 -> fondoRedondeado(C.parcial, 12)
                    else -> fondoRedondeado(C.superficie, 12, C.borde)
                }
                setOnClickListener { menuSingularidad(ti, pi, n, info) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = dp(84)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            })
        }
        col.addView(grid)
        col.addView(espacio(16))
        col.addView(boton("← Volver a paredes") { paredActual = -1; render() })
        setContentView(scroll(col))
    }

    /** Leyenda de colores de la grilla de singularidades. */
    private fun leyenda() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(4), 0, dp(10))
        for ((color, borde, txt) in listOf(Triple(C.superficie, C.borde, "Sin fotos"), Triple(C.parcial, null, "Incompleta"),
                                            Triple(C.ok, null, "Completa"))) {
            addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color); borde?.let { setStroke(dp(1), it) } }
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) }
            })
            addView(TextView(context).apply {
                text = txt; textSize = 12.5f; setTextColor(C.tenue)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) }
            })
        }
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
        val totalFotos = (info?.first ?: 0) + (info?.second ?: 0)
        if (totalFotos > 0) col.addView(boton("🖼  Ver fotos capturadas ($totalFotos)", Estilo.PRIMARIO) {
            dlg.dismiss()
            galeriaFotos("%s %02d — %s".format(t.prefijo, n, p.nombre), carpeta(t, p, n))
        })
        col.addView(boton("📷  Foto general") { dlg.dismiss(); tomarFoto(ti, pi, n, "general") })
        col.addView(boton("🔍  Detalles (ráfaga)") { dlg.dismiss(); tomarFoto(ti, pi, n, "detalle") })
        col.addView(boton("📝  Nota (escribir o dictar)") { dlg.dismiss(); editarNota(ti, pi, n) })
        col.addView(boton("📍  Foto con puntos de espesor") { dlg.dismiss(); medicionSingularidad(ti, pi, n) })
        col.addView(boton("📐  Medir con realidad aumentada") { dlg.dismiss(); medirRASingularidad(ti, pi, n) })
        col.addView(texto("Ráfaga: la cámara se vuelve a abrir después de cada foto. " +
                          "Presiona Atrás en la cámara para terminar.", 12f))
        dlg.show()
    }

    // ---------- Vista previa de fotos ----------

    private class FotoInfo(val uri: Uri, val nombre: String)

    /** Fotos guardadas directamente en la carpeta de la singularidad, ordenadas por tipo y número. */
    private fun fotosDe(ruta: String): MutableList<FotoInfo> {
        val l = mutableListOf<FotoInfo>()
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" + soloImagenes
        runCatching {
            contentResolver.query(coleccion, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                sel, arrayOf(ruta), null)?.use { c ->
                while (c.moveToNext())
                    l.add(FotoInfo(android.content.ContentUris.withAppendedId(coleccion, c.getLong(0)), c.getString(1) ?: ""))
            }
        }
        val orden = listOf("_general", "_detalle", "_espesores", "_RA")
        fun clase(s: String) = orden.indexOfFirst { s.contains(it) }.let { if (it < 0) orden.size else it }
        fun num(s: String) = Regex("_(\\d+)\\.").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        l.sortWith(compareBy({ clase(it.nombre) }, { num(it.nombre) }, { it.nombre }))
        return l
    }

    /** "AP01_detalle_2.jpg" → "Detalle 2" */
    private fun etiquetaFoto(nombre: String): String {
        val s = nombre.substringBeforeLast('.').substringAfter('_').replace('_', ' ')
        return s.replaceFirstChar { it.uppercase() }
    }

    private fun dialogoPantallaCompleta() =
        android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

    private fun barraOscura(titulo: String, cerrar: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(C.primario); setPadding(dp(16), dp(6), dp(4), dp(6))
        addView(TextView(context).apply {
            text = titulo; textSize = 18f; typeface = medio; setTextColor(Color.WHITE); maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(botonIcono(R.drawable.ic_cerrar, Color.WHITE) { cerrar() })
    }

    /** Grilla de miniaturas de las fotos de una singularidad. Tocar una la abre en grande. */
    private fun galeriaFotos(titulo: String, ruta: String) {
        val dlg = dialogoPantallaCompleta()
        val raizV = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.oscuro) }
        raizV.addView(barraOscura(titulo) { dlg.dismiss() })
        val cont = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(16)) }
        raizV.addView(ScrollView(this).apply { addView(cont) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dlg.setContentView(raizV)
        var cambio = false

        fun llenar() {
            cont.removeAllViews()
            val fotos = fotosDe(ruta)
            if (fotos.isEmpty()) {
                cont.addView(TextView(this).apply {
                    text = "Sin fotos"; textSize = 16f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, 0)
                }); return
            }
            val grid = GridLayout(this).apply { columnCount = 3 }
            fotos.forEachIndexed { i, f ->
                val celda = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                }
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.parseColor("#1C2A3A"))
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) }
                    }
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
                    setOnClickListener {
                        visorFotos(fotos, i) { cambio = true; llenar() }
                    }
                }
                celda.addView(iv)
                celda.addView(TextView(this).apply {
                    text = etiquetaFoto(f.nombre); textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(2)); maxLines = 1
                })
                grid.addView(celda)
                Thread {
                    val b = runCatching { contentResolver.loadThumbnail(f.uri, Size(360, 360), null) }.getOrNull()
                    if (b != null) runOnUiThread { iv.setImageBitmap(b) }
                }.start()
            }
            cont.addView(grid)
            cont.addView(TextView(this).apply {
                text = "${fotos.size} foto(s) · toca una para verla en grande"; textSize = 12f
                setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            })
        }
        llenar()
        dlg.setOnDismissListener { if (cambio) render() }
        dlg.show()
    }

    /** Foto en pantalla completa: deslizar o flechas para cambiar, pellizcar o doble toque para zoom. */
    private fun visorFotos(fotos: MutableList<FotoInfo>, inicio: Int, alBorrar: () -> Unit) {
        if (fotos.isEmpty()) return
        var i = inicio.coerceIn(0, fotos.size - 1)
        val dlg = dialogoPantallaCompleta()
        val raizV = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val tituloV = TextView(this)
        val barra = barraOscura("") { dlg.dismiss() }
        (barra.getChildAt(0) as TextView).let { tituloV.layoutParams = it.layoutParams; barra.removeViewAt(0) }
        tituloV.apply { textSize = 17f; typeface = medio; setTextColor(Color.WHITE); maxLines = 2 }
        barra.addView(tituloV, 0)
        raizV.addView(barra)
        lateinit var vista: VistaZoom
        var cargaId = 0

        fun mostrarActual() {
            val f = fotos[i]
            tituloV.text = "${etiquetaFoto(f.nombre)}   ${i + 1} / ${fotos.size}\n${f.nombre}"
            val id = ++cargaId
            vista.poner(null)
            Thread {
                val b = runCatching { cargarBitmap(f.uri, 2400) }.getOrNull()
                runOnUiThread {
                    if (id != cargaId) return@runOnUiThread
                    if (b == null) aviso("No se pudo abrir ${f.nombre}") else vista.poner(b)
                }
            }.start()
        }
        fun mover(d: Int) {
            val j = i + d
            if (j in fotos.indices) { i = j; mostrarActual() }
        }
        vista = VistaZoom({ mover(1) }, { mover(-1) })
        raizV.addView(vista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val acciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(C.oscuro); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun accion(res: Int, txt: String, color: Int = Color.WHITE, f: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            minimumHeight = dp(64)
            background = fondoRedondeado(Color.parseColor("#1C2A3A"), 12)
            isClickable = true; setOnClickListener { f() }
            addView(ImageView(context).apply { setImageDrawable(icono(res, color, 26)) })
            addView(TextView(context).apply { text = txt; textSize = 12.5f; setTextColor(color); gravity = Gravity.CENTER })
            layoutParams = LinearLayout.LayoutParams(0, dp(68), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        acciones.addView(accion(R.drawable.ic_anterior, "Anterior") { mover(-1) })
        acciones.addView(accion(R.drawable.ic_compartir, "Compartir") { compartir(fotos[i].uri) })
        acciones.addView(accion(R.drawable.ic_borrar, "Eliminar", Color.parseColor("#FF8A80")) {
            val f = fotos[i]
            confirmar("¿Eliminar la foto ${f.nombre}?") {
                val ok = runCatching { contentResolver.delete(f.uri, null, null) > 0 }.getOrDefault(false)
                if (!ok) { aviso("No se pudo eliminar la foto"); return@confirmar }
                fotos.removeAt(i)
                alBorrar()
                if (fotos.isEmpty()) dlg.dismiss()
                else { i = i.coerceAtMost(fotos.size - 1); mostrarActual() }
            }
        })
        acciones.addView(accion(R.drawable.ic_siguiente, "Siguiente") { mover(1) })
        raizV.addView(acciones)
        dlg.setContentView(raizV)
        dlg.show()
        mostrarActual()
    }

    /** Imagen con zoom (pellizcar / doble toque), arrastre y deslizamiento lateral para cambiar de foto. */
    private inner class VistaZoom(val siguiente: () -> Unit, val anterior: () -> Unit) : View(this@MainActivity) {
        private var bmp: Bitmap? = null
        private var escala = 1f
        private var dx = 0f
        private var dy = 0f
        private val pintura = Paint(Paint.FILTER_BITMAP_FLAG)
        private val escalador = android.view.ScaleGestureDetector(context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    zoom(d.scaleFactor, d.focusX, d.focusY); return true
                }
            })
        private val gestos = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (escala > 1.05f) { escala = 1f; dx = 0f; dy = 0f; invalidate() } else zoom(2.5f, e.x, e.y)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distX: Float, distY: Float): Boolean {
                if (escala > 1.01f) { dx -= distX; dy -= distY; limitar(); invalidate() }
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (escala > 1.01f || e1 == null) return false
                val ddx = e2.x - e1.x
                if (Math.abs(ddx) > dp(60) && Math.abs(ddx) > Math.abs(e2.y - e1.y)) {
                    if (ddx < 0) siguiente() else anterior(); return true
                }
                return false
            }
        })

        fun poner(b: Bitmap?) { bmp = b; escala = 1f; dx = 0f; dy = 0f; invalidate() }

        /** Rectángulo de la imagen ajustada a la vista (sin zoom). */
        private fun base(): RectF? {
            val b = bmp ?: return null
            if (width == 0 || height == 0) return null
            val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * s; val h = b.height * s
            return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        private fun zoom(f: Float, fx: Float, fy: Float) {
            val nueva = (escala * f).coerceIn(1f, 8f)
            val k = nueva / escala
            // mantiene fijo el punto bajo los dedos (zoom respecto al centro de la vista + desplazamiento)
            val cx = width / 2f; val cy = height / 2f
            dx = (dx - (fx - cx)) * k + (fx - cx)
            dy = (dy - (fy - cy)) * k + (fy - cy)
            escala = nueva
            if (escala <= 1.001f) { dx = 0f; dy = 0f }
            limitar(); invalidate()
        }

        private fun limitar() {
            val r = base() ?: return
            val mx = maxOf(0f, (r.width() * escala - width) / 2)
            val my = maxOf(0f, (r.height() * escala - height) / 2)
            dx = dx.coerceIn(-mx, mx); dy = dy.coerceIn(-my, my)
        }

        override fun onDraw(c: Canvas) {
            val b = bmp
            if (b == null) {
                pintura.color = Color.LTGRAY; pintura.textSize = dp(16).toFloat(); pintura.textAlign = Paint.Align.CENTER
                c.drawText("Cargando…", width / 2f, height / 2f, pintura); return
            }
            val r = base() ?: return
            val cx = width / 2f; val cy = height / 2f
            c.save()
            c.translate(dx, dy)
            c.scale(escala, escala, cx, cy)
            c.drawBitmap(b, null, r, pintura)
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            escalador.onTouchEvent(e)
            if (!escalador.isInProgress) gestos.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick(): Boolean = super.performClick()
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
        col.addView(boton("📍  Nueva medición de espesores", Estilo.PRIMARIO) { medicionTerreno() }.apply {
            minHeight = dp(76); textSize = 17f
        })
        col.addView(boton("📐  Medir con realidad aumentada\nParche · distancia a referencia · tramo de tubo") { medirRATerreno() }.apply {
            minHeight = dp(72)
        })
        col.addView(texto("1. Foto de la zona   2. Toca cada punto medido   3. Valor con foto del medidor, " +
                          "escrito o dictado   4. Guardar: queda la foto con el espesor en cada punto.", 12f))
        col.addView(espacio(8))

        val meds = mediciones().filter { it.optString("origen") == "terreno" }.sortedByDescending { it.optLong("fecha") }
        col.addView(texto("Mediciones (${meds.size})", 14f, true))
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
        if (s == null) {
            prefs.edit().remove("pendMed").apply(); cacheMed = null; moviendo = -1
        }
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
        val fallar = {
            runCatching { contentResolver.delete(uri, null, null) }
            if (modo == "medBase") { val s = sesionMed(); guardarSesionMed(null); volverDeMedicion(s) }
        }
        conCamara(siNo = { fallar() }) {
            try { camara.launch(uri) }
            catch (e: Exception) { fallar(); aviso("No se encontró app de cámara") }
        }
    }

    private fun medicionTerreno() {
        val usados = mediciones().map { it.optString("base") }.toSet()
        var k = libre(carpetaTerreno, "Medicion_%02d.jpg")
        while ("Medicion_%02d".format(k) in usados) k++
        iniciarMedicion("terreno", carpetaTerreno, carpetaTerreno + "Originales/",
            "Medicion_%02d".format(k), "$evento — Medición %02d".format(k))
    }

    private fun medirConRA(origen: String, ruta: String, base: String, titulo: String, ti: Int = -1, pi: Int = -1, n: Int = -1) {
        val d = JSONObject().put("origen", origen).put("ruta", ruta).put("extra", ruta).put("base", base)
            .put("titulo", titulo).put("ti", ti).put("pi", pi).put("n", n)
        prefs.edit().putString("pendRA", d.toString()).apply()
        medirRA.launch(Intent(this, MedirRAActivity::class.java)
            .putExtra("ruta", ruta).putExtra("nombre", "$base.jpg").putExtra("titulo", titulo))
    }

    private fun medirRATerreno() {
        val usados = mediciones().map { it.optString("base") }.toSet()
        var k = libre(carpetaTerreno, "RA_%02d.jpg")
        while ("RA_%02d".format(k) in usados) k++
        medirConRA("terreno", carpetaTerreno, "RA_%02d".format(k), "$evento — Medición RA %02d".format(k))
    }

    private fun medirRASingularidad(ti: Int, pi: Int, n: Int) {
        val t = tipos[ti]; val p = t.paredes[pi]
        val ruta = carpeta(t, p, n)
        val pre = "%s%02d_RA_".format(t.prefijo, n)
        val k = libre(ruta, "$pre%d.jpg")
        medirConRA("sing", ruta, "$pre$k", "$evento — %s %02d — %s".format(t.prefijo, n, p.nombre), ti, pi, n)
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
        menuGrande("Punto P${puntosDe(s).size + 1} — ¿cómo registras el valor?", listOf(
            "📷  Foto del medidor (lee el valor)" to { prepararPunto(x, y); fotoMedidor() },
            "⌨️  Escribir o dictar el valor" to { prepararPunto(x, y); dialogoPunto(null, "", "", -1) }))
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

        // Acciones como botones grandes dentro de la ventana (no se cortan y sirven con guantes)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Punto $etiqueta")
            .setView(scroll(col))
            .setCancelable(false)
            .setOnDismissListener { notaEditando = null }
            .create()
        col.addView(espacio(6))
        col.addView(boton(if (existente == null) "✓  Agregar punto" else "✓  Guardar", Estilo.ACENTO) {
            dlg.dismiss()
            val s2 = sesionMed() ?: return@boton
            val l = puntosDe(s2)
            val v = etValor.text.toString().trim().replace(',', '.')
            val nt = etNota.text.toString().trim()
            if (existente != null && indice in l.indices) { l[indice].valor = v; l[indice].nota = nt }
            else l.add(Punto(s2.optDouble("px").toFloat(), s2.optDouble("py").toFloat(), v, nt,
                             uriMedidor?.toString() ?: "", s2.optInt("pnum")))
            ponerPuntos(s2, l); guardarSesionMed(s2); render()
            if (v.isBlank()) aviso("Punto agregado sin valor: tócalo para completarlo")
        }.apply { minHeight = dp(68); textSize = 18f })
        val fila2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (existente == null) fila2.addView(boton(if (uriMedidor != null) "📷  Repetir foto" else "📷  Foto") {
            dlg.dismiss()
            uriMedidor?.let { runCatching { contentResolver.delete(it, null, null) } }
            fotoMedidor()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) } })
        fila2.addView(boton(if (existente == null) "✕  Descartar" else "✕  Cancelar", Estilo.PELIGRO) {
            dlg.dismiss()
            if (existente == null) uriMedidor?.let { runCatching { contentResolver.delete(it, null, null) } }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        for (k in 0 until fila2.childCount) (fila2.getChildAt(k) as Button).apply { textSize = 15f; maxLines = 1; setPadding(dp(12), 0, dp(12), 0) }
        col.addView(fila2)
        dlg.show()
    }

    private fun menuPunto(i: Int) {
        val s = sesionMed() ?: return
        val p = puntosDe(s).getOrNull(i) ?: return
        menuGrande("Punto P${i + 1}: ${textoValor(p.valor)}", listOf(
            "✏️  Corregir valor o nota" to { dialogoPunto(p.foto.takeIf { it.isNotBlank() }?.let(Uri::parse), p.valor, "", i) },
            "✋  Mover punto" to { moviendo = i; render(); aviso("Toca la nueva posición de P${i + 1}") },
            "🗑  Eliminar punto" to {
                confirmar("¿Eliminar el punto P${i + 1}?") {
                    val s2 = sesionMed() ?: return@confirmar
                    val l = puntosDe(s2)
                    if (i in l.indices) {
                        // La foto del medidor se borra al guardar (por si se cancela la edición)
                        if (l[i].foto.isNotBlank()) s2.put("borrar", (s2.optJSONArray("borrar") ?: JSONArray()).put(l[i].foto))
                        l.removeAt(i)
                    }
                    ponerPuntos(s2, l); guardarSesionMed(s2); render()
                }
            }))
    }

    /** Menú con botones grandes (cómodos con guantes). */
    private fun menuGrande(titulo: String, opciones: List<Pair<String, () -> Unit>>) {
        val col = columna()
        val dlg = AlertDialog.Builder(this).setTitle(titulo).setView(scroll(col))
            .setNegativeButton("Cancelar", null).create()
        opciones.forEach { (t, f) ->
            col.addView(boton(t, if (t.startsWith("🗑")) Estilo.PELIGRO else Estilo.SECUNDARIO) { dlg.dismiss(); f() }.apply {
                minHeight = dp(72); textSize = 18f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(4), 0, dp(4)) }
            })
        }
        mostrar(dlg)
    }

    /** Muestra el diálogo con botones inferiores más grandes. */
    private fun mostrar(dlg: AlertDialog): AlertDialog {
        dlg.show()
        for (b in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dlg.getButton(b)?.apply { textSize = 17f; minHeight = dp(60); minWidth = dp(88) }
        }
        return dlg
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
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(C.oscuro)
        }
        col.addView(texto(when {
            moviendo >= 0 -> "Toca la nueva posición de P${moviendo + 1}"
            puntos.isEmpty() -> "Toca en la foto el punto donde mediste."
            else -> "Toca un lugar libre = nuevo punto · Toca un punto = menú · Arrastra un punto = moverlo"
        }, 15f, true).apply { setTextColor(if (moviendo >= 0) C.acento else Color.WHITE); setPadding(dp(4), dp(4), dp(4), dp(8)) })
        if (bmp != null) {
            col.addView(VistaPuntos(bmp, puntos).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
        } else {
            col.addView(texto("Esperando la foto de la zona…", 14f).apply {
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
        }
        val resumen = if (puntos.isEmpty()) "Sin puntos todavía"
            else puntos.mapIndexed { i, p -> "P${i + 1}: ${textoValor(p.valor)}" }.joinToString(" · ")
        col.addView(texto(resumen, 15f, true).apply { setTextColor(Color.parseColor("#E4EBF1")); setPadding(dp(4), dp(8), dp(4), dp(6)) })
        val filaBotones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filaBotones.addView(boton(if (moviendo >= 0) "✕ Cancelar mover" else "✕ Salir", Estilo.SECUNDARIO) {
            if (moviendo >= 0) { moviendo = -1; render() } else descartarMedicion()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(6) }
            textSize = 18f
        })
        filaBotones.addView(boton("💾 Guardar (${puntos.size})", Estilo.ACENTO) { guardarMedicion() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f)
            textSize = 18f
        })
        col.addView(filaBotones)
        setContentView(col)
    }

    private fun textoValor(v: String) = if (v.isBlank()) "—" else "${v.replace('.', ',')} mm"

    /**
     * Foto con los puntos. Pensada para usar con guantes: zona de toque grande alrededor de cada
     * punto y de su etiqueta. Tocar un lugar libre agrega un punto; tocar un punto abre su menú;
     * mantener y arrastrar un punto lo mueve (aparece una lupa para ubicarlo con precisión).
     */
    private inner class VistaPuntos(val bmp: Bitmap, val puntos: MutableList<Punto>) : View(this@MainActivity) {
        private val dst = RectF()
        private var cajas: List<RectF> = emptyList()
        private var arrastre = -1
        private var movido = false
        private var x0 = 0f
        private var y0 = 0f

        override fun onDraw(c: Canvas) {
            val esc = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val w = bmp.width * esc; val h = bmp.height * esc
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawBitmap(bmp, null, dst, null)
            cajas = dibujarPuntos(c, dst, puntos, 1.3f, if (moviendo >= 0) moviendo else arrastre)
            if (arrastre >= 0 && movido) lupa(c, puntos[arrastre])
        }

        /** Lupa en la esquina superior contraria al dedo, con mira en el punto que se mueve. */
        private fun lupa(c: Canvas, p: Punto) {
            val rad = dp(75).toFloat()
            val px = dst.left + p.x * dst.width()
            val cx = if (px < width / 2f) width - rad - dp(8) else rad + dp(8)
            val cy = rad + dp(8)
            val zoom = 2.5f
            val escala = dst.width() / bmp.width
            val medio = rad / (escala * zoom)
            val bx = p.x * bmp.width; val by = p.y * bmp.height
            val src = android.graphics.Rect((bx - medio).toInt(), (by - medio).toInt(), (bx + medio).toInt(), (by + medio).toInt())
            c.save()
            val camino = android.graphics.Path().apply { addCircle(cx, cy, rad, android.graphics.Path.Direction.CW) }
            c.clipPath(camino)
            c.drawColor(Color.BLACK)
            c.drawBitmap(bmp, src, RectF(cx - rad, cy - rad, cx + rad, cy + rad), null)
            c.restore()
            val pz = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = Color.parseColor("#FFEB3B") }
            c.drawCircle(cx, cy, rad, pz)
            pz.strokeWidth = dp(2).toFloat()
            c.drawLine(cx - dp(18), cy, cx - dp(5), cy, pz); c.drawLine(cx + dp(5), cy, cx + dp(18), cy, pz)
            c.drawLine(cx, cy - dp(18), cx, cy - dp(5), pz); c.drawLine(cx, cy + dp(5), cx, cy + dp(18), pz)
        }

        private fun puntoEn(x: Float, y: Float): Int {
            val radio = dp(44).toFloat()
            var mejor = -1; var dMin = Double.MAX_VALUE
            puntos.forEachIndexed { i, p ->
                val d = Math.hypot((dst.left + p.x * dst.width() - x).toDouble(), (dst.top + p.y * dst.height() - y).toDouble())
                if (d < radio && d < dMin) { dMin = d; mejor = i }
            }
            if (mejor >= 0) return mejor
            return cajas.indexOfFirst { RectF(it).apply { inset(-dp(10).toFloat(), -dp(10).toFloat()) }.contains(x, y) }
        }

        private fun aImagen(x: Float, y: Float) =
            ((x - dst.left) / dst.width()).coerceIn(0f, 1f) to ((y - dst.top) / dst.height()).coerceIn(0f, 1f)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; movido = false
                    arrastre = if (moviendo >= 0) -1 else puntoEn(e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> if (arrastre >= 0) {
                    if (!movido && Math.hypot((e.x - x0).toDouble(), (e.y - y0).toDouble()) > dp(14)) movido = true
                    if (movido) {
                        // El punto mantiene su distancia al dedo, para que el guante no lo tape
                        val (nx, ny) = aImagen(e.x, e.y)
                        puntos[arrastre].x = nx; puntos[arrastre].y = ny
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    performClick()
                    val i = arrastre
                    val fueMovido = movido
                    arrastre = -1
                    // Cambiar la pantalla dentro del evento táctil cierra la app: se hace al terminar
                    val ex = e.x; val ey = e.y
                    post { alSoltar(i, fueMovido, ex, ey) }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { arrastre = -1; post { render() } }
            }
            return true
        }

        private fun alSoltar(i: Int, movido: Boolean, x: Float, y: Float) {
            val (nx, ny) = aImagen(x, y)
            when {
                i >= 0 && movido -> moverPunto(i, puntos[i].x, puntos[i].y)
                i >= 0 -> menuPunto(i)
                moviendo >= 0 && dst.contains(x, y) -> { val m = moviendo; moviendo = -1; moverPunto(m, nx, ny) }
                dst.contains(x, y) -> nuevoPunto(nx, ny)
            }
        }

        override fun performClick(): Boolean = super.performClick()
    }

    /** Punto que se está reubicando desde el menú ("Mover punto"): el próximo toque lo coloca. */
    private var moviendo = -1

    private fun moverPunto(i: Int, x: Float, y: Float) {
        val s = sesionMed() ?: return
        val l = puntosDe(s)
        if (i in l.indices) { l[i].x = x; l[i].y = y }
        ponerPuntos(s, l); guardarSesionMed(s)
        aviso("P${i + 1} movido")
        render()
    }

    /**
     * Dibuja cada punto (círculo con mira) y una etiqueta "P1: 12,45 mm". [escala] agranda las marcas
     * en pantalla; [resaltado] dibuja ese punto en naranjo. Devuelve los rectángulos de las etiquetas.
     */
    private fun dibujarPuntos(c: Canvas, r: RectF, puntos: List<Punto>, escala: Float = 1f, resaltado: Int = -1): List<RectF> {
        val cajas = mutableListOf<RectF>()
        val u = minOf(r.width(), r.height()) / 360f * escala
        val amarillo = Color.parseColor("#FFEB3B")
        val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * u; color = amarillo }
        val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * u; color = Color.BLACK }
        val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * u; typeface = Typeface.DEFAULT_BOLD }
        val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 0, 0, 0) }
        val fm = letra.fontMetrics
        val naranjo = Color.parseColor("#FF6D00")
        puntos.forEachIndexed { i, p ->
            linea.color = if (i == resaltado) naranjo else amarillo
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
            cajas.add(caja)
        }
        return cajas
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

    private fun esRA(m: JSONObject) = m.optString("tipo") == "ra"

    private fun nombreMedicion(m: JSONObject) =
        if (esRA(m)) "Medición RA " + m.optString("base").substringAfterLast('_')
        else m.optString("base").replace('_', ' ').replace("Medicion", "Medición")

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
        if (m.optString("origen") == "terreno" && !esRA(m)) dlg.setNegativeButton("Editar puntos") { _, _ -> editarMedicion(m) }
        dlg.show()
    }

    private fun compartir(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, "Compartir foto")) }
            .onFailure { aviso("No hay app para compartir") }
    }

    private fun menuMedicion(m: JSONObject) {
        val opciones = mutableListOf<Pair<String, () -> Unit>>("🖼  Ver foto" to { verMedicion(m) })
        if (!esRA(m)) opciones.add("✏️  Editar puntos" to { editarMedicion(m) })
        opciones.add("🗑  Eliminar medición" to { confirmar("¿Eliminar ${nombreMedicion(m)} y su foto?") { eliminarMedicion(m) } })
        menuGrande(nombreMedicion(m), opciones)
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
        if (esRA(m)) escribirTexto(m.optString("ruta"), "${m.optString("base")}.txt", "")
        else if (m.optString("origen") == "terreno") {
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
            background = fondoRedondeado(C.superficie, 16, C.borde)
            setPadding(dp(10), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, dp(4)) }
            setOnClickListener { verMedicion(m) }
            setOnLongClickListener { menuMedicion(m); true }
        }
        uriAnotada(m)?.let { u -> runCatching { contentResolver.loadThumbnail(u, Size(300, 300), null) }.getOrNull() }
            ?.let { bmp ->
                fila.addView(ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) }
                    }
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(12) }
                })
            }
        val puntos = puntosDe(m)
        val f = Date(m.optLong("fecha"))
        val valores = puntos.mapIndexed { i, p -> "P${i + 1} ${textoValor(p.valor)}" }.joinToString(" · ")
        val encabezado = if (esRA(m)) "${nombreMedicion(m)} · ${m.optString("modo")}" else "${nombreMedicion(m)} · ${puntos.size} punto(s)"
        val detalle = if (esRA(m)) m.optString("resumen").replace("\n", " · ") else valores
        fila.addView(texto("$encabezado\n${fmtFecha.format(f)} ${fmtHora.format(f)}\n$detalle", 13f)
            .apply {
                setTextColor(C.tinta)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        fila.addView(botonIcono(R.drawable.ic_editar) { menuMedicion(m) })
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
        conCamara(siNo = { runCatching { contentResolver.delete(uri, null, null) } }) {
            try {
                camara.launch(uri)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                aviso("No se encontró app de cámara")
            }
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
        mostrar(AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sí") { _, _ -> ok() }
            .setNegativeButton("No", null)
            .create())
    }

    private fun aviso(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Helpers de UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Ícono de la app (el mismo del teléfono) como imagen de [tamDp]. */
    private fun logo(tamDp: Int): android.graphics.drawable.Drawable {
        val px = dp(tamDp)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(android.graphics.Path().apply {
            addRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), px * 0.28f, px * 0.28f, android.graphics.Path.Direction.CW)
        })
        getDrawable(R.mipmap.ic_launcher)?.apply { setBounds(0, 0, px, px); draw(c) }
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    /** Cabecera de la pantalla principal con el ícono y el nombre de la app. */
    private fun cabecera() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(6), dp(4), dp(14))
        addView(ImageView(context).apply {
            setImageDrawable(logo(64))
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(14) }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "BoilerImage"; textSize = 24f; typeface = medio; setTextColor(C.primario) })
            addView(TextView(context).apply {
                val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
                text = "Fotos, notas y espesores de inspección · v$v"; textSize = 13f; setTextColor(C.tenue)
            })
        })
    }

    // ---------- Estilo visual ----------
    // Claro y de alto contraste para leer al sol; azul industrial con ámbar de acento;
    // controles grandes para usar con guantes.

    private object C {
        val fondo = Color.parseColor("#EEF1F5")
        val superficie = Color.WHITE
        val borde = Color.parseColor("#D3DAE3")
        val tinta = Color.parseColor("#0F1B2A")
        val tenue = Color.parseColor("#5B6B7C")
        val primario = Color.parseColor("#0E3A5F")
        val primarioSuave = Color.parseColor("#E3ECF5")
        val acento = Color.parseColor("#F5A300")
        val ok = Color.parseColor("#1E8E5A")
        val parcial = Color.parseColor("#D97706")
        val vacio = Color.parseColor("#8A96A3")
        val peligro = Color.parseColor("#C62828")
        val oscuro = Color.parseColor("#0B1520")
    }

    enum class Estilo { PRIMARIO, ACENTO, SECUNDARIO, PELIGRO, PLANO }

    private val medio: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Fondo redondeado con efecto al tocar. */
    private fun fondoRedondeado(color: Int, radio: Int = 14, borde: Int? = null, ancho: Int = 1): android.graphics.drawable.Drawable {
        val forma = GradientDrawable().apply {
            cornerRadius = dp(radio).toFloat(); setColor(color); borde?.let { setStroke(dp(ancho), it) }
        }
        val mascara = GradientDrawable().apply { cornerRadius = dp(radio).toFloat(); setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(36, 14, 58, 95)), forma, mascara)
    }

    private val iconos = listOf(
        "📷" to R.drawable.ic_camara, "🔍" to R.drawable.ic_detalle, "📝" to R.drawable.ic_nota,
        "📍" to R.drawable.ic_punto, "📏" to R.drawable.ic_regla, "🏭" to R.drawable.ic_planta,
        "💾" to R.drawable.ic_guardar, "📊" to R.drawable.ic_tabla, "🎤" to R.drawable.ic_microfono,
        "✏️" to R.drawable.ic_editar, "✏" to R.drawable.ic_editar, "✎" to R.drawable.ic_editar,
        "✋" to R.drawable.ic_mover, "🗑" to R.drawable.ic_borrar, "🖼" to R.drawable.ic_imagen,
        "⌨️" to R.drawable.ic_teclado, "⌨" to R.drawable.ic_teclado, "←" to R.drawable.ic_volver,
        "＋" to R.drawable.ic_agregar, "✕" to R.drawable.ic_cerrar, "✓" to R.drawable.ic_listo,
        "🔥" to R.drawable.ic_caldera, "📅" to R.drawable.ic_evento, "📐" to R.drawable.ic_regla)

    /** Separa un emoji inicial del texto: (ícono, texto limpio). */
    private fun separarIcono(s: String): Pair<Int?, String> {
        val t = s.trimStart()
        for ((e, ic) in iconos) if (t.startsWith(e)) return ic to t.removePrefix(e).trimStart('\uFE0F', ' ')
        return null to s
    }

    private fun icono(res: Int, color: Int, tam: Int = 22) =
        getDrawable(res)?.mutate()?.apply { setTint(color); setBounds(0, 0, dp(tam), dp(tam)) }

    /** Ícono dentro de un cuadrado suave (para tarjetas). */
    private fun insignia(res: Int, fondo: Int = C.primarioSuave, color: Int = C.primario) = ImageView(this).apply {
        setImageDrawable(icono(res, color, 24))
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(fondo) }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) }
    }

    private fun columna() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(20))
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v); isFillViewport = true }

    /** Texto. En negrita y 14 sp se usa como encabezado de sección. */
    private fun texto(s: String, size: Float, negrita: Boolean = false) = TextView(this).apply {
        text = s
        if (negrita && size == 14f) {
            text = s.replace(Regex("\\s*\\(✎[^)]*\\)"), "").uppercase(Locale.getDefault()); textSize = 12.5f; letterSpacing = 0.08f
            typeface = medio; setTextColor(C.tenue); setPadding(dp(2), dp(16), 0, dp(6))
        } else {
            textSize = size
            if (negrita) typeface = medio
            setTextColor(if (negrita || size >= 14f) C.tinta else C.tenue)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun boton(s: String, estilo: Estilo = Estilo.SECUNDARIO, onClick: () -> Unit) = Button(this).apply {
        val (ic, t) = separarIcono(s)
        text = t; isAllCaps = false
        gravity = if (ic != null) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        textSize = 16f; minHeight = dp(60); minimumHeight = dp(60)
        typeface = medio; letterSpacing = 0.01f
        stateListAnimator = null; elevation = 0f
        setPadding(dp(18), dp(10), dp(18), dp(10))
        val (bg, fg) = when (estilo) {
            Estilo.PRIMARIO -> C.primario to Color.WHITE
            Estilo.ACENTO -> C.acento to C.tinta
            Estilo.SECUNDARIO -> C.superficie to C.primario
            Estilo.PELIGRO -> C.superficie to C.peligro
            Estilo.PLANO -> Color.TRANSPARENT to C.primario
        }
        val borde = when (estilo) {
            Estilo.SECUNDARIO -> C.borde
            Estilo.PELIGRO -> Color.parseColor("#F0C4C4")
            else -> null
        }
        background = fondoRedondeado(bg, 14, borde)
        setTextColor(fg)
        ic?.let { r -> setCompoundDrawablesRelative(icono(r, fg), null, null, null); compoundDrawablePadding = dp(12) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(5), 0, dp(5)) }
    }

    /** Botón redondo solo con ícono (editar, etc.). */
    private fun botonIcono(res: Int, color: Int = C.tenue, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(icono(res, color, 24))
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 14, 58, 95)), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        isClickable = true; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
    }

    /**
     * Tarjeta de lista: ícono, título (primera línea), detalle (resto) y botón de editar.
     * "● " al inicio marca el elemento activo. [progreso] dibuja una barra (hechos, total).
     */
    private fun fila(s: String, onClick: () -> Unit, progreso: Pair<Int, Int>? = null, onEditar: () -> Unit): View {
        var (ic, t) = separarIcono(s)
        val activo = t.startsWith("● ")
        if (activo) t = t.removePrefix("● ")
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = fondoRedondeado(C.superficie, 16, if (activo) C.primario else C.borde, if (activo) 2 else 1)
            minimumHeight = dp(84)
            setPadding(dp(16), dp(12), dp(6), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(5), 0, dp(5)) }
            isClickable = true; setOnClickListener { onClick() }
        }
        ic?.let { tarjeta.addView(insignia(it)) }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fila1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fila1.addView(TextView(this).apply {
            text = t.substringBefore('\n'); textSize = 17f; typeface = medio; setTextColor(C.tinta)
        })
        if (activo) fila1.addView(TextView(this).apply {
            text = "ACTIVO"; textSize = 10.5f; typeface = medio; letterSpacing = 0.08f; setTextColor(C.primario)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(C.primarioSuave) }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) }
        })
        textos.addView(fila1)
        val detalle = t.substringAfter('\n', "")
        if (detalle.isNotBlank()) textos.addView(TextView(this).apply {
            text = detalle; textSize = 13.5f; setTextColor(C.tenue); setLineSpacing(0f, 1.1f)
            setPadding(0, dp(3), 0, 0)
        })
        progreso?.let { (hechos, total) -> textos.addView(barra(hechos, total)) }
        tarjeta.addView(textos)
        tarjeta.addView(botonIcono(R.drawable.ic_editar) { onEditar() })
        return tarjeta
    }

    /** Barra de avance delgada (verde al completar). */
    private fun barra(hechos: Int, total: Int) = FrameLayout(this).apply {
        val f = if (total > 0) hechos.toFloat() / total else 0f
        background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(C.primarioSuave) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8); marginEnd = dp(8) }
        addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(if (f >= 1f) C.ok else C.primario) }
        }, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val hijo = (v as FrameLayout).getChildAt(0)
            val w = ((r - l) * f).toInt()
            if (hijo.layoutParams.width != w) post { hijo.layoutParams = hijo.layoutParams.apply { width = w }; hijo.requestLayout() }
        }
    }

    private fun espacio(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun conMargen(v: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0); addView(v)
    }
}
