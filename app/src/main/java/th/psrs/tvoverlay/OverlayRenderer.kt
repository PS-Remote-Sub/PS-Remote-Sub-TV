package th.psrs.tvoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws [TvState] into the overlay's full-screen [root]: the subtitle boxes [OverlayPlanner] places, the calibration
 * frame, and a status banner. Main thread only. Never changes [root]'s visibility.
 */
class OverlayRenderer(private val root: FrameLayout) {
    private val context: Context = root.context
    private val density = context.resources.displayMetrics.density
    private val padH = dp(18)
    private val padV = dp(8)
    private val gap = dp(6)

    /** Replaces everything drawn with [state] on a [screenW] x [screenH] screen, plus [banner] when not null. */
    fun render(state: TvState, screenW: Int, screenH: Int, banner: String?) {
        root.removeAllViews()
        val config = state.config
        val sample = context.getString(R.string.calibration_sample)
        val plan = OverlayPlanner.plan(config, state.zones.values, screenW, screenH, gap, sample, ::measure)
        plan.calibrationFrame?.let { addAt(frameBorder(), it) }
        val opacity = (config?.boxOpacity ?: 0.0).coerceIn(0.0, 1.0)
        for (box in plan.boxes) addAt(BoxView(context, box, opacity), box.px)
        if (banner != null) addBanner(banner)
    }

    /** Removes everything drawn. */
    fun clear() = root.removeAllViews()

    /** [Measure] with the same text and layout [BoxView] draws. */
    private fun measure(lines: List<Line>, fontPx: Float, maxWidthPx: Int): Pair<Int, Int> {
        val layout = layout(lines, fontPx, max(maxWidthPx - 2 * padH, 1))
        var textW = 0f
        for (i in 0 until layout.lineCount) textW = max(textW, layout.getLineWidth(i))
        return Pair(min(ceil(textW).toInt() + 2 * padH, maxWidthPx), layout.height + 2 * padV)
    }

    private fun layout(lines: List<Line>, fontPx: Float, width: Int): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
            textSize = fontPx
            setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, Color.BLACK)
        }
        val text = text(lines)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    /** "Name: text" per line (name in the speaker's colour), one line per row; dimmed rows at [DIM_ALPHA]. */
    private fun text(lines: List<Line>): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        for ((i, line) in lines.withIndex()) {
            if (i > 0) out.append('\n')
            val alpha = if (line.dimmed) DIM_ALPHA else 1f
            val speaker = line.row.speaker
            if (speaker != null && speaker.name.isNotEmpty()) {
                val start = out.length
                out.append(speaker.name).append(": ")
                out.setSpan(InkSpan(speakerColor(speaker.color), alpha), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val start = out.length
            out.append(line.row.text)
            out.setSpan(InkSpan(Color.WHITE, alpha), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun addAt(view: View, px: Px) {
        root.addView(view, FrameLayout.LayoutParams(px.w, px.h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = px.x
            topMargin = px.y
        })
    }

    private fun frameBorder() = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(FRAME_BORDER_PX, Color.WHITE)
        }
    }

    private fun addBanner(text: String) {
        val view = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(BANNER_COLOR)
        }
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()

    /** One rounded black box with its lines centred in it. */
    @SuppressLint("ViewConstructor")
    private inner class BoxView(context: Context, box: OverlayBox, opacity: Double) : View(context) {
        private val text = layout(box.lines, box.fontPx, max(box.px.w - 2 * padH, 1))

        init {
            background = rounded(Color.argb((opacity * 255).roundToInt(), 0, 0, 0))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(padH.toFloat(), (height - text.height) / 2f)
            text.draw(canvas)
            canvas.restore()
        }
    }

    /** Text colour and its shadow at an alpha, so a dimmed row's shadow fades with it. */
    private class InkSpan(private val color: Int, private val alpha: Float) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) {
            tp.color = withAlpha(color, alpha)
            tp.setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, withAlpha(Color.BLACK, alpha))
        }
    }

    private companion object {
        const val DIM_ALPHA = 0.6f
        const val SHADOW_RADIUS = 4f
        const val SHADOW_DY = 2f
        const val FRAME_BORDER_PX = 4
        const val DEFAULT_SPEAKER_COLOR = 0xFFFFD54F.toInt()
        const val BANNER_COLOR = 0xAA333333.toInt()
        private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

        fun speakerColor(s: String?): Int =
            if (s != null && HEX_COLOR.matches(s)) Color.parseColor(s) else DEFAULT_SPEAKER_COLOR

        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((Color.alpha(color) * alpha).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}
