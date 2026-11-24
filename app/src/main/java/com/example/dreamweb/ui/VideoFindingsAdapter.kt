package com.example.dreamweb.ui

import android.graphics.Typeface
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.dreamweb.R
import com.example.dreamweb.databinding.ItemVideoFindingBinding
import com.example.dreamweb.engine.Rendition
import com.example.dreamweb.engine.StreamFormat
import com.example.dreamweb.engine.VideoFinding
import com.example.dreamweb.engine.VideoFindingsRegistry

/**
 * Adapter del panel de hallazgos (contracts/findings-panel-ui.md §3/§4).
 *
 * - Fila: badge de formato, título (o host del stream si vacío),
 *   `resolución · duración` cuando existan — los campos ausentes no
 *   bloquean la fila; línea de subtítulos solo si hay pistas.
 * - Sub-fila `▸ Calidad:` visible solo si el hallazgo tiene >1 opción de
 *   resolución — una opción por etiqueta, nunca por URL cruda.
 * - CENTER en fila → lanza la opción por defecto
 *   ([VideoFindingsRegistry.defaultOption]: memoria de sesión o preferencia
 *   de formato); CENTER en opción → lanza esa rendición.
 * - Actualizaciones de metadatos → `notifyItemChanged` sin mover el foco.
 */
class VideoFindingsAdapter(
    private val registry: VideoFindingsRegistry,
    private val onLaunch: (finding: VideoFinding, rendition: Rendition) -> Unit,
    private val onQualityLaunch: (finding: VideoFinding, rendition: Rendition) -> Unit =
        { f, r -> onLaunch(f, r) }
) : RecyclerView.Adapter<VideoFindingsAdapter.FindingVH>() {

    private var items: List<VideoFinding> = emptyList()

    /** Diff por id estable: refresca sin mover el foco del D-pad. */
    fun submitFindings(newItems: List<VideoFinding>) {
        val old = items
        items = newItems
        val common = minOf(old.size, newItems.size)
        for (i in 0 until common) {
            if (old[i] != newItems[i]) notifyItemChanged(i)
        }
        if (newItems.size > old.size) {
            notifyItemRangeInserted(old.size, newItems.size - old.size)
        } else if (newItems.size < old.size) {
            notifyItemRangeRemoved(newItems.size, old.size - newItems.size)
        }
    }

    fun indexOf(findingId: String): Int = items.indexOfFirst { it.id == findingId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FindingVH =
        FindingVH(ItemVideoFindingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: FindingVH, position: Int) {
        holder.bind(items[position])
    }

    inner class FindingVH(private val b: ItemVideoFindingBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(f: VideoFinding) {
            val ctx = b.root.context
            val primary = f.primaryRendition

            b.formatBadge.text = formatLabel(primary)
            b.titleText.text = f.title.ifEmpty { hostOf(primary.url) }
            b.metaText.text = metaText(f)

            if (f.subtitles.isNotEmpty()) {
                b.subtitlesText.visibility = View.VISIBLE
                b.subtitlesText.text = ctx.getString(
                    R.string.subtitles_line,
                    f.subtitles.joinToString(" · ") {
                        (it.language ?: it.label ?: "").uppercase()
                    }
                )
            } else {
                b.subtitlesText.visibility = View.GONE
            }

            // Sub-fila de calidades: solo si hay >1 grupo de resolución
            val options = registry.qualityOptions(f)
            b.qualityOptions.removeAllViews()
            if (options.size > 1) {
                b.qualityRow.visibility = View.VISIBLE
                val defaultLabel = registry.defaultOption(f).resolutionLabel
                options.forEach { opt ->
                    val tv = TextView(ctx).apply {
                        text = opt.resolutionLabel
                        textSize = 14f
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(20, 6, 20, 6)
                        isFocusable = true
                        isClickable = true
                        if (opt.resolutionLabel == defaultLabel) {
                            setTypeface(null, Typeface.BOLD)
                            setBackgroundColor(0xFF335533.toInt())
                        } else {
                            setBackgroundColor(0xFF222222.toInt())
                        }
                        setOnClickListener { onQualityLaunch(f, opt) }
                    }
                    b.qualityOptions.addView(tv)
                }
            } else {
                b.qualityRow.visibility = View.GONE
            }

            b.root.setOnClickListener { onLaunch(f, registry.defaultOption(f)) }
            // D-pad: RIGHT en la fila entra a la sub-fila de calidades.
            b.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                    b.qualityRow.visibility == View.VISIBLE &&
                    b.qualityOptions.childCount > 0
                ) {
                    b.qualityOptions.getChildAt(0).requestFocus()
                    true
                } else false
            }
        }

        private fun formatLabel(r: Rendition): String = when (r.format) {
            StreamFormat.HLS -> "HLS"
            StreamFormat.DASH -> "DASH"
            StreamFormat.PROGRESSIVE -> if (r.url.lowercase().contains(".mp4")) "MP4" else "VIDEO"
            StreamFormat.UNKNOWN -> "—"
        }

        private fun hostOf(url: String): String =
            runCatching { Uri.parse(url).host }.getOrNull() ?: url

        private fun metaText(f: VideoFinding): String {
            val parts = mutableListOf<String>()
            f.primaryRendition.heightPx?.let { parts.add("${it}p") }
            f.durationSec?.let { parts.add(formatDuration(it)) }
            return parts.joinToString(" · ")
        }

        private fun formatDuration(sec: Double): String {
            val total = sec.toLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%d:%02d".format(m, s)
        }
    }
}
