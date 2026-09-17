package app.luoxianlv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

object PlayerUi {
    const val BLUE = 0xff0a84ff.toInt()
    const val TEXT = 0xff17191d.toInt()
    const val MUTED = 0xff697386.toInt()
    const val BG = 0xfff6f7fb.toInt()
    const val LINE = 0xffe5e8ef.toInt()
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    fun text(context: Context, value: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); letterSpacing = 0f
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL
    }
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context) = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    fun background(context: Context, color: Int, radius: Int = 8, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(radius).toFloat()
        if (border) setStroke(context.dp(1), LINE)
    }
    fun button(context: Context, label: String, icon: Int? = null, primary: Boolean = false, iconOnly: Boolean = false) =
        MaterialButton(context).apply {
            text = if (iconOnly) "" else label
            contentDescription = label; tooltipText = label
            isAllCaps = false; textSize = 14f; letterSpacing = 0f
            cornerRadius = context.dp(if (primary) 12 else 10); insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(context.dp(if (iconOnly) 12 else 16), 0, context.dp(if (iconOnly) 12 else 16), 0)
            backgroundTintList = ColorStateList.valueOf(if (primary) BLUE else 0xffffffff.toInt())
            val foreground = if (primary) 0xffffffff.toInt() else TEXT
            setTextColor(foreground); iconTint = ColorStateList.valueOf(foreground)
            if (icon != null) { setIconResource(icon); iconSize = context.dp(22); iconPadding = if (iconOnly) 0 else context.dp(6) }
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            elevation = 0f; stateListAnimator = null
        }
    fun divider(context: Context) = View(context).apply { setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(-1, context.dp(1)) }
}
