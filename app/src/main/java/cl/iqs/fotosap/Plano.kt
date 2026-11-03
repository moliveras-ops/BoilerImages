package cl.iqs.fotosap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Hallazgo marcado en un plano: tipo, tubo (1..n, de izquierda a derecha) y elevación en metros.
 * Si [memb] es true el hallazgo está en la membrana entre el tubo [tubo] y el [tubo] + 1.
 */
class Hallazgo(var tipo: String, var tubo: Int, var elev: Double, var desc: String, var memb: Boolean = false,
               /** Nivel: número ("3") o nombre ("EAP", "EAS", "EAT"); "" = sin nivel. */
               var nivel: String = "", var foto: String = "") {
    /** Ubicación para tablas: "T 12" o "M 12–13". */
    fun ubicacion() = if (memb) "M $tubo–${tubo + 1}" else "T $tubo"
}

/** Plano de ubicación de una pared: grilla de tubos × elevaciones con los hallazgos marcados. */
class Plano(var titulo: String, var subtitulo: String, var tubos: Int,
            var elevMin: Double, var elevMax: Double, var paso: Double,
            val hallazgos: MutableList<Hallazgo> = mutableListOf(),
            /** Cota en metros de cada nivel, por nombre (ej. "3" → 12,5; "EAP" → 5,2), ordenados por cota. */
            var niveles: MutableMap<String, Double> = linkedMapOf())

/** Ubicación leída del texto de una foto (ej. "Nivel 3 entre 50-51"). */
class Lectura(val tubo: Int?, val memb: Boolean, val nivel: String?, val elev: Double?, val tipo: String? = null) {
    /** Hay tubo y elevación (por cota o por nivel con cota): se puede cargar sin preguntar. */
    fun completa() = tubo != null && elev != null
}

/**
 * Dibujo de los planos. Todo se dibuja en unidades de una lámina de [W] de ancho; la vista en pantalla
 * y la imagen para el informe solo cambian la escala.
 */
object Planos {
    class TipoH(val clave: String, val nombre: String, val color: Int, val forma: String)

    val tipos = listOf(
        TipoH("adel", "Adelgazamiento", Color.parseColor("#C62828"), "circulo"),
        TipoH("abol", "Abolladura", Color.parseColor("#E08A00"), "triangulo"),
        TipoH("eros", "Erosión", Color.parseColor("#1565C0"), "cuadrado"),
        TipoH("fisu", "Fisura", Color.parseColor("#7B1FA2"), "rombo"),
        TipoH("grie", "Grieta", Color.parseColor("#D81B60"), "rayo"),
        TipoH("repa", "Reparación", Color.parseColor("#2E7D32"), "cruz"),
        TipoH("otro", "Otro", Color.parseColor("#546E7A"), "anillo"))

    fun tipo(k: String) = tipos.firstOrNull { it.clave == k } ?: tipos.last()
    fun codigo(i: Int) = "H-%02d".format(i + 1)
    fun num(v: Double, dec: Int = 1) = String.format(Locale.US, "%.${dec}f", v).replace('.', ',')

    const val W = 1000f
    private const val X0 = 112f
    private const val X1 = 972f
    private const val Y0 = 132f
    private const val Y1 = 1172f
    const val FILA = 40f

    private val tinta = Color.parseColor("#1B2530")
    private val tenue = Color.parseColor("#5D6B78")
    private val acero = Color.parseColor("#2F4A63")

    // ---------- JSON ----------

    fun aJson(l: List<Plano>): String = JSONArray().apply {
        l.forEach { p ->
            put(JSONObject().put("ti", p.titulo).put("su", p.subtitulo).put("n", p.tubos)
                .put("e0", p.elevMin).put("e1", p.elevMax).put("pa", p.paso)
                .put("h", JSONArray().apply {
                    p.hallazgos.forEach { put(JSONObject().put("t", it.tipo).put("u", it.tubo).put("e", it.elev).put("d", it.desc).put("m", it.memb)
                        .put("lv", it.nivel).put("f", it.foto)) }
                })
                .put("nv", JSONObject().apply { p.niveles.forEach { (k, v) -> put("$k", v) } }))
        }
    }.toString()

    fun desdeJson(s: String?): MutableList<Plano> = runCatching {
        val a = JSONArray(s ?: "[]")
        MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            val h = o.optJSONArray("h") ?: JSONArray()
            Plano(o.optString("ti"), o.optString("su"), o.optInt("n", 100),
                o.optDouble("e0", 0.0), o.optDouble("e1", 30.0), o.optDouble("pa", 2.5),
                MutableList(h.length()) { j ->
                    val x = h.getJSONObject(j)
                    Hallazgo(x.optString("t", "otro"), x.optInt("u", 1), x.optDouble("e", 0.0), x.optString("d"), x.optBoolean("m", false),
                        x.optString("lv", "").let { if (it == "0") "" else it }, x.optString("f", ""))
                },
                ordenarNiveles(mutableMapOf<String, Double>().apply {
                    o.optJSONObject("nv")?.let { nv -> nv.keys().forEach { k -> put(k, nv.optDouble(k)) } }
                }))
        }
    }.getOrDefault(mutableListOf())

    // ---------- Niveles ----------

    /** Niveles ordenados de abajo hacia arriba (por cota). */
    fun ordenarNiveles(m: Map<String, Double>): MutableMap<String, Double> =
        m.entries.sortedBy { it.value }.associateTo(linkedMapOf()) { it.key to it.value }

    /** Nombre normalizado de un nivel: "nivel 3" / "n3" → "3"; "eap" → "EAP". */
    fun nombreNivel(s: String): String {
        val t = s.trim().uppercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
        Regex("""^(?:NIVEL|NIV|NV|N)\s*\.?\s*(\d{1,3})$""").find(t)?.let { return it.groupValues[1].trimStart('0').ifEmpty { "0" } }
        return t.replace(" ", "")
    }

    /** Cota del nivel [nv] (sin importar mayúsculas), o null si no está definido. */
    fun cota(p: Plano, nv: String): Double? = nombreNivel(nv).takeIf { it.isNotEmpty() }?.let { p.niveles[it] }

    /** Etiqueta corta del nivel: "N3" si es número, "EAP" si tiene nombre. */
    fun etiquetaNivel(nv: String) = if (nv.isEmpty()) "" else if (nv.all { it.isDigit() }) "N$nv" else nv

    /** Texto editable de los niveles: una línea por nivel, "3 = 12,5" o "EAP = 5,2". */
    fun nivelesTexto(p: Plano) = p.niveles.entries.joinToString("\n") { "${it.key} = ${num(it.value, 2)}" }

    /**
     * Lee los niveles desde el texto ("3 = 12,5", "Nivel 3: 12.5", "EAP = 5,2", "EAS 8,4"); null si una línea
     * no se entiende. El nombre puede ser número o letras/números (hasta 10 caracteres).
     */
    fun leerNiveles(txt: String): MutableMap<String, Double>? {
        val r = mutableMapOf<String, Double>()
        val re = Regex("""^(.{1,16}?)\s*(?:=|:|\s)\s*\+?\s*(-?\d+(?:[.,]\d+)?)\s*m?$""", RegexOption.IGNORE_CASE)
        for (l in txt.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = re.find(l) ?: return null
            val n = nombreNivel(m.groupValues[1])
            if (n.isEmpty() || n.length > 10 || !n.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
            r[n] = m.groupValues[2].replace(',', '.').toDouble()
        }
        return ordenarNiveles(r)
    }

    // ---------- Lectura de la marca escrita en la pared ----------

    /**
     * Interpreta el texto reconocido en la foto: "Nivel 3 entre 50-51", "N3 T50", "nv 2 tubo 14",
     * "50-51 +12,5 m". Dos tubos seguidos = membrana. Corrige confusiones típicas (O→0, l/I→1).
     * null si no se encontró ni tubo, ni nivel, ni cota.
     */
    fun interpretar(p: Plano, texto: String): Lectura? {
        var t = java.text.Normalizer.normalize(texto.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "").replace('\n', ' ').replace('–', '-').replace('—', '-')
        t = Regex("""(?<=\d)o|(?<![a-z])o(?=\d)""").replace(t, "0")
        t = Regex("""(?<=\d)[li|]|(?<![a-z])[li|](?=\d)""").replace(t, "1")
        fun ok(n: Int) = n in 1..p.tubos

        var tubo: Int? = null; var memb = false
        val par = Regex("""\bentre\s*(?:(?:los\s*)?tubos?\s*)?(\d{1,4})\s*(?:-|/|_|y|al|a|\s)\s*(\d{1,4})\b""").find(t)
            ?: Regex("""\bm\s*\.?\s*(\d{1,4})\s*[-/_]\s*(\d{1,4})\b""").find(t)
            ?: Regex("""\b(\d{1,4})\s*[-/_]\s*(\d{1,4})\b""").findAll(t)
                .firstOrNull { Math.abs(it.groupValues[1].toInt() - it.groupValues[2].toInt()) == 1 }
        if (par != null) {
            val a = par.groupValues[1].toInt(); val b = par.groupValues[2].toInt()
            if (ok(a) && ok(b)) { tubo = minOf(a, b); memb = a != b && minOf(a, b) < p.tubos }
        }
        if (tubo == null) Regex("""\b(?:tubo|tb|t)\s*[.#:°º-]?\s*(\d{1,4})\b""").find(t)?.let {
            val n = it.groupValues[1].toInt(); if (ok(n)) tubo = n
        }
        // Nivel con nombre definido en la pared (ej. "EAP 50-51"), o "Nivel 3" / "N3"
        val nombrado = p.niveles.keys.filter { k -> !k.all { it.isDigit() } }.sortedByDescending { it.length }
            .firstOrNull { k -> Regex("""(?<![a-z0-9])${Regex.escape(k.lowercase(Locale.ROOT))}(?![a-z0-9])""").containsMatchIn(t) }
        val nivel = nombrado ?: Regex("""\b(?:nivel|niv|nv|n)\s*[.#:°º-]?\s*(\d{1,2})\b""").find(t)?.groupValues?.get(1)?.toInt()?.toString()
        val cota = Regex("""(?:\bcota|\belev\w*|\+)\s*(-?\d{1,3}(?:[.,]\d{1,2})?)""").find(t)?.groupValues?.get(1)
            ?: Regex("""\b(-?\d{1,3}(?:[.,]\d{1,2})?)\s*(?:m|mt|mts)\b""").find(t)?.groupValues?.get(1)
        val e = cota?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in p.elevMin..p.elevMax }
            ?: nivel?.let { cota(p, it) }
        if (tubo == null && nivel == null && e == null) return null
        // Tipo escrito junto a la marca (ej. "Abolladura N3 50-51"); si no hay, se usa el tipo elegido en el plano
        val tipo = listOf("adel" to "adelg", "abol" to "abol", "eros" to "eros", "fisu" to "fisur",
            "grie" to "griet", "repa" to "repar").firstOrNull { t.contains(it.second) }?.first
        return Lectura(tubo, memb, nivel, e, tipo)
    }

    // ---------- Geometría ----------

    private fun xTubo(p: Plano, t: Int) = X0 + ((t - 0.5f) / p.tubos) * (X1 - X0)
    /** X del hallazgo: centro del tubo, o el borde entre [tubo] y [tubo] + 1 si está en la membrana. */
    private fun xH(p: Plano, h: Hallazgo) = if (h.memb) X0 + (h.tubo.toFloat() / p.tubos) * (X1 - X0) else xTubo(p, h.tubo)

    /** Deja tubo y elevación dentro del plano (en membrana el tubo izquierdo llega hasta n − 1). */
    fun ajustar(p: Plano, h: Hallazgo) {
        if (h.memb && p.tubos < 2) h.memb = false
        h.tubo = h.tubo.coerceIn(1, if (h.memb) p.tubos - 1 else p.tubos)
        if (h.nivel.isNotEmpty()) cota(p, h.nivel)?.let { h.elev = it }
        h.elev = h.elev.coerceIn(p.elevMin, p.elevMax)
    }
    private fun yElev(p: Plano, e: Double) = Y1 - ((e - p.elevMin) / (p.elevMax - p.elevMin)).toFloat() * (Y1 - Y0)

    /**
     * Ubicación propuesta para el punto tocado (elevación redondeada a 0,1 m), o null si está fuera de la
     * grilla. Si el toque cae cerca del borde entre dos tubos se propone la membrana (tubo = el de la izquierda).
     */
    fun ubicar(p: Plano, x: Float, y: Float): Hallazgo? {
        if (x < X0 || x > X1 || y < Y0 || y > Y1) return null
        val pos = (x - X0) / (X1 - X0) * p.tubos              // 0..n; los bordes entre tubos son enteros
        val borde = Math.round(pos)
        val memb = borde in 1 until p.tubos && Math.abs(pos - borde) < 0.25f
        val tubo = if (memb) borde else (pos.toInt() + 1).coerceIn(1, p.tubos)
        val e = p.elevMin + (Y1 - y) / (Y1 - Y0) * (p.elevMax - p.elevMin)
        return Hallazgo("", tubo, Math.round(e * 10) / 10.0, "", memb)
    }

    /** Índice del hallazgo más cercano al punto tocado (radio de 40 unidades), o -1. */
    fun hallazgoEn(p: Plano, x: Float, y: Float): Int {
        var mejor = -1; var dist = 40f
        p.hallazgos.forEachIndexed { i, h ->
            val d = Math.hypot((xH(p, h) - x).toDouble(), (yElev(p, h.elev) - y).toDouble()).toFloat()
            if (d < dist) { dist = d; mejor = i }
        }
        return mejor
    }

    /** Índice del hallazgo cuya fila de la tabla se tocó, o -1. */
    fun filaEn(p: Plano, x: Float, y: Float): Int {
        if (x < X0 || x > X1) return -1
        val i = Math.floor(((y - topTabla(p)) / FILA).toDouble()).toInt() - 1
        return if (i in p.hallazgos.indices) i else -1
    }

    private fun pinta(size: Float, color: Int, negrita: Boolean = false, alin: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = alin
            typeface = Typeface.create(Typeface.SANS_SERIF, if (negrita) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun leyenda(p: Plano): Pair<List<Pair<TipoH, PointF>>, Float> {
        val usados = tipos.filter { t -> p.hallazgos.any { it.tipo == t.clave } }
        val pt = pinta(26f, tinta)
        var x = X0; var y = Y1 + 128f
        val l = mutableListOf<Pair<TipoH, PointF>>()
        for (t in usados) {
            val w = 34f + pt.measureText(t.nombre) + 36f
            if (x + w > X1 && x > X0) { x = X0; y += 44f }
            l.add(t to PointF(x, y)); x += w
        }
        return l to (if (l.isEmpty()) Y1 + 96f else y + 26f)
    }

    /** Y donde empieza la tabla de hallazgos (encabezado); cada fila mide [FILA]. */
    fun topTabla(p: Plano) = leyenda(p).second + 22f

    /** Alto de la lámina en unidades (el ancho es [W]). */
    fun alto(p: Plano) = topTabla(p) + FILA * (p.hallazgos.size + 1) + 40f

    // ---------- Dibujo ----------

    fun marcador(c: Canvas, forma: String, x: Float, y: Float, r: Float, color: Int) {
        val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r * 0.22f }
        fun poli(vararg pts: Float) = Path().apply {
            moveTo(pts[0], pts[1]); var i = 2
            while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
            close()
        }
        when (forma) {
            "circulo" -> { c.drawCircle(x, y, r, relleno); c.drawCircle(x, y, r, borde) }
            "anillo" -> {
                c.drawCircle(x, y, r, Paint(relleno).apply { this.color = Color.WHITE })
                c.drawCircle(x, y, r, Paint(relleno).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.42f })
            }
            "cuadrado" -> { c.drawRect(x - r, y - r, x + r, y + r, relleno); c.drawRect(x - r, y - r, x + r, y + r, borde) }
            "triangulo" -> poli(x, y - r * 1.25f, x + r * 1.15f, y + r * 0.85f, x - r * 1.15f, y + r * 0.85f).let { c.drawPath(it, relleno); c.drawPath(it, borde) }
            "rombo" -> poli(x, y - r * 1.3f, x + r * 1.1f, y, x, y + r * 1.3f, x - r * 1.1f, y).let { c.drawPath(it, relleno); c.drawPath(it, borde) }
            "rayo" -> poli(x + r * 0.35f, y - r * 1.3f, x - r * 0.9f, y + r * 0.15f, x - r * 0.05f, y + r * 0.15f,
                x - r * 0.35f, y + r * 1.3f, x + r * 0.9f, y - r * 0.15f, x + r * 0.05f, y - r * 0.15f)
                .let { c.drawPath(it, relleno); c.drawPath(it, borde) }
            "cruz" -> {
                val l = Paint(relleno).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.55f; strokeCap = Paint.Cap.ROUND }
                c.drawLine(x - r, y - r, x + r, y + r, l); c.drawLine(x - r, y + r, x + r, y - r, l)
            }
        }
    }

    /** Dibuja la lámina completa en unidades de lámina. [sel] resalta un hallazgo (solo en pantalla). */
    fun dibujar(c: Canvas, p: Plano, sel: Int = -1) {
        c.drawColor(Color.WHITE)
        c.drawText(p.titulo, X0, 52f, pinta(40f, tinta, true))
        c.drawText(p.subtitulo, X0, 94f, pinta(27f, tenue))

        // Grilla
        c.drawRect(X0, Y0, X1, Y1, Paint().apply { color = Color.parseColor("#F6F8FA") })
        val tw = (X1 - X0) / p.tubos
        if (tw >= 5f) {
            val lt = Paint().apply { color = Color.parseColor("#DFE5EA"); strokeWidth = 1f }
            for (i in 1 until p.tubos) { val x = X0 + i * tw; c.drawLine(x, Y0, x, Y1, lt) }
        }
        val ejes = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = acero; strokeWidth = 2f }
        val numTubo = pinta(23f, tinta, alin = Paint.Align.CENTER)
        val salto = when { p.tubos <= 20 -> 1; p.tubos <= 50 -> 5; p.tubos <= 150 -> 10; p.tubos <= 300 -> 20; else -> 50 }
        var ultimoX = -1000f
        for (i in 1..p.tubos) if (i == 1 || i % salto == 0) {
            val x = xTubo(p, i)
            if (x - ultimoX < 34f) continue
            c.drawLine(x, Y1, x, Y1 + 9f, ejes); c.drawText("$i", x, Y1 + 36f, numTubo); ultimoX = x
        }
        c.drawText("N° de tubo (izquierda → derecha)", (X0 + X1) / 2, Y1 + 76f, pinta(25f, tenue, alin = Paint.Align.CENTER))

        val lg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C3CDD6"); strokeWidth = 1.4f; pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        val numElev = pinta(23f, tinta, alin = Paint.Align.RIGHT)
        val n = Math.round((p.elevMax - p.elevMin) / p.paso).toInt().coerceAtMost(400)
        var ultimoY = 10000f
        for (j in 0..n) {
            val e = p.elevMin + j * p.paso
            if (e > p.elevMax + 1e-9) break
            val y = yElev(p, e)
            if (j > 0 && e < p.elevMax - 1e-9) c.drawLine(X0, y, X1, y, lg)
            if (ultimoY - y >= 28f) { c.drawText(num(e), X0 - 12f, y + 8f, numElev); ultimoY = y }
        }
        // Niveles definidos en la pared: línea de color con su nombre y cota a la derecha
        val lNv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E7C86"); strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(14f, 6f, 3f, 6f), 0f) }
        val tNv = pinta(19f, Color.parseColor("#0E7C86"), true, Paint.Align.RIGHT)
        val haloNv = Paint(tNv).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
        p.niveles.forEach { (k, v) ->
            if (v < p.elevMin || v > p.elevMax) return@forEach
            val y = yElev(p, v)
            c.drawLine(X0, y, X1, y, lNv)
            val et = "${etiquetaNivel(k)} · ${num(v, 2)}"
            c.drawText(et, X1 - 6f, y - 6f, haloNv); c.drawText(et, X1 - 6f, y - 6f, tNv)
        }
        c.save(); c.rotate(-90f, 30f, (Y0 + Y1) / 2)
        c.drawText("Elevación (m)", 30f, (Y0 + Y1) / 2 + 8f, pinta(25f, tenue, alin = Paint.Align.CENTER))
        c.restore()
        c.drawRect(X0, Y0, X1, Y1, Paint(ejes).apply { style = Paint.Style.STROKE })

        // Hallazgos con etiqueta; si una etiqueta choca con otra se desplaza y se une con una línea
        val etiqueta = pinta(26f, tinta, true)
        val halo = Paint(etiqueta).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f }
        val guia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A97A3"); strokeWidth = 1.5f }
        val puestos = mutableListOf<PointF>()
        p.hallazgos.forEachIndexed { i, h ->
            val t = tipo(h.tipo)
            val x = xH(p, h); val y = yElev(p, h.elev)
            if (i == sel) c.drawCircle(x, y, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F5A300"); style = Paint.Style.STROKE; strokeWidth = 5f })
            marcador(c, t.forma, x, y, 13f, t.color)
            val cod = codigo(i)
            val ancho = etiqueta.measureText(cod)
            val derecha = x + 18f + ancho < X1
            val lx = if (derecha) x + 18f else x - 18f - ancho
            var ly = y - 14f
            var k = 0
            while (k < 10 && puestos.any { Math.abs(it.x - lx) < ancho + 6f && Math.abs(it.y - ly) < 28f }) {
                k++; ly = y - 14f + (if (k % 2 == 1) 1 else -1) * 30f * ((k + 1) / 2)
            }
            puestos.add(PointF(lx, ly))
            if (k > 0) c.drawLine(x, y, if (derecha) lx - 2f else lx + ancho + 2f, ly - 9f, guia)
            c.drawText(cod, lx, ly, halo); c.drawText(cod, lx, ly, etiqueta)
        }

        // Leyenda
        val tl = pinta(26f, tinta)
        for ((t, pt) in leyenda(p).first) {
            marcador(c, t.forma, pt.x + 12f, pt.y - 9f, 11f, t.color)
            c.drawText(t.nombre, pt.x + 32f, pt.y, tl)
        }

        // Tabla de hallazgos
        val top = topTabla(p)
        val cols = floatArrayOf(X0, X0 + 104f, X0 + 320f, X0 + 450f, X0 + 590f, X0 + 670f)
        c.drawRect(X0, top, X1, top + FILA, Paint().apply { color = acero })
        val cab = pinta(23f, Color.WHITE, true)
        listOf("ID", "Tipo", "Ubicación", "Elev. (m)", "Foto", "Descripción").forEachIndexed { i, s -> c.drawText(s, cols[i] + 10f, top + 27f, cab) }
        val celda = pinta(23f, tinta)
        val franja = Paint().apply { color = Color.parseColor("#F1F4F7") }
        p.hallazgos.forEachIndexed { i, h ->
            val y = top + FILA * (i + 1)
            if (i % 2 == 1) c.drawRect(X0, y, X1, y + FILA, franja)
            if (i == sel) c.drawRect(X0, y, X1, y + FILA, Paint().apply { color = Color.parseColor("#FFE3A3") })
            val maxDesc = X1 - cols[5] - 16f
            var d = h.desc.replace('\n', ' ')
            if (celda.measureText(d) > maxDesc) {
                d = d.take(celda.breakText(d, true, maxDesc - celda.measureText("…"), null)).trimEnd() + "…"
            }
            listOf(codigo(i), tipo(h.tipo).nombre, h.ubicacion(), num(h.elev) + (if (h.nivel.isNotEmpty()) " · ${etiquetaNivel(h.nivel)}" else ""), "", d)
                .forEachIndexed { cI, s -> if (s.isNotEmpty()) c.drawText(s, cols[cI] + 10f, y + 27f, celda) }
            // Foto: "Sí" en verde si el hallazgo tiene foto adjunta; "—" si no
            if (h.foto.isNotBlank()) c.drawText("Sí", cols[4] + 10f, y + 27f, pinta(23f, Color.parseColor("#2E7D32"), true))
            else c.drawText("—", cols[4] + 10f, y + 27f, pinta(23f, tenue))
        }
        val fin = top + FILA * (p.hallazgos.size + 1)
        c.drawLine(X0, fin, X1, fin, ejes)
        if (p.hallazgos.isEmpty()) c.drawText("Sin hallazgos", X0 + 10f, fin + 26f, celda.apply { color = tenue })
        else c.drawText("T = tubo · M 12–13 = membrana entre tubos 12 y 13 · N = nivel · Con foto: ${p.hallazgos.count { it.foto.isNotBlank() }} de ${p.hallazgos.size}", X0 + 10f, fin + 26f, pinta(20f, tenue))
    }

    /** Imagen de la lámina para el informe, de [anchoPx] píxeles de ancho. */
    fun imagen(p: Plano, anchoPx: Int = 2000): Bitmap {
        val s = anchoPx / W
        val bmp = Bitmap.createBitmap(anchoPx, (alto(p) * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s)
        dibujar(c, p)
        return bmp
    }
}
