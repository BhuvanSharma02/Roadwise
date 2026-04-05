package com.roadwise.utils

import android.content.Context
import android.util.Log
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.roadwise.models.PotholeData
import org.osmdroid.util.GeoPoint
import java.lang.reflect.Type

object PotholeRepository {
    private const val PREFS_NAME = "pothole_prefs"
    private const val KEY_POTHOLES = "potholes"
    private var cached: List<PotholeData>? = null

    private val gson = GsonBuilder()
        .registerTypeAdapter(GeoPoint::class.java, object : JsonSerializer<GeoPoint>, JsonDeserializer<GeoPoint> {
            override fun serialize(src: GeoPoint, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
                val obj = JsonObject()
                obj.addProperty("lat", src.latitude)
                obj.addProperty("lon", src.longitude)
                return obj
            }

            override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): GeoPoint {
                val obj = json.asJsonObject
                // Safe check: If fields are missing (old data), don't crash, return 0.0
                val lat = if (obj.has("lat")) obj.get("lat").asDouble else 0.0
                val lon = if (obj.has("lon")) obj.get("lon").asDouble else 0.0
                return GeoPoint(lat, lon)
            }
        })
        .create()

    fun savePothole(context: Context, pothole: PotholeData) {
        val potholes = getAllPotholes(context).toMutableList()
        potholes.add(pothole)
        saveAll(context, potholes)
        cached = potholes
    }

    fun deletePothole(context: Context, timestamp: Long) {
        val potholes = getAllPotholes(context).toMutableList()
        potholes.removeAll { it.timestamp == timestamp }
        saveAll(context, potholes)
        cached = potholes
    }

    fun saveAllInternal(context: Context, potholes: List<PotholeData>) {
        saveAll(context, potholes)
        cached = potholes
    }

    private fun saveAll(context: Context, potholes: List<PotholeData>) {
        val json = gson.toJson(potholes)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POTHOLES, json)
            .apply()
    }

    fun getAllPotholes(context: Context): List<PotholeData> {
        cached?.let { return it }
        return try {
            val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_POTHOLES, null) ?: return emptyList<PotholeData>().also { cached = it }
            val type = object : TypeToken<List<PotholeData>>() {}.type
            val list: List<PotholeData> = gson.fromJson(json, type)
            val result = list.map { it.copy(imagePaths = it.imagePaths ?: emptyList()) }
            cached = result
            result
        } catch (e: Exception) {
            Log.e("RoadWise-Repo", "Failed to load potholes", e)
            emptyList()
        }
    }

    fun clearAll(context: Context) {
        // Delete all image files from disk first
        val allPotholes = getAllPotholes(context)
        for (pothole in allPotholes) {
            for (path in pothole.imagePaths) {
                try { java.io.File(path).delete() } catch (_: Exception) { }
            }
        }
        // Wipe the SharedPrefs store and invalidate cache
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_POTHOLES).apply()
        cached = emptyList()
    }

    fun getStorageSizeBytes(context: Context): Long {
        val dir = context.getExternalFilesDir(null) ?: return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

