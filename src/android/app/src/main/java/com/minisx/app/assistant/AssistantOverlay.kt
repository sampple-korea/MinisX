package com.minisx.app.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.minisx.app.R

/**
 * The assistant as it appears over whatever app the user was in: an answer card and a pill with a
 * text field, a microphone and a send button along the bottom edge, with a soft glow under it. No
 * MinisX screen. Plain views (no Compose), so it can live in the voice-interaction session's window as
 * well as in a transparent activity.
 */
class AssistantOverlay(private val ctx: Context) {

    var onSubmit: (String) -> Unit = {}
    var onMic: () -> Unit = {}
    var onOpenApp: () -> Unit = {}
    var onDismiss: () -> Unit = {}

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private class MaxHeightScrollView(c: Context, private val maxPx: Int) : ScrollView(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun icon(res: Int, desc: String) = ImageButton(ctx).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        background = null
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private val screenChip = TextView(ctx).apply {
        text = ctx.getString(R.string.assistant_overlay_screen)
        setTextColor(0xFFB8B8C4.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = rounded(0xCC1B1B22.toInt(), 12)
        visibility = View.GONE
    }

    private val answer = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setLineSpacing(0f, 1.15f)
        setTextIsSelectable(true)
        movementMethod = LinkMovementMethod.getInstance()
    }

    private val openApp = TextView(ctx).apply {
        text = ctx.getString(R.string.assistant_overlay_open)
        setTextColor(0xFF9CB4FF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(10), dp(16), 0)
        setOnClickListener { onOpenApp() }
    }

    private val closeBtn = TextView(ctx).apply {
        text = ctx.getString(R.string.assistant_overlay_close)
        setTextColor(0xFFB8B8C4.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(10), 0, 0)
        setOnClickListener { onDismiss() }
    }

    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(12))
        background = rounded(0xF2141418.toInt(), 24, 0x22FFFFFF)
        isClickable = true
        visibility = View.GONE
        val scroll = MaxHeightScrollView(ctx, (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()).apply { addView(answer) }
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; addView(openApp); addView(closeBtn) })
    }

    val input = EditText(ctx).apply {
        hint = ctx.getString(R.string.assistant_overlay_hint)
        setHintTextColor(0xFF8D8D99.toInt())
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        background = null
        maxLines = 3
        imeOptions = EditorInfo.IME_ACTION_SEND
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    private val mic = icon(R.drawable.ic_assistant_mic, ctx.getString(R.string.assistant_overlay_mic))
    private val send = icon(R.drawable.ic_assistant_send, ctx.getString(R.string.assistant_overlay_send)).apply { visibility = View.GONE }

    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(4), dp(8), dp(4))
        minimumHeight = dp(56)
        background = rounded(0xF0101014.toInt(), 30, 0x33FFFFFF)
        isClickable = true
        addView(input)
        addView(mic)
        addView(send)
    }

    private val glow = View(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x887C5CFF.toInt(), 0x443B82F6, 0x00000000))
    }

    private val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(14))
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        addView(screenChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8); gravity = Gravity.START })
        addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The whole overlay; hidden (alpha 0) until [reveal], so the screen is captured without it. */
    val root: FrameLayout = FrameLayout(ctx).apply {
        setBackgroundColor(0x33000000)
        alpha = 0f
        setOnClickListener { onDismiss() }
        addView(glow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170), Gravity.BOTTOM))
        addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // Above the keyboard when it opens.
        fitsSystemWindows = false
    }

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val has = !s.isNullOrBlank()
                send.visibility = if (has) View.VISIBLE else View.GONE
                mic.visibility = if (has) View.GONE else View.VISIBLE
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { submit(); true } else false
        }
        send.setOnClickListener { submit() }
        mic.setOnClickListener { onMic() }
    }

    private fun submit() {
        val t = input.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        input.setText("")
        hideKeyboard()
        onSubmit(t)
    }

    fun reveal() { root.animate().alpha(1f).setDuration(160).start() }

    fun reset() {
        root.alpha = 0f
        card.visibility = View.GONE
        answer.text = ""
        input.setText("")
        screenChip.visibility = View.GONE
    }

    fun setScreenIncluded(included: Boolean) { screenChip.visibility = if (included) View.VISIBLE else View.GONE }

    /** The reply so far; "…" while nothing has come yet. */
    fun showAnswer(text: String?, working: Boolean) {
        if (text == null && !working) return
        card.visibility = View.VISIBLE
        answer.text = text ?: ctx.getString(R.string.assistant_overlay_working)
        openApp.visibility = View.VISIBLE
    }

    fun showNote(res: Int) {
        card.visibility = View.VISIBLE
        answer.text = ctx.getString(res)
    }

    fun setListening(on: Boolean) {
        mic.setColorFilter(if (on) 0xFF7C9CFF.toInt() else Color.WHITE)
        if (on) input.hint = ctx.getString(R.string.assistant_overlay_listening) else input.hint = ctx.getString(R.string.assistant_overlay_hint)
    }

    fun setText(t: String) { input.setText(t); input.setSelection(input.text?.length ?: 0) }

    fun showKeyboard() {
        input.requestFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}
