package com.roadwise

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.roadwise.databinding.ActivityHistoryBinding
import com.roadwise.models.PotholeData
import com.roadwise.utils.PotholeAdapter
import com.roadwise.utils.PotholeRepository
import com.roadwise.utils.SessionManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: PotholeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding.toolbar.setOnClickListener { 
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        setupRecyclerView()

        binding.btnExportPdf.setOnClickListener {
            val potholes = PotholeRepository.getAllPotholes(this)
            generatePdf(potholes, "RoadWise_Full_Report")
        }

        observeDataChanges()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        PotholeRepository.fetchFromCloud(this) {}
    }

    private fun observeDataChanges() {
        lifecycleScope.launch {
            PotholeRepository.updates.collect {
                refreshList()
            }
        }
    }

    private fun setupRecyclerView() {
        val userEmail = SessionManager.getUserEmail(this)
        val isAdmin = SessionManager.isAdmin(this)
        
        // Filter records: standard users only see their own, admins see everything
        val allPotholes = PotholeRepository.getAllPotholes(this)
        val potholes = if (isAdmin) allPotholes else allPotholes.filter { it.createdByEmail == userEmail }
        
        adapter = PotholeAdapter(
            potholes,
            userEmail,
            isAdmin,
            onItemClick = { pothole -> showPotholeDetail(pothole) },
            onDeleteClick = { pothole -> confirmDeleteRecord(pothole) }
        )

        binding.rvPotholes.layoutManager = LinearLayoutManager(this)
        binding.rvPotholes.adapter = adapter
        
        updateSummaryChips(potholes)
        updateEmptyState(potholes.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.btnExportPdf.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateSummaryChips(potholes: List<PotholeData>) {
        val potholeCount = potholes.count { it.type == com.roadwise.sensors.RoadFeature.POTHOLE }
        val bumpCount = potholes.count { it.type == com.roadwise.sensors.RoadFeature.SPEED_BUMP }
        binding.tvSummary.text = "$potholeCount Potholes · $bumpCount Speed Bumps"
        binding.tvSummary.visibility = if (potholes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatType(data: PotholeData): String = when (data.type) {
        com.roadwise.sensors.RoadFeature.POTHOLE -> "Pothole"
        com.roadwise.sensors.RoadFeature.SPEED_BUMP -> "Speed Bump"
        else -> "Unknown"
    }

    private fun formatCoords(data: PotholeData): String {
        val lat = String.format(Locale.US, "%.4f", kotlin.math.abs(data.location.latitude))
        val lon = String.format(Locale.US, "%.4f", kotlin.math.abs(data.location.longitude))
        val latDir = if (data.location.latitude >= 0) "N" else "S"
        val lonDir = if (data.location.longitude >= 0) "E" else "W"
        return "$lat° $latDir, $lon° $lonDir"
    }

    private fun confirmDeleteRecord(pothole: PotholeData) {
        AlertDialog.Builder(this)
            .setTitle("Delete Record")
            .setMessage("Are you sure you want to delete this detection?")
            .setPositiveButton("Delete") { _, _ ->
                PotholeRepository.deletePothole(this, pothole.timestamp)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        val userEmail = SessionManager.getUserEmail(this)
        val isAdmin = SessionManager.isAdmin(this)
        
        val allPotholes = PotholeRepository.getAllPotholes(this)
        val potholes = if (isAdmin) allPotholes else allPotholes.filter { it.createdByEmail == userEmail }

        adapter.updateData(potholes)
        updateSummaryChips(potholes)
        updateEmptyState(potholes.isEmpty())
    }

    private fun showPotholeDetail(pothole: PotholeData) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_pothole_detail, null)

        val tvType = view.findViewById<android.widget.TextView>(R.id.tvDetailType)
        val tvLocation = view.findViewById<android.widget.TextView>(R.id.tvDetailLocation)
        val tvDateTime = view.findViewById<android.widget.TextView>(R.id.tvDetailDateTime)
        val tvIntensity = view.findViewById<android.widget.TextView>(R.id.tvIntensityValue)
        val btnExport = view.findViewById<View>(R.id.btnExportSingle)
        val btnViewOnMap = view.findViewById<View>(R.id.btnViewOnMap)

        tvType.text = formatType(pothole)
        tvLocation.text = formatCoords(pothole)
        val sdf = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault())
        tvDateTime.text = sdf.format(Date(pothole.timestamp))
        tvIntensity.text = String.format("%.1fg", Math.abs(pothole.intensity))

        btnExport.setOnClickListener {
            generatePdf(listOf(pothole), "RoadWise_Pothole_${pothole.timestamp}")
            dialog.dismiss()
        }

        btnViewOnMap.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("FOCUS_LAT", pothole.location.latitude)
                putExtra("FOCUS_LON", pothole.location.longitude)
            }
            startActivity(intent)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun generatePdf(potholes: List<PotholeData>, baseFileName: String) {
        try {
            val pdfDocument = PdfDocument()
            val titlePaint = Paint().apply { textSize = 24f; isFakeBoldText = true; color = Color.BLACK }
            val textPaint = Paint().apply { textSize = 14f; color = Color.BLACK }

            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas
            var y = 60f

            canvas.drawText("RoadWise Pothole Report", 50f, y, titlePaint)
            y += 40f

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (pothole in potholes) {
                if (y > 700f) {
                    pdfDocument.finishPage(page)
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 60f
                }

                canvas.drawText("Type: ${pothole.type.name} | Intensity: ${String.format("%.2f", pothole.intensity)}", 50f, y, textPaint)
                y += 20f
                canvas.drawText("Loc: ${pothole.location.latitude}, ${pothole.location.longitude}", 50f, y, textPaint)
                y += 20f
                canvas.drawText("Date: ${sdf.format(Date(pothole.timestamp))}", 50f, y, textPaint)
                y += 20f
                canvas.drawText("Severity: ${pothole.severity.name}", 50f, y, textPaint)
                y += 30f

                canvas.drawLine(50f, y, 545f, y, textPaint)
                y += 30f
            }

            pdfDocument.finishPage(page)
            val dir = File(cacheDir, "reports")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "$baseFileName.pdf")
            pdfDocument.writeTo(FileOutputStream(file))
            pdfDocument.close()
            
            sharePdf(file)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to create PDF. Ensure you have free storage space.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share or Save PDF"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
