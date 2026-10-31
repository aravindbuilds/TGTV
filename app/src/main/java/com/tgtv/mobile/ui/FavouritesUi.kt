package com.tgtv.mobile.ui

import android.app.AlertDialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tgtv.mobile.R
import com.tgtv.mobile.model.VideoItem
import com.tgtv.mobile.model.Watchlist

/**
 * Shared favourites-list picker used by every heart control (grid cards,
 * details rows and the details screen): pill-shaped toggles, one per named
 * list, plus an optional "new list" field, applied to the video on Save.
 */
object FavouritesUi {

    /** Opens the per-list membership chooser for [video]. */
    fun showChooser(activity: AppCompatActivity, video: VideoItem, onChanged: () -> Unit) {
        val names = Watchlist.names(activity)
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)

        val member = Watchlist.membership(activity, video)
        val selected = LinkedHashSet(member)

        if (names.isEmpty()) {
            // Nothing exists yet: quick semantics straight to the default list.
            val added = Watchlist.toggle(activity, video)
            Toast.makeText(
                activity,
                if (added) "Added to My List" else "Removed from My List",
                Toast.LENGTH_SHORT,
            ).show()
            onChanged()
            return
        }

        for (name in names) {
            val tv = pill(activity, name, name in selected)
            tv.setOnClickListener {
                if (name in selected) selected.remove(name) else selected.add(name)
                val active = name in selected
                tv.setBackgroundResource(if (active) R.drawable.bg_chip_red else R.drawable.bg_chip_dark)
                tv.setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (active) android.R.color.white else R.color.text_secondary,
                    ),
                )
            }
            box.addView(tv)
        }

        val input = EditText(activity)
        input.hint = "New list name (optional)"
        box.addView(input)

        AlertDialog.Builder(activity)
            .setTitle("Add to favourites")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val wanted = selected.toMutableSet()
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && !Watchlist.create(activity, newName)) {
                    Toast.makeText(activity, "\"$newName\" already exists - added there", Toast.LENGTH_SHORT).show()
                }
                if (newName.isNotEmpty()) wanted.add(newName)
                if (wanted.isEmpty()) wanted.add(Watchlist.DEFAULT)
                Watchlist.setMembership(activity, video, wanted)
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Builds one pill-shaped toggle chip used inside the chooser. */
    private fun pill(activity: AppCompatActivity, text: String, active: Boolean): TextView {
        val tv = TextView(activity)
        tv.text = text
        val h = (14 * activity.resources.displayMetrics.density).toInt()
        val v = (9 * activity.resources.displayMetrics.density).toInt()
        tv.setPadding(h, v, h, v)
        tv.setTextSize(14f)
        tv.setBackgroundResource(if (active) R.drawable.bg_chip_red else R.drawable.bg_chip_dark)
        tv.setTextColor(
            ContextCompat.getColor(
                activity,
                if (active) android.R.color.white else R.color.text_secondary,
            ),
        )
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = h; bottomMargin = v }
        return tv
    }
}
