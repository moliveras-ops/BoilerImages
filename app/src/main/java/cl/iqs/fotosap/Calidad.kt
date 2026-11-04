package cl.iqs.fotosap

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Revisión de calidad y sello de las fotos de la cámara BoilerImage.
 *
 *  - [revisar]: con una copia reducida (~800 px) mide brillo medio, zonas quemadas y nitidez
 *    (varianza del laplaciano en una grilla 4×4; manda el recuadro más nítido, así una pared lisa
 *    con un solo detalle enfocado no se marca como borrosa). Devuelve el problema o null.
 *  - [sellar]: copia "<foto>_marcada_sello.jpg" con una franja inferior: código · pared, tipo,
 *    evento y fecha/hora. La original no se toca; la copia no cuenta como foto nueva (lleva "_marcada").
 */
object Calidad {
    // Umbrales (luminancia 0–255, imagen de ~800 px de lado mayor)
    private const val OSCURA = 38.0          // brillo medio bajo esto → oscura
    private const val QUEMADA = 0.30         // fracción de píxeles ≥ 250 sobre esto → quemada
    private const val BORROSA = 45.0         // varianza del laplaciano del mejor recuadro bajo esto → borrosa

    fun revisar(cr: ContentResolver, u: Uri): String? {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, u)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val lado = maxOf(info.size.width, info.size.height)
            val f = 800f / lado
            if (f < 1f) dec.setTargetSize(maxOf(1, (info.size.width * f).toInt()), maxOf(1, (info.size.height * f).toInt()))
        }
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val y = IntArray(w * h)
        var suma = 0L; var altas = 0
        for (i in px.indices) {
            val c = px[i]
            val l = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
            y[i] = l; suma += l
            if (l >= 250) altas++
        }
        val media = suma.toDouble() / y.size
        if (media < OSCURA) return "Foto oscura"
        if (altas.toDouble() / y.size > QUEMADA) return "Foto quemada (mucho brillo)"
        // nitidez: varianza del laplaciano por recuadro
        val n = 4
        var mejor = 0.0
        for (ty in 0 until n) for (tx in 0 until n) {
            val x0 = maxOf(1, tx * w / n); val x1 = minOf(w - 1, (tx + 1) * w / n)
            val y0 = maxOf(1, ty * h / n); val y1 = minOf(h - 1, (ty + 1) * h / n)
            var s = 0.0; var s2 = 0.0; var k = 0
            for (yy in y0 until y1) {
                val f = yy * w
                for (xx in x0 until x1) {
                    val i = f + xx
                    val lap = (4 * y[i] - y[i - 1] - y[i + 1] - y[i - w] - y[i + w]).toDouble()
                    s += lap; s2 += lap * lap; k++
                }
            }
            if (k > 0) { val m = s / k; mejor = maxOf(mejor, s2 / k - m * m) }
        }
        return if (mejor < BORROSA) "Posible foto borrosa" else null
    }

    private fun nombreSello(nombre: String) = nombre.substringBeforeLast('.') + "_marcada_sello.jpg"

    private fun buscar(cr: ContentResolver, col: Uri, ruta: String, nombre: String): Uri? = runCatching {
        cr.query(col, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(ruta, nombre), null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(col, c.getLong(0)) else null
        }
    }.getOrNull()

    fun borrarSello(cr: ContentResolver, col: Uri, ruta: String, nombre: String) {
        if (nombre.isEmpty()) return
        buscar(cr, col, ruta, nombreSello(nombre))?.let { runCatching { cr.delete(it, null, null) } }
    }

    /** Evento = carpeta que sigue a "BoilerImage" en la ruta relativa. */
    private fun evento(ruta: String): String {
        val p = ruta.trim('/').split('/')
        val i = p.indexOf("BoilerImage")
        return if (i >= 0) p.getOrNull(i + 1) ?: "" else ""
    }

    fun sellar(cr: ContentResolver, col: Uri, u: Uri, ruta: String, nombre: String, titulo: String, tipo: String): Boolean {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, u)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = true
            val lado = maxOf(info.size.width, info.size.height)
            var m = 1
            while (lado / (m + 1) >= 4000) m++
            if (m > 1) dec.setTargetSampleSize(m)
        }
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val fecha = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
        val linea1 = "$titulo · $tipo"
        val linea2 = listOf(evento(ruta), fecha).filter { it.isNotEmpty() }.joinToString(" · ")
        val base = minOf(w, h)
        val franja = base * 0.11f
        val margen = base * 0.025f
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = franja * 0.36f
        }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5A300"); typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = franja * 0.28f
        }
        // achicar si no cabe en el ancho
        for (p in listOf(p1 to linea1, p2 to linea2)) {
            val ancho = p.first.measureText(p.second)
            if (ancho > w - 2 * margen) p.first.textSize *= (w - 2 * margen) / ancho
        }
        val cv = Canvas(bmp)
        cv.drawRect(0f, h - franja, w, h, Paint().apply { color = Color.argb(170, 0, 0, 0) })
        cv.drawText(linea1, margen, h - franja + franja * 0.46f, p1)
        cv.drawText(linea2, margen, h - franja * 0.16f, p2)

        val nom = nombreSello(nombre)
        buscar(cr, col, ruta, nom)?.let { runCatching { cr.delete(it, null, null) } }
        val salida = cr.insert(col, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nom)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
        }) ?: run { bmp.recycle(); return false }
        val ok = runCatching { cr.openOutputStream(salida)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: false }.getOrDefault(false)
        bmp.recycle()
        if (!ok) runCatching { cr.delete(salida, null, null) }
        return ok
    }
}
