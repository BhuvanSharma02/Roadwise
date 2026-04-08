package com.roadwise.utils

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.roadwise.R
import com.roadwise.models.PotholeData
import com.roadwise.sensors.RoadFeature
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class PotholeAdapter(
    private var potholes: List<PotholeData>,
    private val onItemClick: (PotholeData) -> Unit,
    private val onDeleteClick: (PotholeData) -> Unit
) : RecyclerView.Adapter<PotholeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val ivTypeIcon: ImageView = view.findViewById(R.id.ivTypeIcon)
        val typeRing: View = view.findViewById(R.id.typeRing)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvLocation: TextView = view.findViewById(R.id.tvLocation)
        val tvDateTime: TextView = view.findViewById(R.id.tvDateTime)
        val pbIntensity: ProgressBar = view.findViewById(R.id.pbIntensity)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pothole, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pothole = potholes[position]
        val ctx = holder.itemView.context

        // Type-specific theming: amber for potholes, teal for speed bumps
        val isSpeedBump = pothole.type == RoadFeature.SPEED_BUMP
        val typeColor = if (isSpeedBump) R.color.brand_teal else {
            when(pothole.severity) {
                Severity.LOW -> R.color.brand_blue
                Severity.MEDIUM -> R.color.brand_amber
                Severity.HIGH -> android.R.color.holo_red_dark
            }
        }
        val typeColorInt = ContextCompat.getColor(ctx, typeColor)

        holder.tvType.text = "${pothole.severity.name} ${if (isSpeedBump) "Speed Bump" else "Pothole"}"
        holder.tvType.setTextColor(typeColorInt)
        holder.typeRing.backgroundTintList = ContextCompat.getColorStateList(ctx, typeColor)
        holder.pbIntensity.progressTintList = ContextCompat.getColorStateList(ctx, typeColor)

        // Format coordinates
        val lat = String.format("%.4f", abs(pothole.location.latitude))
        val lon = String.format("%.4f", abs(pothole.location.longitude))
        val latDir = if (pothole.location.latitude >= 0) "N" else "S"
        val lonDir = if (pothole.location.longitude >= 0) "E" else "W"
        holder.tvLocation.text = "$lat° $latDir, $lon° $lonDir"

        val sdf = SimpleDateFormat("MMM dd · HH:mm", Locale.getDefault())
        val intensityG = "%.1f G".format(pothole.intensity)
        holder.tvDateTime.text = "${sdf.format(Date(pothole.timestamp))}  ·  $intensityG"

        // Intensity bar — map g-force to a 0-100 scale (max ~10g)
        val intensityPercent = (abs(pothole.intensity) / 10f * 100).toInt().coerceIn(5, 100)
        holder.pbIntensity.progress = intensityPercent

        // Load first image as thumbnail — OOM-safe
        val paths = pothole.imagePaths
        if (paths.isNotEmpty()) {
            val file = File(paths[0])
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.inSampleSize = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 200)
                opts.inJustDecodeBounds = false
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
                holder.ivThumbnail.setImageBitmap(bitmap)
                holder.ivTypeIcon.visibility = View.GONE
            } else {
                showTypeFallback(holder, typeColor)
            }
        } else {
            showTypeFallback(holder, typeColor)
        }

        holder.itemView.setOnClickListener { onItemClick(pothole) }
        holder.btnDelete.setOnClickListener { onDeleteClick(pothole) }
    }

    private fun showTypeFallback(holder: ViewHolder, typeColorRes: Int) {
        holder.ivThumbnail.setImageDrawable(null)
        holder.ivTypeIcon.visibility = View.VISIBLE
        holder.ivTypeIcon.imageTintList = androidx.core.content.ContextCompat.getColorStateList(
            holder.itemView.context, typeColorRes
        )
    }

    override fun getItemCount() = potholes.size

    fun updateData(newList: List<PotholeData>) {
        this.potholes = newList
        notifyDataSetChanged()
    }
}
