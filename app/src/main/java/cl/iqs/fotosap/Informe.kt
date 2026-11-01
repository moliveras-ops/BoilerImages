package cl.iqs.fotosap

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore

/**
 * Fotos marcadas "para informe" (★). Se guardan en un archivo de texto dentro de la misma carpeta
 * de la singularidad ("Para informe.txt", un nombre de foto por línea), así viajan con la carpeta,
 * con el ZIP y se ven desde el PC.
 */
object Informe {
    const val ARCHIVO = "Para informe.txt"
    private val coleccion: Uri = MediaStore.Files.getContentUri("external")

    private fun buscar(cr: ContentResolver, ruta: String): Uri? = runCatching {
        cr.query(coleccion, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(ruta, ARCHIVO), null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(coleccion, c.getLong(0)) else null
        }
    }.getOrNull()

    fun leer(cr: ContentResolver, ruta: String): MutableSet<String> {
        val u = buscar(cr, ruta) ?: return mutableSetOf()
        return runCatching {
            cr.openInputStream(u)?.bufferedReader()?.use { r ->
                r.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            }
        }.getOrNull() ?: mutableSetOf()
    }

    private fun guardar(cr: ContentResolver, ruta: String, nombres: Set<String>): Boolean {
        val u = buscar(cr, ruta)
        if (nombres.isEmpty()) {
            if (u != null) runCatching { cr.delete(u, null, null) }
            return true
        }
        val destino = u ?: runCatching {
            cr.insert(coleccion, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ARCHIVO)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
            })
        }.getOrNull() ?: return false
        return runCatching {
            cr.openOutputStream(destino, "wt")?.use { it.write((nombres.sorted().joinToString("\r\n") + "\r\n").toByteArray()) }
            true
        }.getOrDefault(false)
    }

    fun marcada(cr: ContentResolver, ruta: String, nombre: String) = nombre in leer(cr, ruta)

    /** Cambia la marca y devuelve el estado nuevo (null si no se pudo guardar). */
    fun alternar(cr: ContentResolver, ruta: String, nombre: String): Boolean? {
        val s = leer(cr, ruta)
        val nuevo = !s.remove(nombre)
        if (nuevo) s.add(nombre)
        return if (guardar(cr, ruta, s)) nuevo else null
    }

    fun quitar(cr: ContentResolver, ruta: String, nombre: String) {
        val s = leer(cr, ruta)
        if (s.remove(nombre)) guardar(cr, ruta, s)
    }

    fun renombrar(cr: ContentResolver, ruta: String, viejo: String, nuevo: String) {
        val s = leer(cr, ruta)
        if (s.remove(viejo)) { s.add(nuevo); guardar(cr, ruta, s) }
    }
}
