package com.kokkoro.clanbattle.character

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class CharacterSelection(
    val entry: CharacterLibraryEntry,
    val sixStar: Boolean
) {
    val ubName: String? get() = entry.ubNameForSixStar(sixStar)
}

object CharacterPickerDialog {
    fun show(
        activity: Activity,
        library: CharacterLibrary,
        initialQuery: String = "",
        onSelected: (CharacterSelection) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val search = EditText(activity).apply {
            hint = "搜索角色名 / 昵称 / 外号"
            setSingleLine(true)
            setText(initialQuery)
            setSelectAllOnFocus(true)
        }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(activity).apply { addView(results) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择角色")
            .setView(root)
            .setNegativeButton("取消", null)
            .create()

        fun choose(entry: CharacterLibraryEntry) {
            fun finish(sixStar: Boolean) {
                dialog.dismiss()
                onSelected(CharacterSelection(entry, sixStar))
            }
            val normalUb = entry.ub
            val ubPlus = entry.ubPlus
            if (normalUb == null || ubPlus == null) {
                finish(false)
                return
            }
            AlertDialog.Builder(activity)
                .setTitle("${entry.name} · UB版本")
                .setItems(arrayOf(
                    "普通UB：${normalUb.name}",
                    "六星UB+：${ubPlus.name}"
                )) { _, which -> finish(which == 1) }
                .show()
        }

        fun render() {
            results.removeAllViews()
            val found = library.search(search.text.toString(), 30)
            if (found.isEmpty()) {
                results.addView(TextView(activity).apply {
                    text = "没有匹配角色"
                    gravity = Gravity.CENTER
                    setPadding(0, dp(28), 0, dp(28))
                })
                return
            }
            found.forEach { entry ->
                results.addView(Button(activity).apply {
                    isAllCaps = false
                    gravity = Gravity.CENTER_VERTICAL
                    text = buildString {
                        append(entry.name)
                        entry.aliases.firstOrNull { it != entry.name }?.let { append("  ·  ").append(it) }
                        val normalUb = entry.ub
                        if (normalUb != null) {
                            append("\nUB：").append(normalUb.name)
                            entry.ubPlus?.let { append("\n6★：").append(it.name) }
                        } else {
                            append("\nUB：未收录，选择后手动填写")
                        }
                    }
                    textSize = 13f
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    minHeight = dp(72)
                    entry.iconAsset?.let { path ->
                        runCatching {
                            activity.assets.open(path).use(BitmapFactory::decodeStream)
                        }.getOrNull()?.let { bitmap ->
                            val size = dp(52)
                            val drawable = BitmapDrawable(activity.resources, bitmap).apply { setBounds(0, 0, size, size) }
                            setCompoundDrawables(drawable, null, null, null)
                            compoundDrawablePadding = dp(10)
                        }
                    }
                    setOnClickListener { choose(entry) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.setOnShowListener { render() }
        dialog.show()
    }
}
