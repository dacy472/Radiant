/*
 * Structure adapted from Mihon's `ReaderTransitionView` (https://github.com/mihonapp/mihon),
 * Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.radiant.webtoon.ui.reader.model.ChapterTransition

/**
 * "Finished: A / Next: B [Load next chapter]" card between chapters.
 *
 * It is a plain vertical `LinearLayout` with `wrap_content` height, so it takes exactly its text
 * height inside the webtoon list (or fills the page in the pager) and cannot overlap pages.
 */
class ReaderTransitionView(context: Context) : LinearLayout(context) {

    private val title = TextView(context).apply {
        textSize = 16f
        gravity = Gravity.CENTER
    }
    private val subtitle = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        alpha = 0.8f
    }
    private val button = Button(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(title, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(subtitle, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad / 3 })
        addView(button, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = pad / 2 })
    }

    fun bind(
        transition: ChapterTransition,
        contentColor: Int,
        onLoadChapter: () -> Unit,
    ) {
        title.setTextColor(contentColor)
        subtitle.setTextColor(contentColor)
        val isNext = transition is ChapterTransition.Next
        val target = transition.to
        title.text = if (isNext) "Finished: ${transition.from.name}" else "Current: ${transition.from.name}"
        if (target == null) {
            subtitle.text = if (isNext) "There is no next chapter" else "There is no previous chapter"
            button.visibility = GONE
        } else {
            subtitle.text = (if (isNext) "Next: " else "Previous: ") + target.name
            button.text = if (isNext) "Load next chapter" else "Load previous chapter"
            button.visibility = VISIBLE
            button.setOnClickListener { onLoadChapter() }
        }
    }
}
