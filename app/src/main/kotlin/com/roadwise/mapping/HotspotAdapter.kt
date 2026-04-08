package com.roadwise.mapping

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.roadwise.R
import com.roadwise.models.AnalysisHotspot
import com.roadwise.utils.RoadGrade
import android.graphics.Color

class HotspotAdapter(
    private var hotspots: List<AnalysisHotspot>,
    private val onItemClick: (AnalysisHotspot) -> Unit,
    private val onResolveClick: (AnalysisHotspot) -> Unit
) : RecyclerView.Adapter<HotspotAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val gradeBadge: TextView = view.findViewById(R.id.tvGradeBadge)
        val locationName: TextView = view.findViewById(R.id.tvLocationName)
        val coords: TextView = view.findViewById(R.id.tvCoords)
        val hazardCount: TextView = view.findViewById(R.id.tvHazardCount)
        val btnResolve: View = view.findViewById(R.id.btnResolve)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hotspot, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val hotspot = hotspots[position]
        
        holder.gradeBadge.text = hotspot.grade.name
        holder.gradeBadge.background.setTint(getGradeColor(hotspot.grade))
        
        holder.locationName.text = hotspot.address.ifBlank { "Zone ${position + 1}" }
        holder.coords.text = "${"%.4f".format(hotspot.center.latitude)}, ${"%.4f".format(hotspot.center.longitude)}"
        holder.hazardCount.text = "${hotspot.count} hazards"
        
        holder.itemView.setOnClickListener { onItemClick(hotspot) }
        holder.btnResolve.setOnClickListener { onResolveClick(hotspot) }
    }

    override fun getItemCount() = hotspots.size

    fun updateData(newData: List<AnalysisHotspot>) {
        this.hotspots = newData
        notifyDataSetChanged()
    }

    private fun getGradeColor(grade: RoadGrade): Int {
        return when (grade) {
            RoadGrade.A -> Color.parseColor("#10B981") // Emerald
            RoadGrade.B -> Color.parseColor("#3B82F6") // Blue
            RoadGrade.C -> Color.parseColor("#FBBF24") // Amber
            RoadGrade.D -> Color.parseColor("#F97316") // Orange
            RoadGrade.F -> Color.parseColor("#EF4444") // Red
            else -> Color.GRAY
        }
    }
}
