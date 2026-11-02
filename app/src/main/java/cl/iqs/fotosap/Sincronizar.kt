package cl.iqs.fotosap

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File

/**
 * Sincroniza Documents/BoilerImage del teléfono con la carpeta de la nube vinculada (si se llama
 * "BoilerImage" se usa directamente; si no, se crea "BoilerImage" dentro) (OneDrive u otra app que ofrezca carpetas). Copia solo lo nuevo o modificado
 * (todo Documents/BoilerImage: fotos, notas, Excel, CSV, ZIP de informe, respaldo); no borra nada en la nube. Se ejecuta en segundo plano con WorkManager,
 * con conexión a internet, y retoma donde quedó si Android la interrumpe.
 */
class Sincronizar(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        private const val TRABAJO = "sincronizar"
        private const val RAIZ = "Documents/BoilerImage/"
        private const val CARPETA = "BoilerImage"

        /** Programa una sincronización (si hay una corriendo, la siguiente queda en cola). */
        fun programar(ctx: Context) {
            val p = ctx.getSharedPreferences("fotosap", Context.MODE_PRIVATE)
            if (p.getString("nube_tree", null) == null || !p.getBoolean("sync_auto", true)) return
            ahora(ctx)
        }

        fun ahora(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<Sincronizar>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(TRABAJO, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun olvidar(ctx: Context) = File(ctx.filesDir, "sync.json").delete()
    }

    private val prefs = ctx.getSharedPreferences("fotosap", Context.MODE_PRIVATE)
    private val cr = ctx.contentResolver
    private val coleccion: Uri = MediaStore.Files.getContentUri("external")

    override fun doWork(): Result {
        val tree = prefs.getString("nube_tree", null)?.let { Uri.parse(it) } ?: return Result.success()
        if (cr.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) {
            estado("error", "Sin permiso sobre la carpeta vinculada: vuelve a vincularla"); return Result.failure()
        }
        estado("corriendo", "")
        val archManifiesto = File(applicationContext.filesDir, "sync.json")
        val manifiesto = runCatching { JSONObject(archManifiesto.readText()) }.getOrDefault(JSONObject())
        fun guardarManifiesto() = runCatching { archManifiesto.writeText(manifiesto.toString()) }

        data class Arch(val id: Long, val ruta: String, val nombre: String, val firma: String)
        val lista = mutableListOf<Arch>()
        cr.query(coleccion, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$RAIZ%"), null)?.use { c ->
            while (c.moveToNext()) {
                val ruta = c.getString(1) ?: continue
                val nombre = c.getString(2) ?: continue
                if (nombre.startsWith(".")) continue
                // los ZIP de respaldo repiten lo que ya se sincroniza archivo por archivo
                if (ruta.startsWith("${RAIZ}Exportaciones/") && nombre.contains(" respaldo ")) continue
                lista.add(Arch(c.getLong(0), ruta, nombre, "${c.getLong(3)}:${c.getLong(4)}"))
            }
        }
        val pendientes = lista.filter { manifiesto.optString(it.ruta + it.nombre) != it.firma }
        if (pendientes.isEmpty()) { estado("ok", "Todo al día"); return Result.success() }

        val carpetas = HashMap<String, Uri>()     // "Documents/BoilerImage/X/Y/" → documento de la nube
        return try {
            val base = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val nombreBase = cr.query(base, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null } ?: ""
            carpetas[RAIZ] = if (nombreBase.equals(CARPETA, true)) base
                             else hijo(tree, base, CARPETA, true) ?: error("no se pudo crear la carpeta $CARPETA")
            var n = 0
            for (a in pendientes) {
                if (isStopped) { guardarManifiesto(); estado("pausa", "$n de ${pendientes.size} copiados; sigue después"); return Result.retry() }
                val dir = carpeta(tree, a.ruta, carpetas)
                val origen = ContentUris.withAppendedId(coleccion, a.id)
                val destino = hijo(tree, dir, a.nombre, false, mime(a.nombre)) ?: error("no se pudo crear ${a.nombre}")
                cr.openInputStream(origen)?.use { ins ->
                    cr.openOutputStream(destino, "wt")?.use { out -> ins.copyTo(out, 1 shl 16) } ?: error("no se pudo escribir ${a.nombre}")
                } ?: continue   // el archivo ya no existe
                manifiesto.put(a.ruta + a.nombre, a.firma)
                n++
                if (n % 10 == 0) { guardarManifiesto(); estado("corriendo", "$n de ${pendientes.size} archivos") }
            }
            guardarManifiesto()
            prefs.edit().putLong("sync_ultimo", System.currentTimeMillis() / 1000).apply()
            estado("ok", "$n archivo(s) copiados")
            Result.success()
        } catch (e: Exception) {
            guardarManifiesto()
            estado("error", e.message ?: e.javaClass.simpleName)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun estado(e: String, detalle: String) {
        prefs.edit().putString("sync_estado", e).putString("sync_detalle", detalle)
            .putLong("sync_cuando", System.currentTimeMillis() / 1000).apply()
    }

    private fun mime(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "txt" -> "text/plain"; "csv" -> "text/csv"
        "json" -> "application/json"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    /** Busca un hijo por nombre dentro de [padre]; si no existe lo crea (carpeta o archivo). */
    private fun hijo(tree: Uri, padre: Uri, nombre: String, esCarpeta: Boolean, tipo: String = "application/octet-stream"): Uri? {
        val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(padre))
        cr.query(hijos, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == nombre && (c.getString(2) == Document.MIME_TYPE_DIR) == esCarpeta)
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return DocumentsContract.createDocument(cr, padre, if (esCarpeta) Document.MIME_TYPE_DIR else tipo, nombre)
    }

    /** Documento de la nube para una ruta "Documents/BoilerImage/A/B/" (crea las carpetas que falten). */
    private fun carpeta(tree: Uri, ruta: String, cache: HashMap<String, Uri>): Uri {
        cache[ruta]?.let { return it }
        val partes = ruta.removePrefix(RAIZ).trimEnd('/').split('/').filter { it.isNotEmpty() }
        var actual = RAIZ
        var doc = cache[RAIZ]!!
        for (p in partes) {
            actual += "$p/"
            doc = cache[actual] ?: (hijo(tree, doc, p, true) ?: error("no se pudo crear la carpeta $p")).also { cache[actual] = it }
        }
        return doc
    }
}
