package cl.iqs.fotosap

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

/** Batería y espacio libre del teléfono, para avisar antes de entrar al hogar. */
object Equipo {
    const val BATERIA_BAJA = 25          // %
    const val ESPACIO_BAJO = 2L shl 30   // 2 GB
    private const val MB_POR_FOTO = 5L   // foto de 12 MP ≈ 3–5 MB

    fun bateria(ctx: Context): Int? = runCatching {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()

    fun cargando(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    }.getOrDefault(false)

    fun libre(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun textoEspacio(b: Long): String =
        if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()).replace('.', ',') else "${b shr 20} MB"

    /** "batería 18 % · 1,2 GB libres (≈ 240 fotos)" si algo está bajo; null si todo está bien. */
    fun aviso(ctx: Context): String? {
        val partes = mutableListOf<String>()
        val b = bateria(ctx)
        if (b != null && b <= BATERIA_BAJA && !cargando(ctx)) partes.add("🔋 batería $b %")
        val l = libre()
        if (l < ESPACIO_BAJO) partes.add("💾 ${textoEspacio(l)} libres (≈ ${l / (MB_POR_FOTO shl 20)} fotos)")
        return if (partes.isEmpty()) null else partes.joinToString(" · ")
    }
}
