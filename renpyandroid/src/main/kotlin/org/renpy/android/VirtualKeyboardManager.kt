package org.renpy.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@SuppressLint("StaticFieldLeak")
object VirtualKeyboardManager {
    private const val TAG = "VirtualKeyboardManager"

    private var keyboardView: View? = null
    
    // states for modifiers
    private var isShiftActive = false
    private var isCapsLockActive = false
    private var isCtrlActive = false
    private var isAltActive = false

    // map to keep track of key views to update their text labels dynamically
    private val letterKeyViews = mutableMapOf<TextView, String>()
    private val modifierKeyViews = mutableMapOf<String, TextView>()

    private val keyRows = listOf(
        listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"),
        listOf("Esc", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "Back"),
        listOf("Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "\\"),
        listOf("Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "Enter"),
        listOf("Shift", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "↑", "Close"),
        listOf("Ctrl", "Alt", "Space", "←", "↓", "→")
    )

    @JvmStatic
    fun showKeyboard(activity: PythonSDLActivity) {
        activity.runOnUiThread {
            if (keyboardView != null) return@runOnUiThread

            val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val darkMode = prefs.getBoolean("dark_mode_enabled", false)
            val language = prefs.getString("language", "English") ?: "English"
            val locale = when (language) {
                "Español" -> java.util.Locale("es")
                "Português" -> java.util.Locale("pt")
                else -> java.util.Locale.ENGLISH
            }

            val baseConfig = activity.resources.configuration
            val config = Configuration(baseConfig)
            config.setLocale(locale)
            config.uiMode = if (darkMode) {
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            } else {
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            }

            val themedContext = activity.createConfigurationContext(config)
            themedContext.setTheme(activity.applicationInfo.theme)
            val themedInflater = LayoutInflater.from(themedContext)

            val view = themedInflater.inflate(R.layout.virtual_keyboard, activity.mFrameLayout, false)
            keyboardView = view

            // focus-blocking and system UI visibility setup
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                view.fitsSystemWindows = false
            } else {
                @Suppress("DEPRECATION")
                view.systemUiVisibility = activity.window.decorView.systemUiVisibility
            }

            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val statusVisible = windowInsets.isVisible(WindowInsetsCompat.Type.statusBars())
                val navVisible = windowInsets.isVisible(WindowInsetsCompat.Type.navigationBars())
                if (statusVisible || navVisible) {
                    activity.applyImmersiveFullscreen()
                }
                windowInsets
            }

            val keyboardDrawer = view.findViewById<CardView>(R.id.keyboard_drawer)
            val rowsContainer = view.findViewById<LinearLayout>(R.id.keyboard_rows_container)
            val dragHandleContainer = view.findViewById<View>(R.id.keyboard_drag_handle_container)

            keyboardDrawer.alpha = 0.85f

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                keyboardDrawer.post {
                    keyboardDrawer.systemGestureExclusionRects = listOf(
                        android.graphics.Rect(0, 0, keyboardDrawer.width, keyboardDrawer.height)
                    )
                }
            }

            // drag-to-move listener on bottom handle with screen-boundary clamping
            setupDragListener(view, keyboardDrawer, dragHandleContainer)

            // generate Keys
            letterKeyViews.clear()
            modifierKeyViews.clear()
            buildKeysLayout(themedInflater, themedContext, activity, rowsContainer)

            updateKeysVisuals(themedContext)

            activity.mFrameLayout.addView(view)
            activity.applyImmersiveFullscreen()

            // random animation
            keyboardDrawer.alpha = 0f
            keyboardDrawer.scaleY = 0.8f
            keyboardDrawer.animate()
                .alpha(0.85f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        }
    }

    @JvmStatic
    fun hideKeyboard(activity: PythonSDLActivity) {
        activity.runOnUiThread {
            val view = keyboardView ?: return@runOnUiThread
            val keyboardDrawer = view.findViewById<CardView>(R.id.keyboard_drawer) ?: return@runOnUiThread

            keyboardDrawer.animate()
                .alpha(0f)
                .scaleY(0.8f)
                .setDuration(200)
                .withEndAction {
                    activity.mFrameLayout.removeView(view)
                    keyboardView = null
                    activity.applyImmersiveFullscreen()
                }
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(keyboardRoot: View, keyboardDrawer: View, dragHandleContainer: View) {
        var lastTouchX = 0f
        var lastTouchY = 0f

        dragHandleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY

                    var newX = keyboardDrawer.x + dx
                    var newY = keyboardDrawer.y + dy

                    val maxLimitX = keyboardRoot.width - keyboardDrawer.width
                    val maxLimitY = keyboardRoot.height - keyboardDrawer.height

                    newX = newX.coerceIn(0f, maxLimitX.toFloat())
                    newY = newY.coerceIn(0f, maxLimitY.toFloat())

                    keyboardDrawer.x = newX
                    keyboardDrawer.y = newY

                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                else -> false
            }
        }
    }

    private fun buildKeysLayout(
        inflater: LayoutInflater,
        context: Context,
        activity: PythonSDLActivity,
        container: LinearLayout
    ) {
        for (row in keyRows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = row.sumOf { getKeyWeight(it).toDouble() }.toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            rowLayout.layoutParams = params

            for (key in row) {
                val keyView = inflater.inflate(R.layout.item_keyboard_key, rowLayout, false) as TextView
                keyView.text = key
                
                val weight = getKeyWeight(key)
                val keyParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                keyView.layoutParams = keyParams

                val keyUpper = key.uppercase()
                if (keyUpper == "SHIFT" || keyUpper == "CAPS" || keyUpper == "CTRL" || keyUpper == "ALT") {
                    modifierKeyViews[keyUpper] = keyView
                }

                if (isLetter(key)) {
                    letterKeyViews[keyView] = key
                }

                keyView.setOnClickListener {
                    handleKeyPress(activity, key, context)
                }

                rowLayout.addView(keyView)
            }
            container.addView(rowLayout)
        }
    }

    private fun handleKeyPress(activity: PythonSDLActivity, key: String, context: Context) {
        val keyUpper = key.uppercase()
        when (keyUpper) {
            "SHIFT" -> {
                isShiftActive = !isShiftActive
                updateKeysVisuals(context)
            }
            "CAPS" -> {
                isCapsLockActive = !isCapsLockActive
                updateKeysVisuals(context)
            }
            "CTRL" -> {
                isCtrlActive = !isCtrlActive
                updateKeysVisuals(context)
            }
            "ALT" -> {
                isAltActive = !isAltActive
                updateKeysVisuals(context)
            }
            "CLOSE" -> {
                hideKeyboard(activity)
            }
            else -> {
                val code = getKeyCode(keyUpper)
                if (code != 0) {
                    injectKeyEvent(activity, code)
                }
            }
        }
    }

    private fun injectKeyEvent(activity: PythonSDLActivity, keyCode: Int) {
        val meta = getMetaState()
        val time = System.currentTimeMillis()

        activity.dispatchKeyEvent(KeyEvent(
            time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta
        ))

        activity.dispatchKeyEvent(KeyEvent(
            time, time, KeyEvent.ACTION_UP, keyCode, 0, meta
        ))

        if (isShiftActive) {
            isShiftActive = false
            keyboardView?.let { v ->
                updateKeysVisuals(v.context)
            }
        }
    }

    private fun updateKeysVisuals(context: Context) {
        val shiftOrCaps = isShiftActive || isCapsLockActive

        for ((view, baseChar) in letterKeyViews) {
            view.text = if (shiftOrCaps) baseChar.uppercase() else baseChar.lowercase()
        }

        val colorPrimary = ContextCompat.getColor(context, R.color.colorPrimary)
        val colorCardBackground = ContextCompat.getColor(context, R.color.colorCardBackground)
        val colorTextPrimary = ContextCompat.getColor(context, R.color.colorTextPrimary)
        val colorOnPrimary = ContextCompat.getColor(context, android.R.color.white)

        modifierKeyViews["SHIFT"]?.let {
            if (isShiftActive) {
                it.setBackgroundColor(colorPrimary)
                it.setTextColor(colorOnPrimary)
            } else {
                it.setBackgroundResource(R.drawable.bg_keyboard_key)
                it.setTextColor(colorTextPrimary)
            }
        }

        modifierKeyViews["CAPS"]?.let {
            if (isCapsLockActive) {
                it.setBackgroundColor(colorPrimary)
                it.setTextColor(colorOnPrimary)
            } else {
                it.setBackgroundResource(R.drawable.bg_keyboard_key)
                it.setTextColor(colorTextPrimary)
            }
        }

        modifierKeyViews["CTRL"]?.let {
            if (isCtrlActive) {
                it.setBackgroundColor(colorPrimary)
                it.setTextColor(colorOnPrimary)
            } else {
                it.setBackgroundResource(R.drawable.bg_keyboard_key)
                it.setTextColor(colorTextPrimary)
            }
        }

        modifierKeyViews["ALT"]?.let {
            if (isAltActive) {
                it.setBackgroundColor(colorPrimary)
                it.setTextColor(colorOnPrimary)
            } else {
                it.setBackgroundResource(R.drawable.bg_keyboard_key)
                it.setTextColor(colorTextPrimary)
            }
        }
    }

    private fun getMetaState(): Int {
        var state = 0
        if (isShiftActive || isCapsLockActive) {
            state = state or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (isCtrlActive) {
            state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (isAltActive) {
            state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        return state
    }

    private fun getKeyWeight(label: String): Float {
        return when (label.uppercase()) {
            "SPACE" -> 4.0f
            "BACK", "ENTER", "SHIFT" -> 1.8f
            "TAB", "CAPS", "CTRL", "ALT", "CLOSE" -> 1.5f
            else -> 1.0f
        }
    }

    private fun isLetter(label: String): Boolean {
        if (label.length != 1) return false
        val c = label[0]
        return c in 'a'..'z' || c in 'A'..'Z'
    }

    private fun getKeyCode(label: String): Int {
        return when (label) {
            "ESC" -> KeyEvent.KEYCODE_ESCAPE
            "F1" -> KeyEvent.KEYCODE_F1
            "F2" -> KeyEvent.KEYCODE_F2
            "F3" -> KeyEvent.KEYCODE_F3
            "F4" -> KeyEvent.KEYCODE_F4
            "F5" -> KeyEvent.KEYCODE_F5
            "F6" -> KeyEvent.KEYCODE_F6
            "F7" -> KeyEvent.KEYCODE_F7
            "F8" -> KeyEvent.KEYCODE_F8
            "F9" -> KeyEvent.KEYCODE_F9
            "F10" -> KeyEvent.KEYCODE_F10
            "F11" -> KeyEvent.KEYCODE_F11
            "F12" -> KeyEvent.KEYCODE_F12
            "1" -> KeyEvent.KEYCODE_1
            "2" -> KeyEvent.KEYCODE_2
            "3" -> KeyEvent.KEYCODE_3
            "4" -> KeyEvent.KEYCODE_4
            "5" -> KeyEvent.KEYCODE_5
            "6" -> KeyEvent.KEYCODE_6
            "7" -> KeyEvent.KEYCODE_7
            "8" -> KeyEvent.KEYCODE_8
            "9" -> KeyEvent.KEYCODE_9
            "0" -> KeyEvent.KEYCODE_0
            "-" -> KeyEvent.KEYCODE_MINUS
            "=" -> KeyEvent.KEYCODE_EQUALS
            "BACK" -> KeyEvent.KEYCODE_DEL
            "TAB" -> KeyEvent.KEYCODE_TAB
            "Q" -> KeyEvent.KEYCODE_Q
            "W" -> KeyEvent.KEYCODE_W
            "E" -> KeyEvent.KEYCODE_E
            "R" -> KeyEvent.KEYCODE_R
            "T" -> KeyEvent.KEYCODE_T
            "Y" -> KeyEvent.KEYCODE_Y
            "U" -> KeyEvent.KEYCODE_U
            "I" -> KeyEvent.KEYCODE_I
            "O" -> KeyEvent.KEYCODE_O
            "P" -> KeyEvent.KEYCODE_P
            "[" -> KeyEvent.KEYCODE_LEFT_BRACKET
            "]" -> KeyEvent.KEYCODE_RIGHT_BRACKET
            "\\" -> KeyEvent.KEYCODE_BACKSLASH
            "A" -> KeyEvent.KEYCODE_A
            "S" -> KeyEvent.KEYCODE_S
            "D" -> KeyEvent.KEYCODE_D
            "F" -> KeyEvent.KEYCODE_F
            "G" -> KeyEvent.KEYCODE_G
            "H" -> KeyEvent.KEYCODE_H
            "J" -> KeyEvent.KEYCODE_J
            "K" -> KeyEvent.KEYCODE_K
            "L" -> KeyEvent.KEYCODE_L
            ";" -> KeyEvent.KEYCODE_SEMICOLON
            "'" -> KeyEvent.KEYCODE_APOSTROPHE
            "ENTER" -> KeyEvent.KEYCODE_ENTER
            "Z" -> KeyEvent.KEYCODE_Z
            "X" -> KeyEvent.KEYCODE_X
            "C" -> KeyEvent.KEYCODE_C
            "V" -> KeyEvent.KEYCODE_V
            "B" -> KeyEvent.KEYCODE_B
            "N" -> KeyEvent.KEYCODE_N
            "M" -> KeyEvent.KEYCODE_M
            "," -> KeyEvent.KEYCODE_COMMA
            "." -> KeyEvent.KEYCODE_PERIOD
            "/" -> KeyEvent.KEYCODE_SLASH
            "SPACE" -> KeyEvent.KEYCODE_SPACE
            "CTRL" -> KeyEvent.KEYCODE_CTRL_LEFT
            "ALT" -> KeyEvent.KEYCODE_ALT_LEFT
            "↑" -> KeyEvent.KEYCODE_DPAD_UP
            "↓" -> KeyEvent.KEYCODE_DPAD_DOWN
            "←" -> KeyEvent.KEYCODE_DPAD_LEFT
            "→" -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> 0
        }
    }
}
