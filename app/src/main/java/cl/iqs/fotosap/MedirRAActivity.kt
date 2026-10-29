package cl.iqs.fotosap

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Medición con realidad aumentada (ARCore).
 *
 * Modos:
 *  - Parche: 4 esquinas → largo de cada lado y área.
 *  - Desde referencia: el primer punto es la referencia (soldadura, borde); cada punto siguiente
 *    muestra su distancia a ella.
 *  - Tramo: puntos a lo largo del tubo → largo de cada tramo y total.
 *  - Largo: pares de puntos → distancia de cada par.
 *
 * Se marca con la mira del centro y el botón "Marcar punto" (más preciso que tocar y cómodo con
 * guantes); tocar la pantalla también marca en ese lugar. "Guardar" crea una foto con las cotas
 * dibujadas en la carpeta indicada por MainActivity (extras "ruta", "nombre", "titulo") y devuelve
 * "nombre" y "resumen".
 */
class MedirRAActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    enum class Modo(val titulo: String, val ayuda: String) {
        PARCHE("Parche", "Marca las 4 esquinas del parche o zona dañada"),
        REFERENCIA("Desde referencia", "Marca primero la referencia (soldadura, borde) y luego cada punto"),
        TRAMO("Tramo", "Marca puntos a lo largo del tubo; se suma el largo"),
        LARGO("Largo", "Marca los dos extremos; repite para otra medida")
    }

    // ---------- Colores (mismos de la app) ----------
    private val cPrimario = Color.parseColor("#0E3A5F")
    private val cAcento = Color.parseColor("#F5A300")
    private val cTinta = Color.parseColor("#0F1B2A")
    private val cOk = Color.parseColor("#22C55E")
    private val cRef = Color.parseColor("#22D3EE")

    private lateinit var gl: GLSurfaceView
    private lateinit var capa: Capa
    private lateinit var estado: TextView
    private lateinit var ayuda: TextView
    private lateinit var resultado: TextView
    private val botonesModo = mutableMapOf<Modo, Button>()

    private var session: Session? = null
    private var instalacionPedida = false
    private var modo = Modo.PARCHE

    // Datos compartidos entre el hilo de OpenGL y la interfaz (protegidos con synchronized)
    private val anclas = mutableListOf<Anchor>()
    @Volatile private var pendienteToque: FloatArray? = null
    @Volatile private var pedirFoto = false
    @Volatile private var hayAcierto = false
    @Volatile private var siguiendo = false

    /** Posiciones en pantalla de cada punto (x, y, visible) calculadas en el último cuadro. */
    @Volatile private var enPantalla: List<FloatArray> = emptyList()
    @Volatile private var mundo: List<FloatArray> = emptyList()

    private var anchoVista = 1
    private var altoVista = 1
    private var texturaCamara = -1
    private val fondo = FondoCamara()

    private val permisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) { aviso("Sin permiso de cámara no se puede medir con RA"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        gl = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MedirRAActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        raiz.addView(gl, FrameLayout.LayoutParams(-1, -1))
        capa = Capa()
        raiz.addView(capa, FrameLayout.LayoutParams(-1, -1))

        // Barra superior: estado + ayuda + modos
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(34), dp(14), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT))
        }
        estado = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        ayuda = TextView(this).apply { textSize = 13.5f; setTextColor(Color.parseColor("#CFDCEA")); setPadding(0, dp(2), 0, dp(8)) }
        arriba.addView(estado); arriba.addView(ayuda)
        val modos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in Modo.values()) {
            val b = chip(m.titulo) { cambiarModo(m) }
            botonesModo[m] = b
            modos.addView(b)
        }
        arriba.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(modos) })
        raiz.addView(arriba, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Barra inferior: resultado + botones grandes
        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(220, 0, 0, 0), Color.TRANSPARENT))
        }
        resultado = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10))
        }
        abajo.addView(resultado)
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fila.addView(boton("Deshacer", Color.argb(220, 20, 32, 43), Color.WHITE) { deshacer() }, LinearLayout.LayoutParams(0, dp(68), 1f))
        fila.addView(boton("＋ Marcar punto", cAcento, cTinta) { marcarCentro() },
            LinearLayout.LayoutParams(0, dp(76), 1.4f).apply { marginStart = dp(8); marginEnd = dp(8) })
        fila.addView(boton("Guardar", cPrimario, Color.WHITE) { guardar() }, LinearLayout.LayoutParams(0, dp(68), 1f))
        abajo.addView(fila)
        raiz.addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(raiz)
        cambiarModo(Modo.PARCHE)
        actualizarTextos()
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permisoCamara.launch(Manifest.permission.CAMERA); return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !instalacionPedida)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { instalacionPedida = true; return }
                    else -> {}
                }
                val s = Session(this)
                val cfg = Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                }
                s.configure(cfg)
                session = s
            } catch (e: UnavailableException) {
                noDisponible("Este teléfono no es compatible con realidad aumentada (ARCore).\n\n${e.javaClass.simpleName}")
                return
            } catch (e: Exception) {
                noDisponible("No se pudo iniciar la realidad aumentada.\n\n${e.message}")
                return
            }
        }
        try { session?.resume() } catch (e: Exception) {
            noDisponible("No se pudo abrir la cámara para RA.\n\n${e.message}"); return
        }
        gl.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) { gl.onPause(); session?.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close(); session = null
    }

    private fun noDisponible(msg: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle("Realidad aumentada no disponible").setMessage(msg)
            .setCancelable(false).setPositiveButton("Volver") { _, _ -> finish() }.show()
    }

    // ---------- Interfaz ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun boton(t: String, fondo: Int, letra: Int, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 17f; setTextColor(letra); typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null; elevation = 0f
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(fondo) }, null)
        setOnClickListener { f() }
    }

    private fun chip(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null; elevation = 0f; minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(52)).apply { marginEnd = dp(8) }
    }

    private fun cambiarModo(m: Modo) {
        if (m != modo && synchronized(anclas) { anclas.isNotEmpty() }) {
            AlertDialog.Builder(this).setMessage("¿Cambiar a ${m.titulo}? Se borran los puntos marcados.")
                .setPositiveButton("Cambiar") { _, _ -> borrarTodo(); aplicarModo(m) }
                .setNegativeButton("No", null).show()
            return
        }
        aplicarModo(m)
    }

    private fun aplicarModo(m: Modo) {
        modo = m
        for ((mm, b) in botonesModo) {
            val sel = mm == m
            b.setTextColor(if (sel) cTinta else Color.WHITE)
            b.background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(if (sel) cAcento else Color.argb(170, 20, 32, 43))
                if (!sel) setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
        }
        actualizarTextos()
    }

    private fun borrarTodo() = synchronized(anclas) { anclas.forEach { it.detach() }; anclas.clear() }

    private fun deshacer() {
        synchronized(anclas) { if (anclas.isNotEmpty()) anclas.removeAt(anclas.size - 1).detach() }
        actualizarTextos()
    }

    private fun marcarCentro() { pendienteToque = floatArrayOf(anchoVista / 2f, altoVista / 2f) }

    private fun actualizarTextos() {
        ayuda.text = modo.ayuda
        resultado.text = resumen().ifBlank { "Apunta a la superficie y marca el primer punto" }
    }

    // ---------- Cálculos ----------

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun cm(m: Float) = "%.1f cm".format(Locale("es", "CL"), m * 100f)

    /** Segmentos a dibujar: (índice a, índice b, etiqueta). */
    private fun segmentos(p: List<FloatArray>): List<Triple<Int, Int, String>> {
        val l = mutableListOf<Triple<Int, Int, String>>()
        when (modo) {
            Modo.PARCHE -> {
                for (i in 0 until p.size - 1) l.add(Triple(i, i + 1, cm(dist(p[i], p[i + 1]))))
                if (p.size >= 3) l.add(Triple(p.size - 1, 0, cm(dist(p[p.size - 1], p[0]))))
            }
            Modo.REFERENCIA -> for (i in 1 until p.size) l.add(Triple(0, i, "P$i ${cm(dist(p[0], p[i]))}"))
            Modo.TRAMO -> for (i in 0 until p.size - 1) l.add(Triple(i, i + 1, cm(dist(p[i], p[i + 1]))))
            Modo.LARGO -> { var i = 0; while (i + 1 < p.size) { l.add(Triple(i, i + 1, cm(dist(p[i], p[i + 1])))); i += 2 } }
        }
        return l
    }

    /** Área del polígono 3D (norma de la suma de productos cruz / 2). */
    private fun area(p: List<FloatArray>): Float {
        var x = 0f; var y = 0f; var z = 0f
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            x += a[1] * b[2] - a[2] * b[1]; y += a[2] * b[0] - a[0] * b[2]; z += a[0] * b[1] - a[1] * b[0]
        }
        return sqrt(x * x + y * y + z * z) / 2f
    }

    /** Texto con los resultados del modo actual (también va en la foto guardada). */
    private fun resumen(): String {
        val p = mundo
        if (p.isEmpty()) return ""
        return when (modo) {
            Modo.PARCHE -> {
                if (p.size < 2) "1 esquina marcada"
                else {
                    val lados = segmentos(p).mapIndexed { i, s -> "L${i + 1} ${s.third}" }.joinToString(" · ")
                    if (p.size >= 3) "$lados\nÁrea ${"%.0f".format(area(p) * 10000f)} cm²" +
                        (if (p.size == 4) {
                            val largo = maxOf((dist(p[0], p[1]) + dist(p[2], p[3])) / 2, (dist(p[1], p[2]) + dist(p[3], p[0])) / 2)
                            val ancho = minOf((dist(p[0], p[1]) + dist(p[2], p[3])) / 2, (dist(p[1], p[2]) + dist(p[3], p[0])) / 2)
                            " · Largo ${cm(largo)} × Ancho ${cm(ancho)}"
                        } else "")
                    else lados
                }
            }
            Modo.REFERENCIA -> if (p.size < 2) "Referencia marcada: ahora marca los puntos"
                else segmentos(p).joinToString(" · ") { it.third }
            Modo.TRAMO -> {
                val segs = segmentos(p)
                if (segs.isEmpty()) "Inicio del tramo marcado"
                else "Total ${cm((0 until p.size - 1).sumOf { dist(p[it], p[it + 1]).toDouble() }.toFloat())}" +
                     (if (segs.size > 1) "  (${segs.joinToString(" + ") { it.third }})" else "")
            }
            Modo.LARGO -> {
                val segs = segmentos(p)
                if (segs.isEmpty()) "Marca el otro extremo"
                else segs.mapIndexed { i, s -> "M${i + 1} ${s.third}" }.joinToString(" · ")
            }
        }
    }

    // ---------- Dibujo de puntos y cotas sobre la cámara ----------

    private inner class Capa : View(this@MedirRAActivity) {
        private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(160, 0, 0, 0); strokeCap = Paint.Cap.ROUND }
        private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
        private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
        private val caja = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 8, 14, 20) }

        init {
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP) pendienteToque = floatArrayOf(e.x, e.y)
                true
            }
        }

        override fun onDraw(c: Canvas) {
            dibujar(c, conMira = true)
            postInvalidateOnAnimation()
        }

        fun dibujar(c: Canvas, conMira: Boolean) {
            val u = resources.displayMetrics.density
            linea.strokeWidth = 3.5f * u; sombra.strokeWidth = 7f * u; letra.textSize = 16f * u
            val pts = enPantalla
            val segs = segmentos(mundo)
            for ((a, b, txt) in segs) {
                val pa = pts.getOrNull(a) ?: continue; val pb = pts.getOrNull(b) ?: continue
                if (pa[2] < 0.5f || pb[2] < 0.5f) continue
                linea.color = if (modo == Modo.REFERENCIA) cRef else cAcento
                c.drawLine(pa[0], pa[1], pb[0], pb[1], sombra); c.drawLine(pa[0], pa[1], pb[0], pb[1], linea)
                etiqueta(c, (pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2, txt, u)
            }
            pts.forEachIndexed { i, p ->
                if (p[2] < 0.5f) return@forEachIndexed
                val esRef = modo == Modo.REFERENCIA && i == 0
                relleno.color = Color.argb(170, 0, 0, 0); c.drawCircle(p[0], p[1], 11f * u, relleno)
                relleno.color = if (esRef) cRef else Color.WHITE; c.drawCircle(p[0], p[1], 7f * u, relleno)
                if (esRef) etiqueta(c, p[0], p[1] - 26f * u, "REF", u)
            }
            if (conMira) {
                val cx = width / 2f; val cy = height / 2f
                linea.color = if (hayAcierto) cOk else Color.WHITE
                linea.strokeWidth = 2.5f * u
                c.drawCircle(cx, cy, 22f * u, sombra.apply { strokeWidth = 5f * u })
                c.drawCircle(cx, cy, 22f * u, linea)
                c.drawLine(cx - 10f * u, cy, cx + 10f * u, cy, linea)
                c.drawLine(cx, cy - 10f * u, cx, cy + 10f * u, linea)
                // Línea guía desde el último punto a la mira
                val ult = pts.lastOrNull()
                val guia = when (modo) { Modo.LARGO -> pts.size % 2 == 1; Modo.REFERENCIA -> pts.isNotEmpty(); else -> pts.isNotEmpty() }
                if (ult != null && ult[2] > 0.5f && guia && hayAcierto) {
                    val desde = if (modo == Modo.REFERENCIA) pts[0] else ult
                    linea.color = Color.argb(200, 255, 255, 255)
                    linea.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * u, 8f * u), 0f)
                    c.drawLine(desde[0], desde[1], cx, cy, linea)
                    linea.pathEffect = null
                }
            }
        }

        private fun etiqueta(c: Canvas, x: Float, y: Float, t: String, u: Float) {
            val fm = letra.fontMetrics
            val w = letra.measureText(t) + 16f * u; val h = fm.descent - fm.ascent + 10f * u
            val r = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            c.drawRoundRect(r, 8f * u, 8f * u, caja)
            c.drawText(t, r.left + 8f * u, r.top + 5f * u - fm.ascent, letra)
        }
    }

    // ---------- OpenGL / ARCore ----------

    override fun onSurfaceCreated(g: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        texturaCamara = fondo.crear()
        session?.setCameraTextureName(texturaCamara)
    }

    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        anchoVista = w; altoVista = h
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, w, h)
    }

    override fun onDrawFrame(g: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (texturaCamara < 0) return
        s.setCameraTextureName(texturaCamara)
        val frame: Frame = try { s.update() } catch (e: Exception) { return }
        fondo.dibujar(frame)
        val cam = frame.camera
        siguiendo = cam.trackingState == TrackingState.TRACKING

        // Toque o "Marcar punto" pendiente
        pendienteToque?.let { t ->
            pendienteToque = null
            if (siguiendo) {
                val hit = mejorAcierto(frame, t[0], t[1])
                if (hit != null) {
                    synchronized(anclas) {
                        if (modo == Modo.PARCHE && anclas.size >= 4) runOnUiThread { aviso("El parche ya tiene 4 esquinas. Deshacer para cambiar.") }
                        else anclas.add(hit.createAnchor())
                    }
                    runOnUiThread { actualizarTextos() }
                } else runOnUiThread { aviso("No se reconoce la superficie ahí. Mueve el teléfono despacio.") }
            }
        }
        hayAcierto = siguiendo && mejorAcierto(frame, anchoVista / 2f, altoVista / 2f) != null

        // Proyección de cada punto a la pantalla
        val vista = FloatArray(16); val proy = FloatArray(16)
        cam.getViewMatrix(vista, 0); cam.getProjectionMatrix(proy, 0, 0.05f, 50f)
        val vp = FloatArray(16); Matrix.multiplyMM(vp, 0, proy, 0, vista, 0)
        val w = mutableListOf<FloatArray>(); val sp = mutableListOf<FloatArray>()
        synchronized(anclas) {
            for (a in anclas) {
                val pose = a.pose
                val p = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
                w.add(p)
                val v = floatArrayOf(p[0], p[1], p[2], 1f); val r = FloatArray(4)
                Matrix.multiplyMV(r, 0, vp, 0, v, 0)
                if (r[3] <= 0f) sp.add(floatArrayOf(0f, 0f, 0f))
                else sp.add(floatArrayOf((r[0] / r[3] + 1f) / 2f * anchoVista, (1f - r[1] / r[3]) / 2f * altoVista, 1f))
            }
        }
        mundo = w; enPantalla = sp

        val txt = when {
            cam.trackingState == TrackingState.PAUSED -> when (cam.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Poca luz: ilumina la zona"
                TrackingFailureReason.EXCESSIVE_MOTION -> "Muévete más despacio"
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Superficie sin detalles: apunta a una zona con textura"
                else -> "Mueve el teléfono despacio para reconocer el lugar"
            }
            cam.trackingState == TrackingState.STOPPED -> "Seguimiento detenido"
            hayAcierto -> "Listo para marcar"
            else -> "Buscando superficie… acércate o mueve el teléfono"
        }
        runOnUiThread { if (estado.text != txt) estado.text = txt; if (mundo.isNotEmpty()) resultado.text = resumen() }

        if (pedirFoto) { pedirFoto = false; capturar() }
    }

    /** Acierto preferido: plano en su polígono, luego punto de profundidad, luego punto orientado. */
    private fun mejorAcierto(frame: Frame, x: Float, y: Float) = frame.hitTest(x, y).firstOrNull { h ->
        when (val t = h.trackable) {
            is Plane -> t.isPoseInPolygon(h.hitPose)
            is DepthPoint -> true
            is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            else -> false
        }
    }

    // ---------- Guardar ----------

    private fun guardar() {
        if (mundo.size < 2) { aviso("Marca al menos dos puntos"); return }
        pedirFoto = true
    }

    /** Se ejecuta en el hilo de OpenGL: lee la imagen de la cámara y la completa en la interfaz. */
    private fun capturar() {
        val w = anchoVista; val h = altoVista
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val crudo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind(); crudo.copyPixelsFromBuffer(buf)
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val foto = Bitmap.createBitmap(crudo, 0, 0, w, h, m, false)
        crudo.recycle()
        runOnUiThread { terminar(foto) }
    }

    private fun terminar(foto: Bitmap) {
        val titulo = intent.getStringExtra("titulo") ?: "Medición RA"
        val texto = resumen()
        val u = resources.displayMetrics.density
        val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * u }
        val negrita = Paint(letra).apply { typeface = Typeface.DEFAULT_BOLD }
        val pad = 12f * u
        val lineas = mutableListOf("$titulo · ${modo.titulo} · " +
            SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date()))
        for (parte in texto.split("\n")) {
            var actual = ""
            for (trozo in parte.split(" · ")) {
                val prueba = if (actual.isEmpty()) trozo else "$actual · $trozo"
                if (letra.measureText(prueba) > foto.width - 2 * pad && actual.isNotEmpty()) { lineas.add(actual); actual = trozo }
                else actual = prueba
            }
            if (actual.isNotEmpty()) lineas.add(actual)
        }
        val alto = letra.fontSpacing
        val franja = (lineas.size * alto + 2 * pad).toInt()
        val out = Bitmap.createBitmap(foto.width, foto.height + franja, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(foto, 0f, 0f, null); foto.recycle()
        capa.dibujar(c, conMira = false)
        c.drawRect(0f, (out.height - franja).toFloat(), out.width.toFloat(), out.height.toFloat(), Paint().apply { color = Color.BLACK })
        lineas.forEachIndexed { i, l ->
            c.drawText(l, pad, out.height - franja + pad + i * alto - letra.fontMetrics.ascent, if (i == 0) negrita else letra)
        }

        val ruta = intent.getStringExtra("ruta") ?: "Documents/BoilerImage/"
        val nombre = intent.getStringExtra("nombre") ?: "Medicion_RA.jpg"
        val ok = runCatching {
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
            }) ?: error("sin archivo")
            contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: error("sin archivo")
        }.isSuccess
        out.recycle()
        if (!ok) { aviso("No se pudo guardar la foto"); return }
        setResult(RESULT_OK, Intent().putExtra("nombre", nombre).putExtra("resumen", texto).putExtra("modo", modo.titulo))
        finish()
    }

    /** Dibuja la imagen de la cámara de fondo (textura externa de ARCore). */
    private class FondoCamara {
        private var programa = 0
        private var textura = 0
        private var listo = false
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private val tex: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        fun crear(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textura = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textura)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val vs = """
                attribute vec4 a_Pos; attribute vec2 a_Tex; varying vec2 v_Tex;
                void main() { gl_Position = a_Pos; v_Tex = a_Tex; }
            """.trimIndent()
            val fs = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float; varying vec2 v_Tex; uniform samplerExternalOES u_Tex;
                void main() { gl_FragColor = texture2D(u_Tex, v_Tex); }
            """.trimIndent()
            programa = GLES20.glCreateProgram()
            GLES20.glAttachShader(programa, sombreador(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(programa, sombreador(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(programa)
            return textura
        }

        private fun sombreador(tipo: Int, codigo: String): Int {
            val s = GLES20.glCreateShader(tipo)
            GLES20.glShaderSource(s, codigo); GLES20.glCompileShader(s)
            return s
        }

        fun dibujar(frame: Frame) {
            if (!listo || frame.hasDisplayGeometryChanged()) {
                quad.position(0); tex.position(0)
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                    Coordinates2d.TEXTURE_NORMALIZED, tex)
                quad.position(0); tex.position(0)
                listo = true
            }
            if (frame.timestamp == 0L) return
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
            GLES20.glUseProgram(programa)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textura)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programa, "u_Tex"), 0)
            val aPos = GLES20.glGetAttribLocation(programa, "a_Pos")
            val aTex = GLES20.glGetAttribLocation(programa, "a_Tex")
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex)
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
            GLES20.glDepthMask(true); GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
    }
}
