package com.roadwise

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.roadwise.databinding.ActivityHistoryBinding
import com.roadwise.models.PotholeData
import com.roadwise.utils.PotholeAdapter
import com.roadwise.utils.PotholeRepository
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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()

        binding.btnExportPdf.setOnClickListener {
            val potholes = PotholeRepository.getAllPotholes(this)
            generatePdf(potholes, "RoadWise_Full_Report")
        }
    }

    private fun setupRecyclerView() {
        val potholes = PotholeRepository.getAllPotholes(this).reversed()
        
        adapter = PotholeAdapter(
            potholes,
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
        val lat = String.format("%.4f", Math.abs(data.location.latitude))
        val lon = String.format("%.4f", Math.abs(data.location.longitude))
        val latDir = if (data.location.latitude >= 0) "N" else "S"
        val lonDir = if (data.location.longitude >= 0) "E" else "W"
        return "$lat° $latDir, $lon° $lonDir"
    }

    private fun confirmDeleteRecord(pothole: PotholeData) {
        AlertDialog.Builder(this)
            .setTitle("Delete Record")
            .setMessage("Are you sure you want to delete this detection?")
            .setPositiveButton("Delete") { _, _ ->
                // Delete actual image files first
                pothole.imagePaths?.forEach { path ->
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
                PotholeRepository.deletePothole(this, pothole.timestamp)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        val potholes = PotholeRepository.getAllPotholes(this).reversed()
        adapter.updateData(potholes)
        updateSummaryChips(potholes)
        updateEmptyState(potholes.isEmpty())
    }

    private fun showPotholeDetail(pothole: PotholeData) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pothole_detail, null)
        
        val tvType = view.findViewById<TextView>(R.id.tvDetailType)
        val tvLocation = view.findViewById<TextView>(R.id.tvDetailLocation)
        val tvDateTime = view.findViewById<TextView>(R.id.tvDetailDateTime)
        val tvIntensity = view.findViewById<TextView>(R.id.tvIntensityValue)
        val photosContainer = view.findViewById<LinearLayout>(R.id.photosContainer)
        val btnExport = view.findViewById<View>(R.id.btnExportSingle)

        tvType.text = formatType(pothole)
        tvLocation.text = formatCoords(pothole)
        val sdf = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault())
        tvDateTime.text = sdf.format(Date(pothole.timestamp))
        tvIntensity.text = String.format("%.1fg", Math.abs(pothole.intensity))

        renderPhotos(pothole, photosContainer)

        btnExport.setOnClickListener {
            generatePdf(listOf(pothole), "RoadWise_Pothole_${pothole.timestamp}")
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun renderPhotos(pothole: PotholeData, container: LinearLayout) {
        container.removeAllViews()
        val paths = pothole.imagePaths
        if (paths == null || paths.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No images captured"
            tv.setTextColor(Color.GRAY)
            container.addView(tv)
            return
        }

        paths.forEach { path ->
            val photoView = LayoutInflater.from(this).inflate(R.layout.item_burst_photo, container, false)
            val iv = photoView.findViewById<ImageView>(R.id.ivBurst)
            val btnDel = photoView.findViewById<ImageButton>(R.id.btnDeletePhoto)

            val file = File(path)
            if (file.exists()) {
                // OOM-safe loading: sample down to max 200px
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                opts.inSampleSize = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 200)
                opts.inJustDecodeBounds = false
                val bitmap = BitmapFactory.decodeFile(path, opts)
                iv.setImageBitmap(bitmap)
                
                btnDel.setOnClickListener {
                    file.delete()
                    val newPaths = paths.toMutableList()
                    newPaths.remove(path)
                    // Update repository with new list of paths
                    updatePotholeImages(pothole, newPaths)
                    renderPhotos(pothole.copy(imagePaths = newPaths), container)
                    refreshList() // Refresh main list to update thumbnail
                }
                container.addView(photoView)
            }
        }
    }

    private fun updatePotholeImages(pothole: PotholeData, newPaths: List<String>) {
        val all = PotholeRepository.getAllPotholes(this).toMutableList()
        val index = all.indexOfFirst { it.timestamp == pothole.timestamp }
        if (index != -1) {
            all[index] = all[index].copy(imagePaths = newPaths)
            // Need a way to save full list in repository
            PotholeRepository.saveAllInternal(this, all)
        }
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
                y += 30f
                
                var xOffset = 50f
                pothole.imagePaths?.forEach { path ->
                    val file = File(path)
                    if (file.exists() && y < 700f) {
                        val bitmap = BitmapFactory.decodeFile(path)
                        if (bitmap != null) {
                            val scaled = Bitmap.createScaledBitmap(bitmap, 120, 90, true)
                            canvas.drawBitmap(scaled, xOffset, y, null)
                            xOffset += 130f
                            if (xOffset > 450f) { xOffset = 50f; y += 100f }
                        }
                    }
                }
                if (xOffset != 50f) y += 100f

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
