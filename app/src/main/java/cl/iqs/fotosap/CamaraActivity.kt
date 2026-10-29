package cl.iqs.fotosap

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Cámara propia de BoilerImage (CameraX), pensada para el interior de la caldera y con guantes.
 *
 *  - Ráfaga continua: la cámara no se cierra entre fotos.
 *  - Disparo con el botón grande, las teclas de volumen o la tecla de cámara.
 *  - Selector General / Detalle y código de la singularidad siempre visible.
 *  - Saltar a la singularidad anterior / siguiente (recorre todas las paredes del tipo).
 *  - Lentes 0,6× · 1× · 3× · 5× (según lo que ofrezca el teléfono), pellizcar para zoom.
 *  - Linterna fija, tocar para enfocar y medir la luz en ese punto.
 *  - Resolución Normal (12 MP) o Máxima; modos HDR / Noche si el teléfono los ofrece.
 *
 * Extras de entrada: "rutas", "codigos" (ej. AP01), "titulos" (ej. AP01 · Pared Frontal),
 * "indice" y "modo" (general | detalle). Devuelve "indice" (última singularidad) y "fotos".
 * Si la cámara no puede abrirse devuelve RESULT_CANCELED con "error" para usar la del sistema.
 */
class CamaraActivity : AppCompatActivity() {

    private val coleccion: Uri = MediaStore.Files.getContentUri("external")
    private val prefs by lazy { getSharedPreferences("fotosap", MODE_PRIVATE) }

    private lateinit var rutas: List<String>
    private lateinit var codigos: List<String>
    private lateinit var titulos: List<String>
    private var indice = 0
    private var modo = "general"
    private var fotosTomadas = 0

    private lateinit var vista: PreviewView
    private var proveedor: ProcessCameraProvider? = null
    private var extensiones: ExtensionsManager? = null
    private var camara: Camera? = null
    private var captura: ImageCapture? = null
    private var ocupado = false
    private var linterna = false
    private var extActual = ExtensionMode.NONE
    private val extDisponibles = mutableListOf(ExtensionMode.NONE)
    private val sonido = MediaActionSound()

    // vistas que se actualizan
    private lateinit var txtTitulo: TextView
    private lateinit var txtConteo: TextView
    private lateinit var btnAnterior: TextView
    private lateinit var btnSiguiente: TextView
    private lateinit var btnGeneral: TextView
    private lateinit var btnDetalle: TextView
    private lateinit var filaLentes: LinearLayout
    private lateinit var btnLinterna: ImageView
    private lateinit var miniatura: ImageView
    private lateinit var btnRes: TextView
    private lateinit var btnExt: TextView
    private lateinit var destello: View
    private lateinit var anilloFoco: View
    private var ultimaUri: Uri? = null
    private var ultimoNombre = ""

    private val medio: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val oscuro = Color.parseColor("#0B1520")
    private val panel = Color.argb(170, 11, 21, 32)
    private val acento = Color.parseColor("#F5A300")
    private val tinta = Color.parseColor("#0F1B2A")
    private val gris = Color.parseColor("#1C2A3A")

    private val permiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) iniciarCamara() else { Toast.makeText(this, "Sin permiso de cámara", Toast.LENGTH_LONG).show(); terminar() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rutas = intent.getStringArrayListExtra("rutas") ?: arrayListOf()
        codigos = intent.getStringArrayListExtra("codigos") ?: arrayListOf()
        titulos = intent.getStringArrayListExtra("titulos") ?: arrayListOf()
        if (rutas.isEmpty() || rutas.size != codigos.size || rutas.size != titulos.size) { finish(); return }
        indice = (savedInstanceState?.getInt("indice") ?: intent.getIntExtra("indice", 0)).coerceIn(0, rutas.size - 1)
        modo = savedInstanceState?.getString("modo") ?: intent.getStringExtra("modo") ?: "general"
        fotosTomadas = savedInstanceState?.getInt("fotos") ?: 0
        linterna = prefs.getBoolean("cam_linterna", false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = oscuro
        window.navigationBarColor = oscuro
        construir()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = terminar()
        })
        sonido.load(MediaActionSound.SHUTTER_CLICK)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) iniciarCamara()
        else permiso.launch(Manifest.permission.CAMERA)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("indice", indice); outState.putString("modo", modo); outState.putInt("fotos", fotosTomadas)
    }

    override fun onDestroy() {
        super.onDestroy()
        orientacion.disable()
        sonido.release()
    }

    private fun terminar() {
        setResult(RESULT_OK, Intent().putExtra("indice", indice).putExtra("fotos", fotosTomadas))
        finish()
    }

    // ---------- Interfaz ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fondo(color: Int, radio: Int, borde: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
        GradientDrawable().apply { cornerRadius = dp(radio).toFloat(); setColor(color); borde?.let { setStroke(dp(2), it) } },
        GradientDrawable().apply { cornerRadius = dp(radio).toFloat(); setColor(Color.WHITE) })

    private fun icono(res: Int, color: Int, tam: Int) =
        getDrawable(res)?.mutate()?.apply { setTint(color); setBounds(0, 0, dp(tam), dp(tam)) }

    private fun chip(t: String, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 17f; typeface = medio; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 1
        background = fondo(gris, 26); setPadding(dp(12), 0, dp(12), 0); isClickable = true
        setOnClickListener { f() }
    }

    private fun botonRedondo(res: Int, tam: Int, f: () -> Unit) = ImageView(this).apply {
        setImageDrawable(icono(res, Color.WHITE, tam / 2 - 4))
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gris) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        isClickable = true; setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(dp(tam), dp(tam))
    }

    private fun construir() {
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vista = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        raiz.addView(vista, FrameLayout.LayoutParams(-1, -1))
        destello = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        raiz.addView(destello, FrameLayout.LayoutParams(-1, -1))
        anilloFoco = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(3), acento) }
            alpha = 0f
        }
        raiz.addView(anilloFoco, FrameLayout.LayoutParams(dp(84), dp(84)))

        // --- Arriba: código, conteo, navegación y modo ---
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(panel); setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val fila1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fila1.addView(botonRedondo(R.drawable.ic_cerrar, 56) { terminar() })
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
        }
        txtTitulo = TextView(this).apply { textSize = 22f; typeface = medio; setTextColor(Color.WHITE); maxLines = 1 }
        txtConteo = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#C9D3DD")) }
        textos.addView(txtTitulo); textos.addView(txtConteo)
        fila1.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        arriba.addView(fila1)

        val fila2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        btnGeneral = chip("General") { cambiarModo("general") }
        btnDetalle = chip("Detalle") { cambiarModo("detalle") }
        fila2.addView(btnGeneral, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(6) })
        fila2.addView(btnDetalle, LinearLayout.LayoutParams(0, dp(56), 1f))
        arriba.addView(fila2)
        val fila3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        btnAnterior = chip("") { saltar(-1) }
        btnSiguiente = chip("") { saltar(1) }
        fila3.addView(btnAnterior, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        fila3.addView(btnSiguiente, LinearLayout.LayoutParams(0, dp(52), 1f))
        arriba.addView(fila3)
        raiz.addView(arriba, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // --- Abajo: opciones, lentes y disparador ---
        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(panel); setPadding(dp(12), dp(10), dp(12), dp(18))
        }
        val opciones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRes = chip("") { cambiarResolucion() }
        btnExt = chip("") { cambiarExtension() }
        opciones.addView(btnRes, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        opciones.addView(btnExt, LinearLayout.LayoutParams(-2, dp(48)))
        abajo.addView(opciones)
        filaLentes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)) }
        abajo.addView(filaLentes)

        val filaDisparo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        miniatura = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(gris); setStroke(dp(2), Color.WHITE) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat()) }
            }
            setOnClickListener { revisarUltima() }
        }
        filaDisparo.addView(miniatura, LinearLayout.LayoutParams(dp(68), dp(68)))
        filaDisparo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val disparador = View(this).apply {
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(90, 0, 0, 0)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(6), acento) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            isClickable = true; contentDescription = "Tomar foto"
            setOnClickListener { disparar() }
        }
        filaDisparo.addView(disparador, LinearLayout.LayoutParams(dp(96), dp(96)))
        filaDisparo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btnLinterna = botonRedondo(R.drawable.ic_linterna, 68) { alternarLinterna() }
        filaDisparo.addView(btnLinterna)
        abajo.addView(filaDisparo, LinearLayout.LayoutParams(-1, -2))
        abajo.addView(TextView(this).apply {
            text = "También disparan las teclas de volumen"; textSize = 12f; setTextColor(Color.parseColor("#9FB0C0"))
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        raiz.addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Gestos sobre la vista previa: tocar = enfocar, pellizcar = zoom
        val escala = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = camara ?: return false
                val z = c.cameraInfo.zoomState.value ?: return false
                c.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        vista.setOnTouchListener { v, e ->
            escala.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !escala.isInProgress && e.eventTime - e.downTime < 300) {
                enfocar(e.x, e.y); v.performClick()
            }
            true
        }
        setContentView(raiz)
        refrescar()
    }

    private fun estiloChip(t: TextView, activo: Boolean) {
        t.background = fondo(if (activo) acento else gris, 26)
        t.setTextColor(if (activo) tinta else Color.WHITE)
    }

    private fun refrescar() {
        txtTitulo.text = titulos[indice]
        val nombres = nombresEn(rutas[indice])
        val g = nombres.count { it.contains("_general_") && !it.contains("_marcada") }
        val d = nombres.count { it.contains("_detalle_") && !it.contains("_marcada") }
        txtConteo.text = "Generales: $g · Detalles: $d" + if (fotosTomadas > 0) "   ·   $fotosTomadas en esta sesión" else ""
        // solo el código; si el salto cambia de pared, se agrega la pared (en otra línea)
        fun etiqueta(j: Int): String {
            val pared = titulos[j].substringAfter(" · ")
            return if (pared == titulos[indice].substringAfter(" · ")) codigos[j] else "${codigos[j]}\n$pared"
        }
        btnAnterior.text = if (indice > 0) "‹  ${etiqueta(indice - 1)}" else "—"
        btnSiguiente.text = if (indice < rutas.size - 1) "${etiqueta(indice + 1)}  ›" else "—"
        for (b in listOf(btnAnterior, btnSiguiente)) { b.maxLines = 2; b.textSize = if (b.text.contains('\n')) 13f else 17f }
        btnAnterior.alpha = if (indice > 0) 1f else 0.35f
        btnSiguiente.alpha = if (indice < rutas.size - 1) 1f else 0.35f
        estiloChip(btnGeneral, modo == "general")
        estiloChip(btnDetalle, modo == "detalle")
        btnRes.text = if (prefs.getString("cam_res", "normal") == "max") "Resolución: máxima" else "Resolución: 12 MP"
        btnExt.text = "Modo: " + nombreExt(extActual)
        btnExt.visibility = if (extDisponibles.size > 1) View.VISIBLE else View.GONE
        btnLinterna.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (linterna) acento else gris) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        btnLinterna.setImageDrawable(icono(R.drawable.ic_linterna, if (linterna) tinta else Color.WHITE, 30))
    }

    private fun nombreExt(m: Int) = when (m) { ExtensionMode.HDR -> "HDR"; ExtensionMode.NIGHT -> "Noche"; else -> "Normal" }

    private fun saltar(d: Int) {
        val j = indice + d
        if (j !in rutas.indices) return
        indice = j; refrescar()
        aviso(titulos[indice])
    }

    private fun cambiarModo(m: String) { modo = m; refrescar() }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------- Cámara ----------

    private val orientacion by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                captura?.targetRotation = r
            }
        }
    }

    private fun iniciarCamara() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val p = runCatching { fut.get() }.getOrElse { fallar(it); return@addListener }
            proveedor = p
            val fe = ExtensionsManager.getInstanceAsync(this, p)
            fe.addListener({
                extensiones = runCatching { fe.get() }.getOrNull()
                extensiones?.let { em ->
                    for (m in listOf(ExtensionMode.HDR, ExtensionMode.NIGHT))
                        if (runCatching { em.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, m) }.getOrDefault(false))
                            extDisponibles.add(m)
                }
                val guardado = prefs.getInt("cam_ext", ExtensionMode.NONE)
                extActual = if (guardado in extDisponibles) guardado else ExtensionMode.NONE
                vincular()
                orientacion.enable()
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun fallar(e: Throwable) {
        setResult(RESULT_CANCELED, Intent().putExtra("error", e.message ?: e.javaClass.simpleName)
            .putExtra("indice", indice).putExtra("fotos", fotosTomadas))
        finish()
    }

    private fun vincular() {
        val p = proveedor ?: return
        val maxima = prefs.getString("cam_res", "normal") == "max"
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                if (maxima) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                else ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .apply { if (maxima) setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .build().also { it.setSurfaceProvider(vista.surfaceProvider) }
        val cap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(selector)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        var sel = CameraSelector.DEFAULT_BACK_CAMERA
        if (extActual != ExtensionMode.NONE) extensiones?.let { sel = it.getExtensionEnabledCameraSelector(sel, extActual) }
        try {
            p.unbindAll()
            camara = p.bindToLifecycle(this, sel, preview, cap)
            captura = cap
        } catch (e: Exception) {
            if (extActual != ExtensionMode.NONE || maxima) {
                // reintento con la configuración básica
                extActual = ExtensionMode.NONE; prefs.edit().putString("cam_res", "normal").putInt("cam_ext", ExtensionMode.NONE).apply()
                aviso("Ese modo no funcionó en este teléfono; se usa el normal")
                vincular(); return
            }
            fallar(e); return
        }
        val c = camara!!
        if (linterna && c.cameraInfo.hasFlashUnit()) c.cameraControl.enableTorch(true)
        c.cameraInfo.zoomState.observe(this) { armarLentes() }
        armarLentes()
        refrescar()
    }

    private var lentesArmados = ""

    /** Botones de lente según el rango de zoom del teléfono (0,6× aparece si hay gran angular). */
    private fun armarLentes() {
        val c = camara ?: return
        val z = c.cameraInfo.zoomState.value ?: return
        val opciones = mutableListOf<Float>()
        if (z.minZoomRatio < 0.95f) opciones.add(z.minZoomRatio)
        opciones.add(1f)
        for (x in listOf(3f, 5f, 10f)) if (z.maxZoomRatio >= x) opciones.add(x)
        val actual = z.zoomRatio
        val cercano = opciones.minByOrNull { Math.abs(it - actual) } ?: 1f
        val clave = opciones.joinToString() + "|" + cercano
        if (clave == lentesArmados) return
        lentesArmados = clave
        filaLentes.removeAllViews()
        for (o in opciones) {
            val activo = o == cercano && Math.abs(actual - o) < 0.15f * o
            filaLentes.addView(TextView(this).apply {
                text = (if (o % 1f == 0f) "${o.toInt()}" else "%.1f".format(o).replace('.', ',')) + "×"
                textSize = 16f; typeface = medio; gravity = Gravity.CENTER
                setTextColor(if (activo) tinta else Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (activo) acento else gris) }
                isClickable = true
                setOnClickListener { c.cameraControl.setZoomRatio(o) }
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(dp(6), 0, dp(6), 0) }
            })
        }
    }

    private fun enfocar(x: Float, y: Float) {
        val c = camara ?: return
        val punto = vista.meteringPointFactory.createPoint(x, y)
        val accion = FocusMeteringAction.Builder(punto, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        runCatching { c.cameraControl.startFocusAndMetering(accion) }
        anilloFoco.translationX = x - dp(42); anilloFoco.translationY = y - dp(42)
        anilloFoco.animate().cancel()
        anilloFoco.alpha = 1f; anilloFoco.scaleX = 1.3f; anilloFoco.scaleY = 1.3f
        anilloFoco.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            anilloFoco.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
        }.start()
    }

    private fun alternarLinterna() {
        val c = camara ?: return
        if (!c.cameraInfo.hasFlashUnit()) { aviso("Este modo no permite linterna"); return }
        linterna = !linterna
        c.cameraControl.enableTorch(linterna)
        prefs.edit().putBoolean("cam_linterna", linterna).apply()
        refrescar()
    }

    private fun cambiarResolucion() {
        val max = prefs.getString("cam_res", "normal") == "max"
        prefs.edit().putString("cam_res", if (max) "normal" else "max").apply()
        aviso(if (max) "Resolución 12 MP (más rápida y liviana)" else "Resolución máxima (archivos más pesados)")
        vincular()
    }

    private fun cambiarExtension() {
        val i = extDisponibles.indexOf(extActual)
        extActual = extDisponibles[(i + 1) % extDisponibles.size]
        prefs.edit().putInt("cam_ext", extActual).apply()
        if (extActual == ExtensionMode.NIGHT) aviso("Noche: mantén el teléfono quieto unos segundos al disparar")
        lentesArmados = ""
        vincular()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event?.repeatCount == 0) disparar()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------- Fotos ----------

    private fun nombresEn(ruta: String): Set<String> {
        val s = HashSet<String>()
        runCatching {
            contentResolver.query(coleccion, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(ruta), null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { s.add(it) }
            }
        }
        return s
    }

    private fun disparar() {
        val cap = captura ?: return
        if (ocupado) return
        ocupado = true
        val ruta = rutas[indice]
        val pre = "${codigos[indice]}_${modo}_"
        val existentes = nombresEn(ruta)
        var k = 1
        while ("$pre$k.jpg" in existentes) k++
        val nombre = "$pre$k.jpg"
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, ruta)
        }
        val opciones = ImageCapture.OutputFileOptions.Builder(contentResolver, coleccion, valores).build()
        destello.alpha = 0.8f
        destello.animate().alpha(0f).setDuration(250).start()
        runCatching { sonido.play(MediaActionSound.SHUTTER_CLICK) }
        cap.takePicture(opciones, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                ocupado = false
                fotosTomadas++
                ultimaUri = r.savedUri; ultimoNombre = nombre
                refrescar()
                aviso("${codigos[indice]} · ${if (modo == "general") "General" else "Detalle"} $k")
                val u = r.savedUri ?: return
                Thread {
                    val b = runCatching { contentResolver.loadThumbnail(u, Size(200, 200), null) }.getOrNull()
                    if (b != null) runOnUiThread { miniatura.setImageBitmap(b) }
                }.start()
            }
            override fun onError(e: ImageCaptureException) {
                ocupado = false
                aviso("No se pudo guardar la foto: ${e.message}")
            }
        })
    }

    /** Ver la última foto en grande, con opción de eliminarla. */
    private fun revisarUltima() {
        val u = ultimaUri ?: return
        val bmp: Bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, u)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val lado = maxOf(info.size.width, info.size.height)
                var m = 1
                while (lado / (m + 1) >= 1600) m++
                if (m > 1) dec.setTargetSampleSize(m)
            }
        }.getOrNull() ?: return
        val iv = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(Color.BLACK) }
        AlertDialog.Builder(this).setTitle(ultimoNombre).setView(iv)
            .setPositiveButton("Seguir") { _, _ -> }
            .setNegativeButton("Eliminar") { _, _ ->
                if (runCatching { contentResolver.delete(u, null, null) > 0 }.getOrDefault(false)) {
                    fotosTomadas = maxOf(0, fotosTomadas - 1)
                    ultimaUri = null; ultimoNombre = ""
                    miniatura.setImageDrawable(null)
                    aviso("Foto eliminada"); refrescar()
                } else aviso("No se pudo eliminar")
            }.show()
    }
}
