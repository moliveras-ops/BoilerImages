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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.TotalCaptureResult
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
 *  - Brillo (compensación de exposición) con barra y botones − / +; se recuerda.
 *  - Enfoque automático (por defecto) o manual con barra Cerca ↔ Lejos; al abrir la cámara vuelve a Auto.
 *    En manual, lupa con el centro ampliado para ver la nitidez.
 *  - Mantener presionado sobre la imagen: bloquea brillo y enfoque (AE/AF) hasta soltarlo.
 *  - Opciones (⋮): disparo estabilizado (espera a que el teléfono esté quieto), flash al disparar,
 *    cuadrícula, nivel y macro automática (pasa al gran angular 0,6× cuando el objeto está muy cerca).
 *  - Foto única (extra "destino" = uri): para la foto de espesores, del medidor o del nombre; devuelve RESULT_OK al guardarla.
 *  - Al abrir avisa si queda poca batería o poco espacio.
 *  - Pendientes: "faltan X de N" en la pared y botón ⏭ para saltar a la siguiente sin completar.
 *  - ★ junto a la miniatura: marca la última foto "para informe" (archivo Para informe.txt de la carpeta).
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
    private var vistaPrevia: Preview? = null
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
    private lateinit var btnBrillo: TextView
    private lateinit var panelBrillo: LinearLayout
    private lateinit var barraBrillo: SeekBar
    private lateinit var txtBrillo: TextView
    private var brillo = 0          // índice de compensación de exposición
    private var brilloMin = 0
    private var brilloMax = 0
    private var brilloPaso = 0f     // EV por paso
    private var brilloRecomendado = false   // aplicar −0,7 EV al conocer el paso de la cámara
    private lateinit var btnEnfoque: TextView
    private lateinit var panelEnfoque: LinearLayout
    private lateinit var barraEnfoque: SeekBar
    private lateinit var txtEnfoque: TextView
    private lateinit var btnAuto: TextView
    private var enfoqueManual = false
    private var enfoque = 500                       // 0 = lo más cerca … PASOS_ENFOQUE = infinito
    private var dioptriasMin = 0f                   // 1 / distancia mínima en metros (0 = foco fijo)
    private val PASOS_ENFOQUE = 1000
    private val BRILLO_RECOMENDADO = -0.7f   // EV: con linterna el metal y la escoria clara se queman

    // opciones (⋮)
    private var optEstab = true
    private var optFlash = false
    private var optGrilla = true
    private var optNivel = true
    private var optMacro = true
    private lateinit var btnOpciones: TextView
    private lateinit var barraArriba: View
    private lateinit var guia: Guia
    private lateinit var txtAviso: TextView           // "Mantén quieto…", "AE/AF bloqueado", "Macro"
    private lateinit var lupa: ImageView
    private lateinit var btnEstrella: TextView
    private var ultimaRuta = ""
    private var destino: Uri? = null      // modo foto única
    private lateinit var fila2V: View
    private lateinit var btnPendiente: TextView
    private var conteoRutas: Map<String, Pair<Int, Int>> = emptyMap()   // ruta → (generales, otras), como en la pantalla principal
    private var ultimaEstrella = false

    // sensores
    private val sensores by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val giro by lazy { sensores.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    private val gravedad by lazy { sensores.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    @Volatile private var ultimoMovimiento = 0L      // última vez que el giro superó el umbral (uptime ms)
    private val g = FloatArray(3)
    private var hayGravedad = false
    private val manejador = Handler(Looper.getMainLooper())

    // bloqueo AE/AF
    private var bloqueado = false
    private var aeBloqueado = false

    // macro automática
    @Volatile private var zoomActual = 1f
    @Volatile private var zoomMin = 1f
    @Volatile private var macroAuto = false           // la app pasó a 0,6× por cercanía
    @Volatile private var macroDesde = 0L
    @Volatile private var macroPausa = 0L             // no volver a cambiar antes de este instante
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
        destino = intent.getStringExtra("destino")?.let { Uri.parse(it) }
        if (destino != null) {
            rutas = listOf(""); codigos = listOf("")
            titulos = listOf(intent.getStringExtra("titulo") ?: "Foto")
        } else {
            rutas = intent.getStringArrayListExtra("rutas") ?: arrayListOf()
            codigos = intent.getStringArrayListExtra("codigos") ?: arrayListOf()
            titulos = intent.getStringArrayListExtra("titulos") ?: arrayListOf()
        }
        if (rutas.isEmpty() || rutas.size != codigos.size || rutas.size != titulos.size) { finish(); return }
        indice = (savedInstanceState?.getInt("indice") ?: intent.getIntExtra("indice", 0)).coerceIn(0, rutas.size - 1)
        modo = savedInstanceState?.getString("modo") ?: intent.getStringExtra("modo") ?: "general"
        fotosTomadas = savedInstanceState?.getInt("fotos") ?: 0
        linterna = prefs.getBoolean("cam_linterna", true)
        brillo = prefs.getInt("cam_brillo", 0)
        brilloRecomendado = !prefs.contains("cam_brillo")
        optEstab = prefs.getBoolean("cam_estab", true)
        optFlash = prefs.getBoolean("cam_flash", false)
        optGrilla = prefs.getBoolean("cam_grilla", true)
        optNivel = prefs.getBoolean("cam_nivel", true)
        optMacro = prefs.getBoolean("cam_macro", true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = oscuro
        window.navigationBarColor = oscuro
        construir()
        if (!prefs.getBoolean("cam_aviso_volumen", false)) {
            aviso("También disparan las teclas de volumen"); prefs.edit().putBoolean("cam_aviso_volumen", true).apply()
        }
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

    override fun onResume() {
        super.onResume()
        giro?.let { sensores.registerListener(oyente, it, SensorManager.SENSOR_DELAY_GAME) }
        gravedad?.let { sensores.registerListener(oyente, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensores.unregisterListener(oyente)
        manejador.removeCallbacks(refrescoLupa)
    }

    private val oyente = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val w = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                    if (w > 0.07) ultimoMovimiento = SystemClock.uptimeMillis()
                }
                else -> {
                    val a = if (hayGravedad) 0.2f else 1f
                    for (i in 0..2) g[i] += a * (e.values[i] - g[i])
                    hayGravedad = true
                    if (optNivel) guia.invalidate()
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor, a: Int) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        orientacion.disable()
        sonido.release()
    }

    private fun terminar() {
        if (destino != null) { setResult(RESULT_CANCELED); finish(); return }
        setResult(RESULT_OK, Intent().putExtra("indice", indice).putExtra("fotos", fotosTomadas))
        finish()
    }

    // ---------- Interfaz ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    /** Botones de la fila de disparo: se achican en pantallas angostas para que quepan los seis. */
    private fun dpF(v: Int) = dp((v * minOf(1f, resources.configuration.screenWidthDp / 400f)).toInt())

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

    private fun chipChico(f: () -> Unit) = TextView(this).apply {
        textSize = 13f; typeface = medio; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 1
        background = fondo(gris, 20); setPadding(dp(10), 0, dp(10), 0); isClickable = true
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
        guia = Guia()
        raiz.addView(guia, FrameLayout.LayoutParams(-1, -1))
        lupa = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.BLACK); setStroke(dp(3), acento) }
            setPadding(dp(3), dp(3), dp(3), dp(3)); clipToOutline = true
        }
        raiz.addView(lupa, FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER_HORIZONTAL))
        txtAviso = TextView(this).apply {
            textSize = 14f; typeface = medio; setTextColor(tinta); gravity = Gravity.CENTER; visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(acento) }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { if (bloqueado) desbloquear(true) else if (macroAuto) salirMacro(true) }
        }
        raiz.addView(txtAviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))

        // --- Arriba (compacto): cerrar, código, conteo, resolución/modo; luego navegación + General/Detalle ---
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(panel); setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        barraArriba = arriba
        val fila1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fila1.addView(botonRedondo(R.drawable.ic_cerrar, 44) { terminar() })
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0)
        }
        txtTitulo = TextView(this).apply {
            textSize = 18f; typeface = medio; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        txtConteo = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#C9D3DD")); maxLines = 1 }
        textos.addView(txtTitulo); textos.addView(txtConteo)
        fila1.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        btnRes = chipChico { cambiarResolucion() }
        btnExt = chipChico { cambiarExtension() }
        fila1.addView(btnRes, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(6) })
        fila1.addView(btnExt, LinearLayout.LayoutParams(-2, dp(40)))
        btnOpciones = chipChico { menuOpciones() }.apply { text = "⋮"; textSize = 20f; contentDescription = "Opciones de cámara" }
        fila1.addView(btnOpciones, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        arriba.addView(fila1)

        val fila2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        btnAnterior = chip("") { saltar(-1) }
        btnGeneral = chip("General") { cambiarModo("general") }
        btnDetalle = chip("Detalle") { cambiarModo("detalle") }
        btnSiguiente = chip("") { saltar(1) }
        for (b in listOf(btnAnterior, btnGeneral, btnDetalle, btnSiguiente)) {
            b.textSize = 15f; b.setPadding(dp(4), 0, dp(4), 0); b.ellipsize = android.text.TextUtils.TruncateAt.END
            fila2.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        }
        btnPendiente = chip("⏭") { saltarPendiente() }.apply { contentDescription = "Siguiente pendiente"; setPadding(0, 0, 0, 0) }
        fila2.addView(btnPendiente, LinearLayout.LayoutParams(dp(50), dp(48)))
        arriba.addView(fila2)
        fila2V = fila2
        raiz.addView(arriba, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // --- Abajo: lentes flotando sobre la imagen y fila de disparo ---
        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        panelBrillo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(panel) }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun botonPaso(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 24f; typeface = medio; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(gris) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            isClickable = true; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        panelBrillo.addView(botonPaso("−") { fijarBrillo(brillo - 1) })
        barraBrillo = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(acento); thumbTintList = ColorStateList.valueOf(acento)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, deUsuario: Boolean) { if (deUsuario) fijarBrillo(v + brilloMin) }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
            })
        }
        panelBrillo.addView(barraBrillo, LinearLayout.LayoutParams(0, dp(46), 1f))
        panelBrillo.addView(botonPaso("+") { fijarBrillo(brillo + 1) })
        txtBrillo = TextView(this).apply {
            textSize = 14f; typeface = medio; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 1
            background = fondo(gris, 20); isClickable = true
            setOnClickListener { fijarBrillo(0) }
            contentDescription = "Volver el brillo a 0"
        }
        panelBrillo.addView(txtBrillo, LinearLayout.LayoutParams(dp(70), dp(46)).apply { marginStart = dp(6) })
        abajo.addView(panelBrillo, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        // Panel de enfoque: Auto | − barra (Cerca ↔ Lejos) + | distancia
        panelEnfoque = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(panel) }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        btnAuto = TextView(this).apply {
            text = "Auto"; textSize = 14f; typeface = medio; gravity = Gravity.CENTER; maxLines = 1
            isClickable = true; setOnClickListener { enfoqueAuto() }
            contentDescription = "Enfoque automático"
        }
        panelEnfoque.addView(btnAuto, LinearLayout.LayoutParams(dp(62), dp(46)).apply { marginEnd = dp(4) })
        val paso = PASOS_ENFOQUE / 40
        panelEnfoque.addView(botonPaso("−") { fijarEnfoque(enfoque - paso) })
        val columna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        barraEnfoque = SeekBar(this).apply {
            max = PASOS_ENFOQUE
            progressTintList = ColorStateList.valueOf(acento); thumbTintList = ColorStateList.valueOf(acento)
            setPadding(dp(14), dp(6), dp(14), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, deUsuario: Boolean) { if (deUsuario) fijarEnfoque(v) }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
            })
        }
        columna.addView(barraEnfoque, LinearLayout.LayoutParams(-1, dp(30)))
        val etiquetas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        for ((t, g) in listOf("Cerca" to Gravity.START, "Lejos" to Gravity.END))
            etiquetas.addView(TextView(this).apply { text = t; textSize = 11f; gravity = g; setTextColor(Color.parseColor("#C9D3DD")) },
                LinearLayout.LayoutParams(0, -2, 1f))
        columna.addView(etiquetas)
        panelEnfoque.addView(columna, LinearLayout.LayoutParams(0, dp(46), 1f))
        panelEnfoque.addView(botonPaso("+") { fijarEnfoque(enfoque + paso) })
        txtEnfoque = TextView(this).apply {
            textSize = 14f; typeface = medio; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 1
            background = fondo(gris, 20)
        }
        panelEnfoque.addView(txtEnfoque, LinearLayout.LayoutParams(dp(70), dp(46)).apply { marginStart = dp(6) })
        abajo.addView(panelEnfoque, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        filaLentes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(8)) }
        abajo.addView(filaLentes)

        val filaDisparo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(panel); setPadding(dpF(14), dp(8), dpF(14), dp(10))
        }
        miniatura = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(gris); setStroke(dp(2), Color.WHITE) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) }
            }
            setOnClickListener { revisarUltima() }
        }
        filaDisparo.addView(miniatura, LinearLayout.LayoutParams(dpF(54), dpF(54)))
        btnEstrella = TextView(this).apply {
            text = "★"; textSize = 24f; gravity = Gravity.CENTER; isClickable = true
            contentDescription = "Marcar la última foto para informe"
            setOnClickListener { alternarEstrella() }
        }
        filaDisparo.addView(btnEstrella, LinearLayout.LayoutParams(dpF(46), dpF(46)).apply { marginStart = dpF(6) })
        filaDisparo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fun botonTexto(desc: String, f: () -> Unit) = TextView(this).apply {
            textSize = 12f; typeface = medio; gravity = Gravity.CENTER; maxLines = 2; setLineSpacing(0f, 0.9f)
            isClickable = true; contentDescription = desc; setOnClickListener { f() }
        }
        btnBrillo = botonTexto("Brillo") { alternarPanel(panelBrillo) }.apply { visibility = View.GONE }
        filaDisparo.addView(btnBrillo, LinearLayout.LayoutParams(dpF(54), dpF(54)))
        filaDisparo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val disparador = View(this).apply {
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(90, 0, 0, 0)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), acento) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            isClickable = true; contentDescription = "Tomar foto (también las teclas de volumen)"
            setOnClickListener { disparar() }
        }
        filaDisparo.addView(disparador, LinearLayout.LayoutParams(dpF(76), dpF(76)))
        filaDisparo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btnEnfoque = botonTexto("Enfoque") { alternarPanel(panelEnfoque) }.apply { visibility = View.GONE }
        filaDisparo.addView(btnEnfoque, LinearLayout.LayoutParams(dpF(54), dpF(54)))
        filaDisparo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btnLinterna = botonRedondo(R.drawable.ic_linterna, 54) { alternarLinterna() }.apply { layoutParams = LinearLayout.LayoutParams(dpF(54), dpF(54)) }
        filaDisparo.addView(btnLinterna)
        abajo.addView(filaDisparo, LinearLayout.LayoutParams(-1, -2))
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
        val gestos = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!escala.isInProgress && areaFoto().contains(e.x, e.y)) bloquear(e.x, e.y)
            }
        })
        vista.setOnTouchListener { v, e ->
            escala.onTouchEvent(e)
            gestos.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !escala.isInProgress && e.eventTime - e.downTime < 300) {
                if (areaFoto().contains(e.x, e.y)) { if (bloqueado) desbloquear(false); enfocar(e.x, e.y) }
                v.performClick()
            }
            true
        }
        setContentView(raiz)
        if (destino != null) {
            fila2V.visibility = View.GONE; btnEstrella.visibility = View.GONE; miniatura.visibility = View.INVISIBLE
        }
        recontar()
        refrescar()
        Equipo.aviso(this)?.let { a ->
            vista.post {
                mostrarAviso("⚠ $a")
                manejador.postDelayed({ if (!bloqueado && !macroAuto && txtAviso.text.startsWith("⚠")) txtAviso.visibility = View.GONE }, 6000)
            }
        }
    }

    /** Fotos por carpeta de todas las singularidades del recorrido (una sola consulta). */
    private fun recontar() {
        if (destino != null) return
        var pre = rutas.reduce { a, b -> a.commonPrefixWith(b) }
        pre = pre.substring(0, pre.lastIndexOf('/') + 1)
        val res = HashMap<String, Pair<Int, Int>>()
        val enRecorrido = rutas.toHashSet()
        runCatching {
            contentResolver.query(coleccion, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%'",
                arrayOf("$pre%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val ruta = c.getString(0) ?: continue
                    if (ruta !in enRecorrido) continue
                    val nombre = c.getString(1) ?: ""
                    if (nombre.contains("_marcada")) continue
                    val (g, o) = res[ruta] ?: (0 to 0)
                    res[ruta] = if (nombre.contains("_general")) (g + 1 to o) else (g to o + 1)
                }
            }
        }
        conteoRutas = res
    }

    private fun grupoDe(i: Int) = titulos[i].substringAfter(" · ", "")

    /** 0 = sin fotos, 1 = incompleta, 2 = general + al menos otra foto (igual que la grilla). */
    private fun estadoDe(i: Int): Int {
        val (g, o) = conteoRutas[rutas[i]] ?: (0 to 0)
        return when { g + o == 0 -> 0; g > 0 && o > 0 -> 2; else -> 1 }
    }

    private fun saltarPendiente() {
        for (k in 1 until rutas.size + 1) {
            val j = (indice + k) % rutas.size
            if (estadoDe(j) != 2) {
                indice = j; refrescar()
                aviso(titulos[j] + if (estadoDe(j) == 0) " · sin fotos" else " · incompleta")
                cambiarModo(if ((conteoRutas[rutas[j]]?.first ?: 0) == 0) "general" else "detalle")
                return
            }
        }
        aviso("Todas completas ✓")
    }

    private fun estiloChip(t: TextView, activo: Boolean) {
        t.background = fondo(if (activo) acento else gris, 26)
        t.setTextColor(if (activo) tinta else Color.WHITE)
    }

    private fun refrescar() {
        txtTitulo.text = titulos[indice]
        if (destino != null) txtConteo.text = intent.getStringExtra("subtitulo") ?: "Una foto · disparador o teclas de volumen"
        else {
            val (g, d) = conteoRutas[rutas[indice]] ?: (0 to 0)
            val grupo = grupoDe(indice)
            val delGrupo = rutas.indices.filter { grupoDe(it) == grupo }
            val faltan = delGrupo.count { estadoDe(it) != 2 }
            txtConteo.text = "Gen: $g · Det: $d · " + (if (faltan == 0) "pared completa ✓" else "faltan $faltan de ${delGrupo.size}") +
                if (fotosTomadas > 0) " · $fotosTomadas en sesión" else ""
            val hayPend = rutas.indices.any { estadoDe(it) != 2 }
            btnPendiente.alpha = if (hayPend) 1f else 0.35f
            // solo el código; si el salto cambia de pared, se agrega la pared (en otra línea)
            fun etiqueta(j: Int): String {
                val pared = titulos[j].substringAfter(" · ")
                return if (pared == titulos[indice].substringAfter(" · ")) codigos[j] else "${codigos[j]}\n$pared"
            }
            btnAnterior.text = if (indice > 0) "‹  ${etiqueta(indice - 1)}" else "—"
            btnSiguiente.text = if (indice < rutas.size - 1) "${etiqueta(indice + 1)}  ›" else "—"
            for (b in listOf(btnAnterior, btnSiguiente)) { b.maxLines = 2; b.textSize = if (b.text.contains('\n')) 11f else 15f }
            btnAnterior.alpha = if (indice > 0) 1f else 0.35f
            btnSiguiente.alpha = if (indice < rutas.size - 1) 1f else 0.35f
            estiloChip(btnGeneral, modo == "general")
            estiloChip(btnDetalle, modo == "detalle")
        }
        btnRes.text = captura?.resolutionInfo?.resolution?.let { "${mp(it)} MP" }
            ?: if (prefs.getString("cam_res", "normal") == "max") "Máx" else "12 MP"
        btnExt.text = nombreExt(extActual)
        fun redondo(v: TextView, activo: Boolean) {
            v.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (activo) acento else gris) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            v.setTextColor(if (activo) tinta else Color.WHITE)
        }
        btnBrillo.text = "☀\n" + textoEv(brillo)
        redondo(btnBrillo, brillo != 0 || panelBrillo.visibility == View.VISIBLE)
        btnEnfoque.text = if (enfoqueManual) "MF\n" + textoDistancia() else "AF"
        redondo(btnEnfoque, enfoqueManual || panelEnfoque.visibility == View.VISIBLE)
        btnAuto.background = fondo(if (enfoqueManual) gris else acento, 20)
        btnAuto.setTextColor(if (enfoqueManual) Color.WHITE else tinta)
        txtEnfoque.text = if (enfoqueManual) textoDistancia() else "Auto"
        barraEnfoque.progress = enfoque
        btnEstrella.alpha = if (ultimaUri != null) 1f else 0.35f
        redondo(btnEstrella, ultimaEstrella)
        actualizarLupa()
        txtBrillo.text = if (brillo == 0) "0 EV" else textoEv(brillo)
        barraBrillo.max = brilloMax - brilloMin
        barraBrillo.progress = brillo - brilloMin
        btnExt.visibility = if (extDisponibles.size > 1) View.VISIBLE else View.GONE
        btnLinterna.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (linterna) acento else gris) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        btnLinterna.setImageDrawable(icono(R.drawable.ic_linterna, if (linterna) tinta else Color.WHITE, 24))
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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun vincular() {
        val p = proveedor ?: return
        if (tamanos.isEmpty()) leerTamanos(p)
        val pref = prefs.getString("cam_res", "normal") ?: "normal"
        val maxima = pref != "normal"
        val pedido: Size? = when (pref) {
            "normal", "max" -> if (pref == "normal") Size(4000, 3000) else null
            else -> runCatching { pref.split("x").let { Size(it[0].toInt(), it[1].toInt()) } }.getOrNull() ?: Size(4000, 3000)
        }
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                if (pedido == null) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                else ResolutionStrategy(pedido, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .apply {
                if (pedido == null || pedido.width.toLong() * pedido.height > 13_000_000L)
                    setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            }
            .build()
        val pb = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
        if (extActual == ExtensionMode.NONE) runCatching { Camera2Interop.Extender(pb).setSessionCaptureCallback(vigiaMacro) }
        val preview = pb.build().also { it.setSurfaceProvider(vista.surfaceProvider) }
        val cap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(selector)
            .setTargetRotation(Surface.ROTATION_0)
            .setFlashMode(if (optFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()
        var sel = CameraSelector.DEFAULT_BACK_CAMERA
        if (extActual != ExtensionMode.NONE) extensiones?.let { sel = it.getExtensionEnabledCameraSelector(sel, extActual) }
        try {
            p.unbindAll()
            camara = p.bindToLifecycle(this, sel, preview, cap)
            vistaPrevia = preview
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
        prepararBrillo(c)
        prepararEnfoque(c)
        c.cameraInfo.zoomState.observe(this) { z -> zoomActual = z.zoomRatio; zoomMin = z.minZoomRatio; armarLentes() }
        bloqueado = false; aeBloqueado = false; txtAviso.visibility = View.GONE
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
                textSize = 13f; typeface = medio; gravity = Gravity.CENTER
                setTextColor(if (activo) tinta else Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (activo) acento else panel) }
                isClickable = true
                setOnClickListener {
                    if (macroAuto) { macroAuto = false; txtAviso.visibility = View.GONE }
                    macroPausa = SystemClock.uptimeMillis() + 8000
                    c.cameraControl.setZoomRatio(o)
                }
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(5), 0, dp(5), 0) }
            })
        }
    }

    /** Rectángulo de la vista donde realmente se ve la foto (FIT_CENTER deja franjas negras). */
    private fun areaFoto(): android.graphics.RectF {
        val w = vista.width.toFloat(); val h = vista.height.toFloat()
        val r = vistaPrevia?.resolutionInfo?.resolution
        // la pantalla está fija en vertical: el lado largo del sensor va en altura
        val prop = if (r != null) maxOf(r.width, r.height).toFloat() / minOf(r.width, r.height) else 4f / 3f
        var iw = w; var ih = w * prop
        if (ih > h) { ih = h; iw = h / prop }
        val x0 = (w - iw) / 2; val y0 = (h - ih) / 2
        return android.graphics.RectF(x0, y0, x0 + iw, y0 + ih)
    }

    private fun enfocar(x: Float, y: Float) {
        val c = camara ?: return
        val punto = vista.meteringPointFactory.createPoint(x, y)
        val modos = if (enfoqueManual) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val accion = FocusMeteringAction.Builder(punto, modos)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        runCatching { c.cameraControl.startFocusAndMetering(accion) }
        // el anillo queda completo dentro de la foto aunque se toque cerca del borde
        val a = areaFoto(); val r = dp(42).toFloat()
        val cx = if (a.width() > 2 * r) x.coerceIn(a.left + r, a.right - r) else a.centerX()
        val cy = if (a.height() > 2 * r) y.coerceIn(a.top + r, a.bottom - r) else a.centerY()
        anilloFoco.translationX = cx - r; anilloFoco.translationY = cy - r
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

    /** Rango de brillo que ofrece la cámara (o el modo HDR/Noche); vuelve a aplicar el valor recordado. */
    private fun prepararBrillo(c: Camera) {
        val e = c.cameraInfo.exposureState
        if (!e.isExposureCompensationSupported) {
            brilloMin = 0; brilloMax = 0; brilloPaso = 0f
            btnBrillo.visibility = View.GONE; panelBrillo.visibility = View.GONE; refrescar()
            return
        }
        brilloMin = e.exposureCompensationRange.lower
        brilloMax = e.exposureCompensationRange.upper
        brilloPaso = e.exposureCompensationStep.toFloat()
        btnBrillo.visibility = View.VISIBLE
        if (brilloRecomendado && brilloPaso > 0f) {
            brilloRecomendado = false
            brillo = Math.round(BRILLO_RECOMENDADO / brilloPaso)
            prefs.edit().putInt("cam_brillo", brillo.coerceIn(brilloMin, brilloMax)).apply()
        }
        brillo = brillo.coerceIn(brilloMin, brilloMax)
        if (brillo != 0) runCatching { c.cameraControl.setExposureCompensationIndex(brillo) }
    }

    /** Muestra un panel (brillo o enfoque) y cierra el otro; tocar de nuevo lo cierra. */
    private fun alternarPanel(p: LinearLayout) {
        val abrir = p.visibility != View.VISIBLE
        panelBrillo.visibility = View.GONE; panelEnfoque.visibility = View.GONE
        if (abrir) p.visibility = View.VISIBLE
        refrescar()
    }

    /** Enfoque manual solo si la cámara informa distancia mínima de enfoque (si no, es foco fijo). */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun prepararEnfoque(c: Camera) {
        dioptriasMin = runCatching {
            Camera2CameraInfo.from(c.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        }.getOrNull() ?: 0f
        if (dioptriasMin <= 0f || extActual != ExtensionMode.NONE) {
            enfoqueManual = false
            btnEnfoque.visibility = View.GONE; panelEnfoque.visibility = View.GONE
        } else btnEnfoque.visibility = View.VISIBLE
        aplicarEnfoque()
        refrescar()
    }

    private fun dioptrias() = dioptriasMin * (1f - enfoque.toFloat() / PASOS_ENFOQUE)

    private fun textoDistancia(): String {
        val d = dioptrias()
        if (d <= 0.02f) return "∞"
        val cm = 100f / d
        return if (cm >= 100f) "%.1f m".format(cm / 100f).replace('.', ',') else "${Math.round(cm)} cm"
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun aplicarEnfoque() {
        val c = camara ?: return
        val b = CaptureRequestOptions.Builder()
        if (enfoqueManual) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, dioptrias())
        }
        if (aeBloqueado) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        runCatching { Camera2CameraControl.from(c.cameraControl).captureRequestOptions = b.build() }
            .onFailure { if (enfoqueManual) { enfoqueManual = false; aviso("Este modo no permite enfoque manual") } }
    }

    private fun fijarEnfoque(v: Int) {
        if (camara == null || dioptriasMin <= 0f) return
        if (!enfoqueManual) runCatching { camara?.cameraControl?.cancelFocusAndMetering() }
        enfoqueManual = true
        enfoque = v.coerceIn(0, PASOS_ENFOQUE)
        aplicarEnfoque(); refrescar()
    }

    private fun enfoqueAuto() {
        enfoqueManual = false
        aplicarEnfoque(); refrescar()
        aviso("Enfoque automático")
    }

    private fun fijarBrillo(v: Int) {
        val c = camara ?: return
        val n = v.coerceIn(brilloMin, brilloMax)
        if (n == brillo && v == n) { refrescar(); return }
        brillo = n
        runCatching { c.cameraControl.setExposureCompensationIndex(n) }
        prefs.edit().putInt("cam_brillo", n).apply()
        refrescar()
    }

    /** Valor en EV con signo y coma decimal, ej. "+0,7", "−1,3", "0". */
    private fun textoEv(i: Int): String {
        val ev = i * brilloPaso
        if (i == 0 || brilloPaso == 0f) return "0"
        val t = "%.1f".format(Math.abs(ev)).replace('.', ',').removeSuffix(",0")
        return (if (ev > 0) "+" else "−") + t
    }

    // ---------- Aviso flotante, bloqueo AE/AF, lupa, estrella, opciones ----------

    private fun mostrarAviso(t: String) {
        txtAviso.text = t; txtAviso.visibility = View.VISIBLE
        val a = areaFoto()
        txtAviso.translationY = maxOf(a.top, barraArriba.bottom.toFloat()) + dp(10)
    }

    /** Mantener presionado: enfoca y mide en ese punto y deja fijos el enfoque y el brillo. */
    private fun bloquear(x: Float, y: Float) {
        val c = camara ?: return
        val punto = vista.meteringPointFactory.createPoint(x, y)
        val modos = if (enfoqueManual) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val accion = FocusMeteringAction.Builder(punto, modos).disableAutoCancel().build()
        aeBloqueado = false; aplicarEnfoque()
        bloqueado = true
        vista.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        mostrarAviso("🔒 AE/AF bloqueado · tocar aquí para soltar")
        val r = dp(42).toFloat(); val a = areaFoto()
        anilloFoco.animate().cancel()
        anilloFoco.translationX = x.coerceIn(a.left + r, a.right - r) - r
        anilloFoco.translationY = y.coerceIn(a.top + r, a.bottom - r) - r
        anilloFoco.alpha = 1f; anilloFoco.scaleX = 1f; anilloFoco.scaleY = 1f
        runCatching {
            val f = c.cameraControl.startFocusAndMetering(accion)
            f.addListener({ if (bloqueado) { aeBloqueado = true; aplicarEnfoque() } }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun desbloquear(avisar: Boolean) {
        if (!bloqueado) return
        bloqueado = false; aeBloqueado = false
        aplicarEnfoque()
        runCatching { camara?.cameraControl?.cancelFocusAndMetering() }
        txtAviso.visibility = View.GONE
        anilloFoco.animate().alpha(0f).setDuration(200).start()
        if (avisar) aviso("AE/AF libre")
    }

    /** Lupa: centro de la imagen ampliado ×3 mientras el enfoque manual está abierto. */
    private val refrescoLupa = object : Runnable {
        override fun run() {
            if (lupa.visibility != View.VISIBLE) return
            val src = runCatching { vista.bitmap }.getOrNull()
            if (src != null) {
                val a = areaFoto()
                val lado = (minOf(a.width(), a.height()) / 3f).toInt().coerceAtLeast(16)
                val x0 = (a.centerX() - lado / 2).toInt().coerceIn(0, src.width - lado)
                val y0 = (a.centerY() - lado / 2).toInt().coerceIn(0, src.height - lado)
                val rec = runCatching { Bitmap.createBitmap(src, x0, y0, lado, lado) }.getOrNull()
                if (rec != null) lupa.setImageBitmap(rec)
                if (rec !== src) src.recycle()
            }
            manejador.postDelayed(this, 140)
        }
    }

    private fun actualizarLupa() {
        val ver = enfoqueManual && panelEnfoque.visibility == View.VISIBLE
        if (ver == (lupa.visibility == View.VISIBLE)) return
        lupa.visibility = if (ver) View.VISIBLE else View.GONE
        manejador.removeCallbacks(refrescoLupa)
        if (ver) {
            lupa.post {
                val a = areaFoto()
                lupa.translationY = maxOf(a.top, barraArriba.bottom.toFloat()) + dp(56)
            }
            manejador.post(refrescoLupa)
        }
    }

    private fun alternarEstrella() {
        if (ultimaUri == null) { aviso("Primero toma una foto"); return }
        val r = Informe.alternar(contentResolver, ultimaRuta, ultimoNombre)
        if (r == null) { aviso("No se pudo guardar la marca"); return }
        ultimaEstrella = r
        aviso(if (r) "★ $ultimoNombre para informe" else "Sin marca de informe")
        refrescar()
    }

    private fun menuOpciones() {
        val nombres = arrayOf("Disparo estabilizado (espera a que esté quieto)", "Flash al disparar",
            "Cuadrícula", "Nivel (horizonte)", "Macro automática (0,6× al acercarse)")
        val marcas = booleanArrayOf(optEstab, optFlash, optGrilla, optNivel, optMacro)
        AlertDialog.Builder(this).setTitle("Opciones de cámara")
            .setMultiChoiceItems(nombres, marcas) { _, i, v -> marcas[i] = v }
            .setPositiveButton("Listo") { _, _ ->
                val flashAntes = optFlash
                optEstab = marcas[0]; optFlash = marcas[1]; optGrilla = marcas[2]; optNivel = marcas[3]; optMacro = marcas[4]
                prefs.edit().putBoolean("cam_estab", optEstab).putBoolean("cam_flash", optFlash)
                    .putBoolean("cam_grilla", optGrilla).putBoolean("cam_nivel", optNivel).putBoolean("cam_macro", optMacro).apply()
                if (optFlash != flashAntes) captura?.flashMode = if (optFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                if (optFlash && linterna) aviso("Con la linterna encendida el flash no se usa")
                if (!optMacro && macroAuto) salirMacro(false)
                guia.invalidate()
            }
            .setNeutralButton("Recomendada") { _, _ -> configuracionRecomendada() }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Configuración recomendada para el interior de la caldera. */
    private fun configuracionRecomendada() {
        optEstab = true; optFlash = false; optGrilla = true; optNivel = true; optMacro = true
        linterna = true; extActual = ExtensionMode.NONE
        enfoqueManual = false
        if (bloqueado) desbloquear(false)
        if (macroAuto) salirMacro(false)
        brilloRecomendado = true
        prefs.edit().putBoolean("cam_estab", true).putBoolean("cam_flash", false).putBoolean("cam_grilla", true)
            .putBoolean("cam_nivel", true).putBoolean("cam_macro", true).putBoolean("cam_linterna", true)
            .putString("cam_res", "normal").putInt("cam_ext", ExtensionMode.NONE).apply()
        lentesArmados = ""
        vincular()
        camara?.cameraControl?.setZoomRatio(1f)
        aviso("Configuración recomendada: 12 MP · Normal · linterna · brillo −0,7 · AF")
    }

    // ---------- Macro automática ----------
    // Con 1× y el enfoque topando en su distancia mínima por 1 s → pasa a 0,6× (el gran angular enfoca más cerca).
    // Si la app hizo el cambio y el objeto vuelve a estar lejos por 1,5 s → regresa a 1×.

    private val vigiaMacro = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            if (!optMacro || enfoqueManual || bloqueado || dioptriasMin <= 0f || zoomMin > 0.95f) { macroDesde = 0L; return }
            val d = res.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
            val ahora = SystemClock.uptimeMillis()
            if (ahora < macroPausa) { macroDesde = 0L; return }
            val cond = if (!macroAuto) zoomActual in 0.95f..1.3f && d >= 0.9f * dioptriasMin
                       else zoomActual < 0.95f && d < 0.6f * dioptriasMin
            if (!cond) { macroDesde = 0L; return }
            if (macroDesde == 0L) { macroDesde = ahora; return }
            if (ahora - macroDesde >= if (macroAuto) 1500 else 1000) {
                macroDesde = 0L; macroPausa = ahora + 2000
                runOnUiThread { if (macroAuto) salirMacro(false) else entrarMacro() }
            }
        }
    }

    private fun entrarMacro() {
        val c = camara ?: return
        macroAuto = true
        c.cameraControl.setZoomRatio(zoomMin)
        if (!bloqueado) mostrarAviso("Macro 0,6× · tocar aquí para volver a 1×")
    }

    private fun salirMacro(manual: Boolean) {
        macroAuto = false
        if (manual) macroPausa = SystemClock.uptimeMillis() + 8000
        camara?.cameraControl?.setZoomRatio(1f)
        if (!bloqueado) txtAviso.visibility = View.GONE
    }

    /** Cuadrícula de tercios y nivel de horizonte dibujados sobre el área de la foto (no recibe toques). */
    private inner class Guia : View(this@CamaraActivity) {
        private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255); strokeWidth = dp(1).toFloat() }
        private val nivel = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(3).toFloat(); strokeCap = Paint.Cap.ROUND }
        override fun onDraw(cv: Canvas) {
            val a = areaFoto()
            if (optGrilla) for (k in 1..2) {
                val x = a.left + a.width() * k / 3; val y = a.top + a.height() * k / 3
                cv.drawLine(x, a.top, x, a.bottom, linea); cv.drawLine(a.left, y, a.right, y, linea)
            }
            if (!optNivel || !hayGravedad) return
            val n = Math.sqrt((g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).toDouble()).toFloat()
            if (n < 1f || Math.abs(g[2]) > 0.8f * n) return          // teléfono casi plano: el nivel no aplica
            val ang = Math.toDegrees(Math.atan2(g[0].toDouble(), g[1].toDouble()))
            val desvio = ((ang % 90 + 135) % 90) - 45                  // -45…45 respecto a la orientación más cercana
            val ok = Math.abs(desvio) < 1.0
            // dirección del horizonte en pantalla: perpendicular a "arriba" = (gx, -gy)
            val h = Math.hypot(g[0].toDouble(), g[1].toDouble()).toFloat()
            val dx = g[1] / h; val dy = g[0] / h
            val L = a.width() * 0.22f; val cx = a.centerX(); val cy = a.centerY()
            nivel.color = if (ok) Color.parseColor("#3DDC84") else Color.WHITE
            nivel.setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
            cv.drawLine(cx - dx * L, cy - dy * L, cx + dx * L, cy + dy * L, nivel)
            // marcas fijas de referencia a los lados
            nivel.color = if (ok) Color.parseColor("#3DDC84") else acento
            val m = dp(14).toFloat()
            if (Math.abs(dx) >= Math.abs(dy)) {
                cv.drawLine(cx - L - m * 2, cy, cx - L - m, cy, nivel); cv.drawLine(cx + L + m, cy, cx + L + m * 2, cy, nivel)
            } else {
                cv.drawLine(cx, cy - L - m * 2, cx, cy - L - m, nivel); cv.drawLine(cx, cy + L + m, cx, cy + L + m * 2, nivel)
            }
        }
    }

    private fun mp(z: Size): String {
        val m = z.width.toLong() * z.height / 1_000_000.0
        return if (m < 10) "%.1f".format(m).replace('.', ',').removeSuffix(",0") else Math.round(m).toString()
    }

    /** Tamaños 4:3 que la cámara trasera ofrece a otras apps (normales y de alta resolución). */
    private val tamanos = mutableListOf<Size>()

    @OptIn(ExperimentalCamera2Interop::class)
    private fun leerTamanos(p: ProcessCameraProvider) {
        runCatching {
            val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull() ?: return
            val c2 = Camera2CameraInfo.from(info)
            val todos = mutableListOf<Size>()
            c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { m ->
                m.getOutputSizes(ImageFormat.JPEG)?.let { todos.addAll(it) }
                m.getHighResolutionOutputSizes(ImageFormat.JPEG)?.let { todos.addAll(it) }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31)
                c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    ?.getOutputSizes(ImageFormat.JPEG)?.let { todos.addAll(it) }
            val cuatroTercios = todos.filter { maxOf(it.width, it.height) * 3 == minOf(it.width, it.height) * 4 }
                .filter { it.width.toLong() * it.height >= 1_900_000L }
                .distinctBy { mp(it) }
                .sortedBy { it.width.toLong() * it.height }
            // como máximo 7 opciones, repartidas entre la menor y la mayor
            val elegidos = if (cuatroTercios.size <= 7) cuatroTercios else {
                val metas = listOf(2.0, 5.0, 8.0, 12.0, 24.0, 50.0, 1e9)
                metas.mapNotNull { meta -> cuatroTercios.minByOrNull { Math.abs(it.width.toLong() * it.height / 1e6 - meta) } }.distinct()
            }
            tamanos.clear(); tamanos.addAll(elegidos)
        }
    }

    private fun cambiarResolucion() {
        val opciones = tamanos.toList()
        if (opciones.isEmpty()) {
            val max = prefs.getString("cam_res", "normal") == "max"
            prefs.edit().putString("cam_res", if (max) "normal" else "max").apply()
            vincular(); return
        }
        val actual = captura?.resolutionInfo?.resolution
        val etiquetas = opciones.map { z ->
            val m = z.width.toLong() * z.height / 1e6
            "${mp(z)} MP  (${maxOf(z.width, z.height)}×${minOf(z.width, z.height)})" +
                when { m > 30 -> " · archivos pesados"; m in 11.0..13.5 -> " · recomendado"; else -> "" }
        }.toTypedArray()
        val marcado = opciones.indexOfFirst { actual != null && mp(it) == mp(actual) }
        AlertDialog.Builder(this).setTitle("Resolución de las fotos")
            .setSingleChoiceItems(etiquetas, marcado) { d, i ->
                val z = opciones[i]
                prefs.edit().putString("cam_res", "${z.width}x${z.height}").apply()
                d.dismiss(); vincular()
                aviso("Resolución ${mp(z)} MP")
            }
            .setNegativeButton("Cancelar", null).show()
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
        if (captura == null || ocupado) return
        ocupado = true
        if (!optEstab || giro == null) { capturar(); return }
        val inicio = SystemClock.uptimeMillis()
        var avisado = false
        val espera = object : Runnable {
            override fun run() {
                val ahora = SystemClock.uptimeMillis()
                val quieto = ahora - ultimoMovimiento >= 150
                if (quieto || ahora - inicio > 1500) {
                    if (avisado && !bloqueado && !macroAuto) txtAviso.visibility = View.GONE
                    capturar(); return
                }
                if (!avisado && ahora - inicio > 120) { avisado = true; mostrarAviso("Mantén quieto…") }
                manejador.postDelayed(this, 25)
            }
        }
        espera.run()
    }

    private fun capturar() {
        val cap = captura ?: run { ocupado = false; return }
        destino?.let { capturarUnica(cap, it); return }
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
                recontar()
                ultimaUri = r.savedUri; ultimoNombre = nombre; ultimaRuta = ruta; ultimaEstrella = false
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

    /** Foto única: se escribe en la uri recibida y se vuelve a la pantalla que la pidió. */
    private fun capturarUnica(cap: ImageCapture, u: Uri) {
        val salida = runCatching { contentResolver.openOutputStream(u, "wt") }.getOrNull()
            ?: run { ocupado = false; aviso("No se pudo crear el archivo"); return }
        destello.alpha = 0.8f
        destello.animate().alpha(0f).setDuration(250).start()
        runCatching { sonido.play(MediaActionSound.SHUTTER_CLICK) }
        cap.takePicture(ImageCapture.OutputFileOptions.Builder(salida).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    runCatching { salida.close() }
                    setResult(RESULT_OK, Intent().putExtra("destino", u.toString()))
                    finish()
                }
                override fun onError(e: ImageCaptureException) {
                    runCatching { salida.close() }
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
                    recontar()
                    Informe.quitar(contentResolver, ultimaRuta, ultimoNombre)
                    ultimaUri = null; ultimoNombre = ""; ultimaEstrella = false
                    miniatura.setImageDrawable(null)
                    aviso("Foto eliminada"); refrescar()
                } else aviso("No se pudo eliminar")
            }.show()
    }
}
