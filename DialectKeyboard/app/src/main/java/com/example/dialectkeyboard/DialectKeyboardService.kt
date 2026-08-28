package com.example.dialectkeyboard

import android.inputmethodservice.InputMethodService
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.annotation.SuppressLint

class DialectKeyboardService : InputMethodService(), FlickKeyboardView.KeyActionListener {

    private lateinit var dictionary: CojadsDictionary
    private var candidateAdapter: CandidateAdapter? = null
    private var recyclerCandidates: RecyclerView? = null
    private val currentCandidateList = mutableListOf<DialectEntry>()
    private var selectedCandidateIndex = -1

    private val composingText = StringBuilder()
    private var flickKeyboardView: FlickKeyboardView? = null

    private var layoutKeyboardMain: View? = null
    private var layoutRegionPanel: View? = null
    private var containerRegionChecks: LinearLayout? = null
    private val tempSelectedPrefs = mutableSetOf<String>()

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        dictionary = CojadsDictionary(this)
        dictionary.loadDictionary()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_DialectKeyboard)
        val themedInflater = LayoutInflater.from(themedContext)
        val view = themedInflater.inflate(R.layout.keyboard_view, null)

        layoutKeyboardMain = view.findViewById(R.id.layout_keyboard_main)
        layoutRegionPanel = view.findViewById(R.id.layout_region_panel)
        containerRegionChecks = view.findViewById(R.id.container_region_checks)

        recyclerCandidates = view.findViewById(R.id.recycler_candidates)
        recyclerCandidates?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        candidateAdapter = CandidateAdapter { entry ->
            currentInputConnection?.commitText(entry.word, 1)
            composingText.clear()
            selectedCandidateIndex = -1
            currentCandidateList.clear()
            candidateAdapter?.updateCandidates(emptyList())
            flickKeyboardView?.isComposing = false
        }
        recyclerCandidates?.adapter = candidateAdapter

        view.findViewById<Button>(R.id.btn_select_region)?.setOnClickListener {
            openRegionPanel()
        }

        view.findViewById<Button>(R.id.btn_panel_apply)?.setOnClickListener {

            DialectRegionManager.setSelectedPrefectures(
                this,
                tempSelectedPrefs.toSet()
            )

            // 以前の検索を停止
            searchJob?.cancel()

            // 古い候補を消す
            currentCandidateList.clear()
            candidateAdapter?.updateCandidates(emptyList())

            closeRegionPanel()

            // 現在入力中の文字列で即再検索
            if (composingText.isNotEmpty()) {
                updateComposingState()
            }
        }

        flickKeyboardView = view.findViewById(R.id.flick_keyboard_view)
        flickKeyboardView?.actionListener = this

        return view
    }

    private fun openRegionPanel() {
        tempSelectedPrefs.clear()
        tempSelectedPrefs.addAll(DialectRegionManager.getSelectedPrefectures(this))

        containerRegionChecks?.removeAllViews()

        val prefCbMap = mutableMapOf<String, CheckBox>()
        val regionCbMap = mutableMapOf<String, CheckBox>()

        val headerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 12)
        }

        val btnSelectAll = Button(this).apply {
            text = "すべて選択"
            textSize = 12f
            setOnClickListener {
                val allPrefs = DialectRegionManager.REGION_MAP.values.flatten()
                tempSelectedPrefs.addAll(allPrefs)
                prefCbMap.values.forEach { it.isChecked = true }
                regionCbMap.values.forEach { it.isChecked = true }
            }
        }
        val btnDeselectAll = Button(this).apply {
            text = "すべて解除"
            textSize = 12f
            setOnClickListener {
                tempSelectedPrefs.clear()
                prefCbMap.values.forEach { it.isChecked = false }
                regionCbMap.values.forEach { it.isChecked = false }
            }
        }
        headerActions.addView(btnSelectAll)
        headerActions.addView(btnDeselectAll)
        containerRegionChecks?.addView(headerActions)

        DialectRegionManager.REGION_MAP.forEach { (regionName, prefList) ->
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 4, 8, 4)
            }

            var isExpanded = false

            val prefContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 2, 0, 4)
                visibility = View.GONE
            }

            val regionHeaderLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val toggleIndicator = TextView(this).apply {
                text = "▶ "
                textSize = 14f
                setPadding(8, 0, 8, 0)
            }

            val regionCb = CheckBox(this).apply {
                text = "$regionName"
                textSize = 14f
                paint.isFakeBoldText = true
                setTextColor(0xFF1976D2.toInt())
                isChecked = prefList.all { tempSelectedPrefs.contains(it) }
            }
            regionCbMap[regionName] = regionCb

            regionHeaderLayout.addView(toggleIndicator)
            regionHeaderLayout.addView(regionCb)

            regionHeaderLayout.setOnClickListener {
                isExpanded = !isExpanded
                toggleIndicator.text = if (isExpanded) "▼ " else "▶ "
                prefContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            }

            prefList.forEach { pref ->
                val prefCb = CheckBox(this).apply {
                    text = pref
                    textSize = 12f
                    isChecked = tempSelectedPrefs.contains(pref)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) tempSelectedPrefs.add(pref) else tempSelectedPrefs.remove(pref)
                        regionCb.isChecked = prefList.all { tempSelectedPrefs.contains(it) }
                    }
                }
                prefCbMap[pref] = prefCb
                prefContainer.addView(prefCb)
            }

            regionCb.setOnClickListener {
                val check = regionCb.isChecked
                prefList.forEach { pref ->
                    prefCbMap[pref]?.isChecked = check
                    if (check) tempSelectedPrefs.add(pref) else tempSelectedPrefs.remove(pref)
                }
            }

            section.addView(regionHeaderLayout)
            section.addView(prefContainer)
            containerRegionChecks?.addView(section)
        }

        layoutKeyboardMain?.visibility = View.GONE
        layoutRegionPanel?.visibility = View.VISIBLE
    }

    private fun closeRegionPanel() {
        layoutRegionPanel?.visibility = View.GONE
        layoutKeyboardMain?.visibility = View.VISIBLE
    }

    override fun onCharInput(char: String) {
        composingText.append(char)
        updateComposingState()
    }

    override fun onToggleDakuten() {
        if (composingText.isEmpty()) return
        val lastIdx = composingText.length - 1
        val lastChar = composingText[lastIdx]

        val dakutenMap = mapOf(
            'か' to 'が', 'が' to 'か',
            'き' to 'ぎ', 'ぎ' to 'き',
            'く' to 'ぐ', 'ぐ' to 'く',
            'け' to 'げ', 'げ' to 'け',
            'こ' to 'ご', 'ご' to 'こ',
            'さ' to 'ざ', 'ざ' to 'さ',
            'し' to 'じ', 'じ' to 'し',
            'す' to 'ず', 'ず' to 'す',
            'せ' to 'ぜ', 'ぜ' to 'せ',
            'そ' to 'ぞ', 'ぞ' to 'そ',
            'た' to 'だ', 'だ' to 'た',
            'ち' to 'ぢ', 'ぢ' to 'ち',
            'つ' to 'っ', 'っ' to 'づ', 'づ' to 'つ',
            'て' to 'で', 'で' to 'て',
            'と' to 'ど', 'ど' to 'と',
            'は' to 'ば', 'ば' to 'ぱ', 'ぱ' to 'は',
            'ひ' to 'び', 'び' to 'ぴ', 'ぴ' to 'ひ',
            'ふ' to 'ぶ', 'ぶ' to 'ぷ', 'ぷ' to 'ふ',
            'へ' to 'べ', 'べ' to 'ぺ', 'ぺ' to 'へ',
            'ほ' to 'ぼ', 'ほ' to 'ぽ', 'ぽ' to 'ほ',
            'あ' to 'ぁ', 'ぁ' to 'あ',
            'い' to 'ぃ', 'ぃ' to 'い',
            'う' to 'ぅ', 'ぅ' to 'う',
            'え' to 'ぇ', 'ぇ' to 'え',
            'お' to 'ぉ', 'ぉ' to 'お',
            'や' to 'ゃ', 'ゃ' to 'や',
            'ゆ' to 'ゅ', 'ゆ' to 'ゆ',
            'よ' to 'ょ', 'ょ' to 'よ',
            'わ' to 'ゎ', 'ゎ' to 'わ'
        )

        val replaced = dakutenMap[lastChar]
        if (replaced != null) {
            composingText[lastIdx] = replaced
            updateComposingState()
        }
    }

    override fun onDelete() {
        if (composingText.isNotEmpty()) {
            composingText.deleteCharAt(composingText.length - 1)
            updateComposingState()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    override fun onDeleteAll() {
        if (composingText.isNotEmpty()) {
            composingText.clear()
            updateComposingState()
        } else {
            currentInputConnection?.deleteSurroundingText(100, 0)
        }
    }

    override fun onSpaceOrNext() {
        if (composingText.isEmpty()) {
            currentInputConnection?.commitText(" ", 1)
        } else {
            if (currentCandidateList.isNotEmpty()) {
                selectedCandidateIndex = (selectedCandidateIndex + 1) % currentCandidateList.size
                val entry = currentCandidateList[selectedCandidateIndex]
                currentInputConnection?.setComposingText(entry.word, 1)
                recyclerCandidates?.smoothScrollToPosition(selectedCandidateIndex)
            }
        }
    }

    override fun onEnter() {
        if (composingText.isNotEmpty()) {
            if (selectedCandidateIndex in currentCandidateList.indices) {
                currentInputConnection?.commitText(currentCandidateList[selectedCandidateIndex].word, 1)
            } else {
                currentInputConnection?.commitText(composingText.toString(), 1)
            }
            composingText.clear()
            selectedCandidateIndex = -1
            currentCandidateList.clear()
            candidateAdapter?.updateCandidates(emptyList())
            flickKeyboardView?.isComposing = false
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onSwitchLanguage(mode: KeyboardMode) {
        if (mode == KeyboardMode.EMOJI || mode == KeyboardMode.QWERTY_EN) {
            composingText.clear()
            candidateAdapter?.updateCandidates(emptyList())
            flickKeyboardView?.isComposing = false
        }
    }

    override fun onCursorRight() {
        if (composingText.isNotEmpty()) {
            commitComposing()
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
    }

    override fun onUndo() {
        onDelete()
    }

    private fun updateComposingState() {
        val text = composingText.toString()
        selectedCandidateIndex = -1

        if (text.isEmpty()) {
            currentInputConnection?.setComposingText("", 1)
            currentCandidateList.clear()
            candidateAdapter?.updateCandidates(emptyList())
            flickKeyboardView?.isComposing = false
            return
        }

        flickKeyboardView?.isComposing = true
        currentInputConnection?.setComposingText(text, 1)

        val localResults = dictionary.searchPrefixLocal(text)
        val initialList = mutableListOf<DialectEntry>()
        initialList.addAll(localResults)

        if (initialList.none { it.word == text }) {
            initialList.add(DialectEntry(text, text, "確定", isDialect = false))
        }

        currentCandidateList.clear()
        currentCandidateList.addAll(initialList)
        candidateAdapter?.updateCandidates(initialList)

        searchJob?.cancel()
        searchJob = serviceScope.launch {
            val googleResults = dictionary.fetchGoogleCandidates(text)
            if (isActive) {
                val merged = mutableListOf<DialectEntry>()
                merged.addAll(localResults)

                googleResults.forEach { g ->
                    if (merged.none { it.word == g.word }) {
                        merged.add(g)
                    }
                }

                if (merged.none { it.word == text }) {
                    merged.add(DialectEntry(text, text, "確定", isDialect = false))
                }

                currentCandidateList.clear()
                currentCandidateList.addAll(merged)
                candidateAdapter?.updateCandidates(merged)
            }
        }
    }

    private fun commitComposing() {
        currentInputConnection?.commitText(composingText.toString(), 1)
        composingText.clear()
        selectedCandidateIndex = -1
        currentCandidateList.clear()
        candidateAdapter?.updateCandidates(emptyList())
        flickKeyboardView?.isComposing = false
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        searchJob?.cancel()
        closeRegionPanel()
        composingText.clear()
        selectedCandidateIndex = -1
        currentCandidateList.clear()
        candidateAdapter?.updateCandidates(emptyList())
        flickKeyboardView?.isComposing = false
    }
}