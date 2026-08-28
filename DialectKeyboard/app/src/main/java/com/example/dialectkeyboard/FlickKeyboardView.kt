package com.example.dialectkeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

enum class FlickDirection {
    CENTER, LEFT, UP, RIGHT, DOWN
}

enum class KeyType {
    CHAR, DAKUTEN, DELETE, SPACE_OR_NEXT, ENTER, SWITCH_EN, SWITCH_EMOJI, CURSOR_RIGHT, UNDO
}

data class KeyModel(
    val row: Int,
    val col: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val type: KeyType,
    val centerText: String,
    val leftText: String = "",
    val upText: String = "",
    val rightText: String = "",
    val downText: String = ""
) {
    fun getChar(direction: FlickDirection): String {
        return when (direction) {
            FlickDirection.CENTER -> centerText
            FlickDirection.LEFT -> leftText.ifEmpty { centerText }
            FlickDirection.UP -> upText.ifEmpty { centerText }
            FlickDirection.RIGHT -> rightText.ifEmpty { centerText }
            FlickDirection.DOWN -> downText.ifEmpty { centerText }
        }
    }
}

enum class KeyboardMode {
    KANA_TENKEY,
    QWERTY_EN,
    EMOJI
}

class FlickKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface KeyActionListener {
        fun onCharInput(char: String)
        fun onToggleDakuten()
        fun onDelete()
        fun onDeleteAll()
        fun onSpaceOrNext()
        fun onEnter()
        fun onSwitchLanguage(mode: KeyboardMode)
        fun onCursorRight()
        fun onUndo()
    }

    var actionListener: KeyActionListener? = null
    var isComposing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var currentMode: KeyboardMode = KeyboardMode.KANA_TENKEY
        set(value) {
            field = value
            invalidate()
        }

    // 5列 x 4段 レイアウト
    private val kanaKeys = listOf(
        // 列0
        KeyModel(0, 0, 1, 1, KeyType.CURSOR_RIGHT, "→"),
        KeyModel(1, 0, 1, 1, KeyType.UNDO, "↶"),
        KeyModel(2, 0, 1, 1, KeyType.SWITCH_EN, "ABC"),
        KeyModel(3, 0, 1, 1, KeyType.SWITCH_EMOJI, "😀"),

        // 列1
        KeyModel(0, 1, 1, 1, KeyType.CHAR, "あ", "い", "う", "え", "お"),
        KeyModel(1, 1, 1, 1, KeyType.CHAR, "た", "ち", "つ", "て", "と"),
        KeyModel(2, 1, 1, 1, KeyType.CHAR, "ま", "み", "む", "め", "も"),
        KeyModel(3, 1, 1, 1, KeyType.DAKUTEN, "小/゛"),

        // 列2
        KeyModel(0, 2, 1, 1, KeyType.CHAR, "か", "き", "く", "け", "こ"),
        KeyModel(1, 2, 1, 1, KeyType.CHAR, "な", "に", "ぬ", "ね", "の"),
        KeyModel(2, 2, 1, 1, KeyType.CHAR, "や", "（", "ゆ", "）", "よ"),
        KeyModel(3, 2, 1, 1, KeyType.CHAR, "わ", "を", "ん", "ー", "〜"),

        // 列3
        KeyModel(0, 3, 1, 1, KeyType.CHAR, "さ", "し", "す", "せ", "そ"),
        KeyModel(1, 3, 1, 1, KeyType.CHAR, "は", "ひ", "ふ", "へ", "ほ"),
        KeyModel(2, 3, 1, 1, KeyType.CHAR, "ら", "り", "る", "れ", "ろ"),
        KeyModel(3, 3, 1, 1, KeyType.CHAR, "、", "。", "？", "！", "…"),

        // 列4
        KeyModel(0, 4, 1, 1, KeyType.DELETE, "⌫"),
        KeyModel(1, 4, 1, 1, KeyType.SPACE_OR_NEXT, "空白"),
        KeyModel(2, 4, 2, 1, KeyType.ENTER, "→")
    )

    private val qwertyRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    private val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
        "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
        "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖",
        "😭", "🥺", "😢", "😤", "😠", "😡", "🤯", "😳", "🥵", "🥶",
        "👍", "👎", "👏", "🙌", "🙏", "💪", "✨", "🎉", "❤️", "🔥"
    )

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var activeKey: KeyModel? = null
    private val activeRect = RectF()
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var currentDirection = FlickDirection.CENTER
    private val flickThreshold = 35f

    private val handler = Handler(Looper.getMainLooper())
    private var isDeletingRepeatedly = false
    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            actionListener?.onDelete()
            handler.postDelayed(this, 60)
        }
    }
    private val deleteInitialRunnable = Runnable {
        isDeletingRepeatedly = true
        handler.post(deleteRepeatRunnable)
    }

    init {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#212121")
        textPaint.textSize = 34f

        subTextPaint.textAlign = Paint.Align.CENTER
        subTextPaint.color = Color.parseColor("#90A4AE")
        subTextPaint.textSize = 18f

        highlightPaint.color = Color.parseColor("#90CAF9")
        highlightPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        when (currentMode) {
            KeyboardMode.KANA_TENKEY -> drawKanaTenkey(canvas)
            KeyboardMode.QWERTY_EN -> drawQwerty(canvas)
            KeyboardMode.EMOJI -> drawEmojiGrid(canvas)
        }
    }

    private fun drawKanaTenkey(canvas: Canvas) {
        val totalRows = 4
        val totalCols = 5
        val cellW = width.toFloat() / totalCols
        val cellH = height.toFloat() / totalRows
        val margin = 5f

        for (k in kanaKeys) {
            val left = k.col * cellW + margin
            val top = k.row * cellH + margin
            val right = (k.col + k.colSpan) * cellW - margin
            val bottom = (k.row + k.rowSpan) * cellH - margin
            val rect = RectF(left, top, right, bottom)

            val isPressed = (k == activeKey)
            keyPaint.color = when {
                k.type == KeyType.ENTER -> Color.parseColor("#1976D2")
                isPressed && k.type != KeyType.CHAR -> Color.parseColor("#B0BEC5")
                k.type == KeyType.CHAR -> Color.WHITE
                else -> Color.parseColor("#CFD8DC")
            }

            canvas.drawRoundRect(rect, 12f, 12f, keyPaint)

            if (isPressed && k.type == KeyType.CHAR) {
                drawDirectionHighlight(canvas, rect, currentDirection)
            }

            textPaint.color = if (k.type == KeyType.ENTER) Color.WHITE else Color.parseColor("#212121")
            textPaint.textSize = when {
                k.centerText == "、" -> 22f
                k.type == KeyType.ENTER -> 38f
                k.type == KeyType.DAKUTEN || k.type == KeyType.SPACE_OR_NEXT -> 24f
                else -> 34f
            }
            val cx = rect.centerX()
            val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2

            val displayText = when {
                k.row == 3 && k.col == 3 -> "、。?!"
                k.type == KeyType.SPACE_OR_NEXT -> if (isComposing) "次候補" else "空白"
                else -> k.centerText
            }
            canvas.drawText(displayText, cx, cy, textPaint)

            if (k.type == KeyType.CHAR && k.leftText.isNotEmpty() && !(k.row == 3 && k.col == 3)) {
                canvas.drawText(k.leftText, rect.left + 18f, rect.centerY() + 7f, subTextPaint)
                canvas.drawText(k.upText, cx, rect.top + 22f, subTextPaint)
                canvas.drawText(k.rightText, rect.right - 18f, rect.centerY() + 7f, subTextPaint)
                canvas.drawText(k.downText, cx, rect.bottom - 10f, subTextPaint)
            }
        }
    }

    private fun drawDirectionHighlight(canvas: Canvas, rect: RectF, direction: FlickDirection) {
        if (direction == FlickDirection.CENTER) return

        val cx = rect.centerX()
        val cy = rect.centerY()
        val path = Path()

        when (direction) {
            FlickDirection.LEFT -> {
                path.moveTo(rect.left, rect.top)
                path.lineTo(cx, cy)
                path.lineTo(rect.left, rect.bottom)
            }
            FlickDirection.UP -> {
                path.moveTo(rect.left, rect.top)
                path.lineTo(cx, cy)
                path.lineTo(rect.right, rect.top)
            }
            FlickDirection.RIGHT -> {
                path.moveTo(rect.right, rect.top)
                path.lineTo(cx, cy)
                path.lineTo(rect.right, rect.bottom)
            }
            FlickDirection.DOWN -> {
                path.moveTo(rect.left, rect.bottom)
                path.lineTo(cx, cy)
                path.lineTo(rect.right, rect.bottom)
            }
            FlickDirection.CENTER -> {}
        }
        path.close()

        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, 12f, 12f, Path.Direction.CW) })
        canvas.drawPath(path, highlightPaint)
        canvas.restore()
    }

    private fun drawQwerty(canvas: Canvas) {
        val rowH = height.toFloat() / 4
        val margin = 4f
        var top = margin

        qwertyRows.forEachIndexed { _, row ->
            val keyW = width.toFloat() / row.size
            row.forEachIndexed { cIdx, char ->
                val rect = RectF(cIdx * keyW + margin, top, (cIdx + 1) * keyW - margin, top + rowH - margin)
                keyPaint.color = Color.WHITE
                canvas.drawRoundRect(rect, 8f, 8f, keyPaint)
                val cx = rect.centerX()
                val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                textPaint.color = Color.BLACK
                textPaint.textSize = 30f
                canvas.drawText(char, cx, cy, textPaint)
            }
            top += rowH
        }

        val bW = width.toFloat() / 5
        val bTop = 3 * rowH + margin
        val bBtm = height.toFloat() - margin

        val bottomButtons = listOf(
            "かな" to KeyType.SWITCH_EN,
            "😀" to KeyType.SWITCH_EMOJI,
            "Space" to KeyType.SPACE_OR_NEXT,
            "⌫" to KeyType.DELETE,
            "↵" to KeyType.ENTER
        )
        bottomButtons.forEachIndexed { idx, (lbl, type) ->
            val rect = RectF(idx * bW + margin, bTop, (idx + 1) * bW - margin, bBtm)
            keyPaint.color = if (type == KeyType.ENTER) Color.parseColor("#1976D2") else Color.parseColor("#CFD8DC")
            canvas.drawRoundRect(rect, 8f, 8f, keyPaint)
            textPaint.color = if (type == KeyType.ENTER) Color.WHITE else Color.BLACK
            textPaint.textSize = 24f
            val cx = rect.centerX()
            val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(lbl, cx, cy, textPaint)
        }
    }

    private fun drawEmojiGrid(canvas: Canvas) {
        val cols = 8
        val rows = 4
        val cellW = width.toFloat() / cols
        val cellH = (height.toFloat() - 60f) / rows

        for (i in 0 until (cols * rows).coerceAtMost(emojis.size)) {
            val r = i / cols
            val c = i % cols
            val cx = c * cellW + cellW / 2
            val cy = r * cellH + cellH / 2 + 10f
            textPaint.textSize = 34f
            canvas.drawText(emojis[i], cx, cy, textPaint)
        }

        val bRect = RectF(10f, height.toFloat() - 55f, width.toFloat() - 10f, height.toFloat() - 5f)
        keyPaint.color = Color.parseColor("#CFD8DC")
        canvas.drawRoundRect(bRect, 8f, 8f, keyPaint)
        textPaint.textSize = 22f
        textPaint.color = Color.BLACK
        canvas.drawText("閉じる (かな入力に戻る)", bRect.centerX(), bRect.centerY() + 8f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return super.onTouchEvent(event)

        when (currentMode) {
            KeyboardMode.KANA_TENKEY -> handleTenkeyTouch(event)
            KeyboardMode.QWERTY_EN -> handleQwertyTouch(event)
            KeyboardMode.EMOJI -> handleEmojiTouch(event)
        }
        return true
    }

    private fun handleTenkeyTouch(event: MotionEvent) {
        val totalRows = 4
        val totalCols = 5
        val cellW = width.toFloat() / totalCols
        val cellH = height.toFloat() / totalRows

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val col = (event.x / cellW).toInt().coerceIn(0, totalCols - 1)
                val row = (event.y / cellH).toInt().coerceIn(0, totalRows - 1)

                activeKey = if (row in 2..3 && col == 4) {
                    kanaKeys.find { it.type == KeyType.ENTER }
                } else {
                    kanaKeys.find { it.row == row && it.col == col }
                }

                activeKey?.let {
                    val left = it.col * cellW
                    val top = it.row * cellH
                    val right = (it.col + it.colSpan) * cellW
                    val bottom = (it.row + it.rowSpan) * cellH
                    activeRect.set(left, top, right, bottom)
                }

                touchStartX = event.x
                touchStartY = event.y
                currentDirection = FlickDirection.CENTER
                isDeletingRepeatedly = false

                if (activeKey?.type == KeyType.DELETE) {
                    handler.postDelayed(deleteInitialRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                currentDirection = when {
                    abs(dx) < flickThreshold && abs(dy) < flickThreshold -> FlickDirection.CENTER
                    abs(dx) > abs(dy) -> if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
                    else -> if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
                }
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(deleteInitialRunnable)
                handler.removeCallbacks(deleteRepeatRunnable)

                activeKey?.let { k ->
                    when (k.type) {
                        KeyType.CHAR -> actionListener?.onCharInput(k.getChar(currentDirection))
                        KeyType.DAKUTEN -> actionListener?.onToggleDakuten()
                        KeyType.DELETE -> {
                            if (!isDeletingRepeatedly) {
                                if (currentDirection == FlickDirection.LEFT) {
                                    actionListener?.onDeleteAll()
                                } else {
                                    actionListener?.onDelete()
                                }
                            }
                        }
                        KeyType.SPACE_OR_NEXT -> actionListener?.onSpaceOrNext()
                        KeyType.ENTER -> actionListener?.onEnter()
                        KeyType.SWITCH_EN -> {
                            currentMode = KeyboardMode.QWERTY_EN
                            actionListener?.onSwitchLanguage(currentMode)
                        }
                        KeyType.SWITCH_EMOJI -> {
                            currentMode = KeyboardMode.EMOJI
                            actionListener?.onSwitchLanguage(currentMode)
                        }
                        KeyType.CURSOR_RIGHT -> actionListener?.onCursorRight()
                        KeyType.UNDO -> actionListener?.onUndo()
                    }
                    Unit
                }
                activeKey = null
                currentDirection = FlickDirection.CENTER
                isDeletingRepeatedly = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(deleteInitialRunnable)
                handler.removeCallbacks(deleteRepeatRunnable)
                activeKey = null
                currentDirection = FlickDirection.CENTER
                isDeletingRepeatedly = false
                invalidate()
            }
        }
    }

    private fun handleQwertyTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val rowH = height.toFloat() / 4
            val row = (event.y / rowH).toInt().coerceIn(0, 3)

            if (row < 3) {
                val rowList = qwertyRows[row]
                val keyW = width.toFloat() / rowList.size
                val col = (event.x / keyW).toInt().coerceIn(0, rowList.size - 1)
                actionListener?.onCharInput(rowList[col])
            } else {
                val bW = width.toFloat() / 5
                when ((event.x / bW).toInt().coerceIn(0, 4)) {
                    0 -> {
                        currentMode = KeyboardMode.KANA_TENKEY
                        actionListener?.onSwitchLanguage(currentMode)
                    }
                    1 -> {
                        currentMode = KeyboardMode.EMOJI
                        actionListener?.onSwitchLanguage(currentMode)
                    }
                    2 -> actionListener?.onSpaceOrNext()
                    3 -> actionListener?.onDelete()
                    4 -> actionListener?.onEnter()
                }
            }
        }
    }

    private fun handleEmojiTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (event.y > height - 60f) {
                currentMode = KeyboardMode.KANA_TENKEY
                actionListener?.onSwitchLanguage(currentMode)
            } else {
                val cols = 8
                val rows = 4
                val cellW = width.toFloat() / cols
                val cellH = (height.toFloat() - 60f) / rows
                val c = (event.x / cellW).toInt().coerceIn(0, cols - 1)
                val r = (event.y / cellH).toInt().coerceIn(0, rows - 1)
                val idx = r * cols + c
                if (idx < emojis.size) {
                    actionListener?.onCharInput(emojis[idx])
                }
            }
        }
    }
}