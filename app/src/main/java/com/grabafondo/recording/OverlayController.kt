package com.grabafondo.recording

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import kotlin.math.abs

/**
 * Ventanas flotantes encima de otras apps mientras se graba (requiere el permiso
 * "Mostrar sobre otras apps"):
 * - Luz roja: un puntito rojo. Al tocarlo se despliega con el tiempo, "Detener" y "Ocultar".
 * - Ventanita de cámara: muestra lo que se está grabando. Se arrastra; tocarla cambia el tamaño.
 */
class OverlayController(
    private val context: Context,
    private val onStopRequested: () -> Unit,
    private val onHideIndicator: () -> Unit,
    private val onHidePreview: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var indicatorRoot: LinearLayout? = null
    private var indicatorParams: WindowManager.LayoutParams? = null
    private var indicatorDetails: LinearLayout? = null
    private var chronometer: Chronometer? = null
    private var stopButton: TextView? = null
    private var pulse: ObjectAnimator? = null
    private var stopArmed = false

    private var previewRoot: FrameLayout? = null
    private var previewParams: WindowManager.LayoutParams? = null
    private var previewView: PreviewView? = null
    private var previewLarge = false

    private val collapseRunnable = Runnable { setIndicatorExpanded(false) }
    private val disarmRunnable = Runnable { disarmStop() }

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /** Muestra u oculta cada ventana según lo que se pida. */
    fun update(showIndicator: Boolean, showPreview: Boolean, sessionStartMs: Long) {
        val allowed = canDraw()
        if (showIndicator && allowed) showIndicator(sessionStartMs) else hideIndicator()
        if (showPreview && allowed) showPreviewWindow() else hidePreviewWindow()
    }

    fun release() {
        hideIndicator()
        hidePreviewWindow()
    }

    // ---------------------------------------------------------------------------------------
    // Luz roja
    // ---------------------------------------------------------------------------------------

    private fun showIndicator(sessionStartMs: Long) {
        if (indicatorRoot != null) {
            chronometer?.base = chronometerBase(sessionStartMs)
            return
        }
        val dotSize = dp(22)
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(RED)
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
            contentDescription = "Grabando"
        }
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        val chrono = Chronometer(context).apply {
            base = chronometerBase(sessionStartMs)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), 0, dp(4), 0)
            start()
        }
        val stop = pillButton("■ Detener", RED).apply {
            setOnClickListener { onStopClicked() }
        }
        val hide = pillButton("Ocultar", Color.parseColor("#55FFFFFF")).apply {
            setOnClickListener { onHideIndicator() }
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chrono)
            addView(stop)
            addView(hide)
            visibility = View.GONE
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(dot)
            addView(details)
        }

        val params = overlayParams().apply {
            x = context.resources.displayMetrics.widthPixels - dp(48)
            y = dp(120)
        }
        // Arrastrar desde el punto rojo; un toque lo despliega o lo pliega.
        dot.setOnTouchListener(DragListener(params, root) {
            setIndicatorExpanded(indicatorDetails?.visibility != View.VISIBLE)
        })

        if (!addWindow(root, params)) {
            pulse?.cancel()
            pulse = null
            return
        }
        indicatorRoot = root
        indicatorParams = params
        indicatorDetails = details
        chronometer = chrono
        stopButton = stop
    }

    private fun hideIndicator() {
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(disarmRunnable)
        pulse?.cancel()
        pulse = null
        chronometer?.stop()
        indicatorRoot?.let { removeWindow(it) }
        indicatorRoot = null
        indicatorParams = null
        indicatorDetails = null
        chronometer = null
        stopButton = null
        stopArmed = false
    }

    private fun setIndicatorExpanded(expanded: Boolean) {
        val root = indicatorRoot ?: return
        val details = indicatorDetails ?: return
        handler.removeCallbacks(collapseRunnable)
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        root.background = if (expanded) {
            GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#DD000000"))
            }
        } else {
            null
        }
        if (!expanded) disarmStop()
        // Si se despliega a la derecha y no cabe, se mueve hacia la izquierda.
        indicatorParams?.let { params ->
            if (expanded) {
                root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val maxX = context.resources.displayMetrics.widthPixels - root.measuredWidth
                if (params.x > maxX) params.x = maxX.coerceAtLeast(0)
            }
            updateWindow(root, params)
        }
        if (expanded) handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
    }

    /** Primer toque: pide confirmación. Segundo toque (en 3 s): detiene. Evita paradas por error. */
    private fun onStopClicked() {
        handler.removeCallbacks(collapseRunnable)
        if (stopArmed) {
            disarmStop()
            onStopRequested()
            return
        }
        stopArmed = true
        stopButton?.text = "¿Detener? Toca otra vez"
        handler.removeCallbacks(disarmRunnable)
        handler.postDelayed(disarmRunnable, CONFIRM_MS)
        handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
    }

    private fun disarmStop() {
        handler.removeCallbacks(disarmRunnable)
        stopArmed = false
        stopButton?.text = "■ Detener"
    }

    // ---------------------------------------------------------------------------------------
    // Ventanita de cámara
    // ---------------------------------------------------------------------------------------

    private fun showPreviewWindow() {
        if (previewRoot != null) return
        val view = PreviewView(context).apply {
            // TextureView: la superficie aguanta que la ventana se oculte sin romper la grabación.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val rec = TextView(context).apply {
            text = "● REC"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(RED)
            }
        }
        val close = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#99000000"))
            }
            contentDescription = "Ocultar ventanita"
            setOnClickListener { onHidePreview() }
        }
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(2), RED)
            }
            clipToOutline = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(rec, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(6), dp(6), 0, 0)
            })
            addView(close, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(4), dp(4), 0)
            })
        }

        previewLarge = false
        val params = overlayParams().apply {
            width = dp(SMALL_W)
            height = dp(SMALL_H)
            x = dp(12)
            y = dp(160)
        }
        // Arrastrar la ventanita; un toque alterna tamaño pequeño/grande.
        view.setOnTouchListener(DragListener(params, root) { togglePreviewSize() })

        if (!addWindow(root, params)) return
        previewRoot = root
        previewParams = params
        previewView = view
        RecorderBus.attachPreview(view.surfaceProvider)
    }

    private fun hidePreviewWindow() {
        val root = previewRoot ?: return
        previewView?.let { RecorderBus.detachPreview(it.surfaceProvider) }
        removeWindow(root)
        previewRoot = null
        previewParams = null
        previewView = null
    }

    private fun togglePreviewSize() {
        val root = previewRoot ?: return
        val params = previewParams ?: return
        previewLarge = !previewLarge
        params.width = dp(if (previewLarge) LARGE_W else SMALL_W)
        params.height = dp(if (previewLarge) LARGE_H else SMALL_H)
        updateWindow(root, params)
    }

    // ---------------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------------

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // No roba el teclado ni los toques fuera de la propia ventanita.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (e: Exception) {
        Log.w("GrabaFondo", "No se pudo mostrar la ventana flotante", e)
        false
    }

    private fun updateWindow(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w("GrabaFondo", "No se pudo mover la ventana flotante", e)
        }
    }

    private fun removeWindow(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w("GrabaFondo", "No se pudo quitar la ventana flotante", e)
        }
    }

    private fun pillButton(label: String, color: Int) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(6) }
    }

    private fun chronometerBase(sessionStartMs: Long): Long =
        SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sessionStartMs)

    private fun dp(value: Int): Int = (value * density).toInt()

    /** Mueve la ventana al arrastrar; si apenas se mueve el dedo, cuenta como un toque. */
    private inner class DragListener(
        private val params: WindowManager.LayoutParams,
        private val window: View,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        updateWindow(window, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) onTap()
            }
            return true
        }
    }

    private companion object {
        val RED = Color.parseColor("#E53935")
        const val AUTO_COLLAPSE_MS = 6_000L
        const val CONFIRM_MS = 3_000L
        const val SMALL_W = 108
        const val SMALL_H = 144
        const val LARGE_W = 180
        const val LARGE_H = 240
    }
}
