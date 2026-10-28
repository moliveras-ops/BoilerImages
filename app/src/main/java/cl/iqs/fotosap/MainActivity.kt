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

/**
 * Fotos AP — ordena fotos de aires primarios por carpeta.
 *
 * Estructura en el teléfono (visible por USB):
 *   Pictures/FotosAP/<Evento>/<Pared>/AP 01/AP01_general_1.jpg
 *                                          /AP01_detalle_1.jpg ...
 */
class MainActivity : AppCompatActivity() {

    private val paredes = listOf("Pared Frontal", "Pared Trasera", "Pared Izquierda", "Pared Derecha")
    private val raiz = "Pictures/FotosAP"

    private val prefs by lazy { getSharedPreferences("fotosap", Context.MODE_PRIVATE) }

    // Estado de navegación: -1 = inicio; >=0 = índice de pared
    private var paredActual = -1

    private val camara = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = prefs.getString("pendUri", null)?.let(Uri::parse)
        val modo = prefs.getString("pendModo", "") ?: ""
        val pared = prefs.getInt("pendPared", -1)
        val ap = prefs.getInt("pendAp", -1)
        prefs.edit().remove("pendUri").apply()

        if (!ok) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
        } else if (modo == "detalle" && pared >= 0 && ap > 0) {
            // Modo ráfaga: vuelve a abrir la cámara hasta que el usuario cancele
            tomarFoto(pared, ap, "detalle")
            return@registerForActivityResult
        }
        if (pared >= 0) paredActual = pared
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paredActual = savedInstanceState?.getInt("pared", -1) ?: -1
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (paredActual >= 0) { paredActual = -1; render() } else finish()
            }
        })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pared", paredActual)
    }

    // ---------- Configuración ----------

    private var evento: String
        get() = prefs.getString("evento", "PGP 2026") ?: "PGP 2026"
        set(v) = prefs.edit().putString("evento", limpiar(v)).apply()

    private fun cantidad(p: Int) = prefs.getInt("cant_$p", 30)
    private fun setCantidad(p: Int, n: Int) = prefs.edit().putInt("cant_$p", n).apply()

    private fun limpiar(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "Evento" }

    private fun carpetaAp(p: Int, ap: Int) =
        "$raiz/$evento/${paredes[p]}/AP %02d/".format(ap)

    // ---------- Pantallas ----------

    private fun render() {
        if (paredActual < 0) pantallaInicio() else pantallaPared(paredActual)
    }

    private fun pantallaInicio() {
        title = "Fotos AP"
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        val col = columna()

        col.addView(texto("Evento", 14f, bold = true))
        col.addView(boton("📁  $evento  (editar)") { editarEvento() })
        col.addView(espacio(24))
        col.addView(texto("Paredes", 14f, bold = true))

        val conteo = contarFotos()
        for (p in paredes.indices) {
            val aps = (1..cantidad(p))
            val completos = aps.count { estado(conteo[carpetaAp(p, it)]) == 2 }
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.addView(boton("${paredes[p]}\n$completos / ${cantidad(p)} AP completos") {
                paredActual = p; render()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(80), 1f) })
            fila.addView(boton("✎") { editarCantidad(p) }
                .apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(80)) })
            col.addView(fila)
        }

        col.addView(espacio(24))
        col.addView(texto(
            "Las fotos quedan en el teléfono en:\nInterno > $raiz/$evento/<Pared>/AP xx\n\n" +
            "Conecta por USB (modo Transferencia de archivos) y copia la carpeta del evento.",
            13f))
        setContentView(scroll(col))
    }

    private fun pantallaPared(p: Int) {
        title = "${paredes[p]} — $evento"
        val col = columna()
        col.addView(texto("Gris: sin fotos · Naranjo: incompleto · Verde: general + detalle", 12f))
        col.addView(espacio(8))

        val conteo = contarFotos()
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (ap in 1..cantidad(p)) {
            val info = conteo[carpetaAp(p, ap)]
            val total = info?.let { it.first + it.second } ?: 0
            val b = Button(this).apply {
                text = "AP %02d\n%d 📷".format(ap, total)
                isAllCaps = false
                setBackgroundColor(when (estado(info)) {
                    2 -> Color.parseColor("#2E7D32")
                    1 -> Color.parseColor("#EF6C00")
                    else -> Color.parseColor("#757575")
                })
                setTextColor(Color.WHITE)
                setOnClickListener { menuAp(p, ap, info) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = dp(72)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            }
            grid.addView(b)
        }
        col.addView(grid)
        col.addView(espacio(16))
        col.addView(boton("← Volver a paredes") { paredActual = -1; render() })
        setContentView(scroll(col))
    }

    private fun menuAp(p: Int, ap: Int, info: Pair<Int, Int>?) {
        val g = info?.first ?: 0
        val d = info?.second ?: 0
        AlertDialog.Builder(this)
            .setTitle("${paredes[p]} — AP %02d".format(ap))
            .setMessage("Generales: $g\nDetalles: $d\n\nDetalle = ráfaga: la cámara se vuelve a abrir " +
                        "después de cada foto. Presiona Atrás en la cámara para terminar.")
            .setPositiveButton("📷 General") { _, _ -> tomarFoto(p, ap, "general") }
            .setNeutralButton("🔍 Detalles") { _, _ -> tomarFoto(p, ap, "detalle") }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Cámara / MediaStore ----------

    private fun tomarFoto(p: Int, ap: Int, modo: String) {
        val carpeta = carpetaAp(p, ap)
        val previas = contarFotos()[carpeta]
        val previasModo = if (modo == "general") previas?.first ?: 0 else previas?.second ?: 0
        val n = previasModo + 1
        val nombre = "AP%02d_%s_%d.jpg".format(ap, modo, n)

        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, carpeta)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
        if (uri == null) {
            Toast.makeText(this, "No se pudo crear el archivo", Toast.LENGTH_LONG).show(); return
        }
        prefs.edit()
            .putString("pendUri", uri.toString())
            .putString("pendModo", modo)
            .putInt("pendPared", p)
            .putInt("pendAp", ap)
            .apply()
        try {
            camara.launch(uri)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "No se encontró app de cámara", Toast.LENGTH_LONG).show()
        }
    }

    /** Devuelve mapa carpeta -> (nº generales, nº detalles) para el evento actual. */
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

    // ---------- Diálogos de edición ----------

    private fun editarEvento() {
        val et = EditText(this).apply { setText(evento); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Nombre del evento")
            .setMessage("Se crea una carpeta nueva por evento. Las fotos de otros eventos no se borran.")
            .setView(conMargen(et))
            .setPositiveButton("Guardar") { _, _ -> evento = et.text.toString(); render() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editarCantidad(p: Int) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cantidad(p).toString()); setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Cantidad de AP — ${paredes[p]}")
            .setView(conMargen(et))
            .setPositiveButton("Guardar") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null && n in 1..200) { setCantidad(p, n); render() }
                else Toast.makeText(this, "Valor entre 1 y 200", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Helpers de UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun columna() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun texto(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun boton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun espacio(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun conMargen(v: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0); addView(v)
    }
}
