package com.roadwise.utils

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.roadwise.models.PotholeData
import com.roadwise.sensors.RoadFeature
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.lang.reflect.Type
import java.util.Collections
import kotlinx.coroutines.*

object PotholeRepository {
    private const val PREFS_NAME = "pothole_prefs"
    private const val KEY_POTHOLES = "potholes"
    private const val KEY_DELETED = "deleted_timestamps"
    private var cached: List<PotholeData>? = null
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val deletedTimestamps = Collections.synchronizedSet(mutableSetOf<Long>())

    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updates = _updates.asSharedFlow()

    private fun notifyUpdated() {
        _updates.tryEmit(Unit)
    }

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
                val lat = if (obj.has("lat")) obj.get("lat").asDouble else 0.0
                val lon = if (obj.has("lon")) obj.get("lon").asDouble else 0.0
                return GeoPoint(lat, lon)
            }
        })
        .create()

    fun savePothole(context: Context, pothole: PotholeData) {
        try {
            val userEmail = SessionManager.getUserEmail(context)
            val updatedPothole = if (pothole.createdByEmail.isBlank()) {
                pothole.copy(createdByEmail = userEmail)
            } else pothole

            val potholes = getAllPotholes(context).toMutableList()
            potholes.add(0, updatedPothole)
            saveAllInternal(context, potholes)
            pushToCloud(context, updatedPothole)
        } catch (e: Exception) { Log.e("RoadWise-Repo", "Save failed", e) }
    }

    private fun pushToCloud(context: Context, pothole: PotholeData) {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            CoroutineScope(Dispatchers.Main).launch {
                android.widget.Toast.makeText(context, "⚠️ Saved locally. Sign in to sync to cloud.", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "☁️ Syncing to Cloud...", android.widget.Toast.LENGTH_SHORT).show()
                }

                val data = hashMapOf(
                    "lat" to pothole.location.latitude,
                    "lon" to pothole.location.longitude,
                    "type" to pothole.type.name,
                    "intensity" to pothole.intensity,
                    "severity" to pothole.severity.name,
                    "timestamp" to pothole.timestamp,
                    "userId" to currentUser.uid,
                    "createdByEmail" to (currentUser.email ?: "")
                )

                withTimeout(15000L) {
                    val task = firestore.collection("potholes").add(data)
                    while (!task.isComplete) { delay(500) }

                    if (task.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "✅ CLOUD SAVED!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        throw task.exception ?: Exception("Firebase Task Failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = if (e is TimeoutCancellationException) "Timeout: Slow Network" else e.message
                    android.widget.Toast.makeText(context, "❌ CLOUD ERROR: $msg", android.widget.Toast.LENGTH_LONG).show()
                }
                Log.e("RoadWise-Cloud", "Sync error", e)
            }
        }
    }

    private fun pushToCloudSilent(pothole: PotholeData) {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        val data = hashMapOf(
            "lat" to pothole.location.latitude,
            "lon" to pothole.location.longitude,
            "type" to pothole.type.name,
            "intensity" to pothole.intensity,
            "severity" to pothole.severity.name,
            "timestamp" to pothole.timestamp,
            "userId" to currentUser.uid,
            "createdByEmail" to (currentUser.email ?: "")
        )
        firestore.collection("potholes").add(data)
            .addOnFailureListener { e ->
                Log.e("RoadWise-Cloud", "Silent sync failed for pothole at ${pothole.timestamp}", e)
            }
    }

    fun fetchFromCloud(context: Context, onComplete: (List<PotholeData>) -> Unit) {
        loadDeletedTimestamps(context)
        firestore.collection("potholes").orderBy("timestamp", Query.Direction.DESCENDING).limit(500).get()
            .addOnSuccessListener { result ->
                val cloud = result.mapNotNull { doc ->
                    try {
                        val severityStr = doc.getString("severity")
                        val severity = if (severityStr != null) Severity.valueOf(severityStr) else Severity.LOW
                        
                        PotholeData(
                            GeoPoint(doc.getDouble("lat")!!, doc.getDouble("lon")!!),
                            RoadFeature.valueOf(doc.getString("type")!!),
                            doc.getDouble("intensity")!!.toFloat(),
                            severity,
                            doc.getLong("timestamp")!!,
                            emptyList(),
                            doc.getString("createdByEmail") ?: ""
                        )
                    } catch(e: Exception) { 
                        Log.e("RoadWise-Repo", "Failed to parse document ${doc.id}", e)
                        null 
                    }
                }
                val local = getAllPotholes(context)

                // If authenticated, sync local-only potholes (offline records) to the cloud
                val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    val localOnly = local.filter { localItem -> 
                        localItem.timestamp !in deletedTimestamps && cloud.none { it.timestamp == localItem.timestamp } 
                    }
                    if (localOnly.isNotEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            for (pothole in localOnly) {
                                pushToCloudSilent(pothole)
                            }
                        }
                    }
                }

                val localMap = local.associateBy { it.timestamp }
                val mergedCloud = cloud.map { cloudItem ->
                    localMap[cloudItem.timestamp]?.let { localItem ->
                        cloudItem.copy(imagePaths = localItem.imagePaths)
                    } ?: cloudItem
                }

                val combined = (mergedCloud + local)
                    .filter { it.timestamp !in deletedTimestamps }
                    .distinctBy { it.timestamp }
                    .sortedByDescending { it.timestamp }
                
                saveAll(context, combined)
                cached = combined
                notifyUpdated()
                onComplete(combined)
            }
            .addOnFailureListener { e ->
                Log.e("RoadWise-Repo", "Firebase fetch failed completely", e)
            }
    }

    fun deletePothole(context: Context, timestamp: Long) {
        val potholes = getAllPotholes(context).toMutableList()
        val removed = potholes.removeAll { it.timestamp == timestamp }
        
        deletedTimestamps.add(timestamp)
        saveDeletedTimestamps(context)
        
        if (removed) {
            saveAllInternal(context, potholes)
        } else {
            notifyUpdated()
        }

        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d("RoadWise-Repo", "Attempting to delete from cloud: $timestamp")
                    val task = firestore.collection("potholes")
                        .whereEqualTo("timestamp", timestamp)
                        .get()
                    
                    val result = com.google.android.gms.tasks.Tasks.await(task)
                    Log.d("RoadWise-Repo", "Found ${result.size()} documents to delete for timestamp $timestamp")
                    
                    for (doc in result.documents) {
                        Log.d("RoadWise-Repo", "Deleting document: ${doc.id}")
                        com.google.android.gms.tasks.Tasks.await(doc.reference.delete())
                    }
                } catch (e: Exception) {
                    Log.e("RoadWise-Repo", "Failed to delete pothole from Firebase", e)
                }
            }
        }
    }

    fun resolveHotspot(context: Context, targetPotholes: List<PotholeData>, onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Remove from local storage
                val localData = getAllPotholes(context).toMutableList()
                val timestampsToRemove = targetPotholes.map { it.timestamp }.toSet()
                deletedTimestamps.addAll(timestampsToRemove)
                saveDeletedTimestamps(context)
                
                localData.removeAll { it.timestamp in timestampsToRemove }
                saveAllInternal(context, localData)

                // 2. Remove from Firebase
                val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    val chunks = timestampsToRemove.chunked(10) // Firestore 'in' queries are limited to 10
                    for (chunk in chunks) {
                        val task = firestore.collection("potholes").whereIn("timestamp", chunk).get()
                        val result = com.google.android.gms.tasks.Tasks.await(task)
                        for (doc in result.documents) {
                            com.google.android.gms.tasks.Tasks.await(doc.reference.delete())
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e("RoadWise-Repo", "Failed to resolve hotspot records", e)
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    fun saveAllInternal(context: Context, potholes: List<PotholeData>) {
        saveAll(context, potholes)
        cached = potholes
        notifyUpdated()
    }

    private fun saveAll(context: Context, potholes: List<PotholeData>) {
        val json = gson.toJson(potholes)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POTHOLES, json)
            .apply()
    }

    fun getAllPotholes(context: Context): List<PotholeData> {
        loadDeletedTimestamps(context)
        cached?.let { return it.filter { p -> p.timestamp !in deletedTimestamps } }
        return try {
            val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_POTHOLES, null) ?: return emptyList<PotholeData>().also { cached = it }

            val parser = JsonParser.parseString(json)
            if (!parser.isJsonArray) return emptyList<PotholeData>().also { cached = it }

            val array = parser.asJsonArray
            val result = mutableListOf<PotholeData>()
            for (element in array) {
                try {
                    val obj = element.asJsonObject
                    val locObj = obj.get("location").asJsonObject
                    val loc = GeoPoint(locObj.get("lat").asDouble, locObj.get("lon").asDouble)
                    val type = RoadFeature.valueOf(obj.get("type").asString)
                    val intensity = obj.get("intensity").asFloat
                    val timestamp = if (obj.has("timestamp")) obj.get("timestamp").asLong else System.currentTimeMillis()
                    
                    // Skip if item is in deleted list
                    if (timestamp in deletedTimestamps) continue
                    
                    val severity = if (obj.has("severity")) Severity.valueOf(obj.get("severity").asString) else Severity.LOW
                    val paths = if (obj.has("imagePaths")) gson.fromJson<List<String>>(obj.get("imagePaths"), object : TypeToken<List<String>>() {}.type) else emptyList()
                    val email = if (obj.has("createdByEmail")) obj.get("createdByEmail").asString else ""
                    result.add(PotholeData(loc, type, intensity, severity, timestamp, paths, email))
                } catch (e: Exception) { }
            }
            val sortedResult = result.sortedByDescending { it.timestamp }
            cached = sortedResult
            sortedResult
        } catch (e: Exception) { emptyList() }
    }

    private fun saveDeletedTimestamps(context: Context) {
        val json = gson.toJson(deletedTimestamps.toList())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DELETED, json)
            .apply()
    }

    private fun loadDeletedTimestamps(context: Context) {
        if (deletedTimestamps.isNotEmpty()) return
        try {
            val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DELETED, null) ?: return
            val list = gson.fromJson<List<Long>>(json, object : TypeToken<List<Long>>() {}.type)
            deletedTimestamps.addAll(list)
        } catch (e: Exception) { }
    }

    fun clearAll(context: Context) {
        val allPotholes = getAllPotholes(context)
        for (pothole in allPotholes) {
            for (path in pothole.imagePaths) {
                try { File(path).delete() } catch (_: Exception) { }
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_POTHOLES)
            .remove(KEY_DELETED)
            .apply()
        deletedTimestamps.clear()
        clearCache()
        notifyUpdated()
    }

    fun clearCache() {
        cached = null
    }

    fun getStorageSizeBytes(context: Context): Long {
        var totalSize = 0L
        
        // 1. Check External Files Dir (where we'll eventually save high-res photos)
        context.getExternalFilesDir(null)?.let { dir ->
            totalSize += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        
        // 2. Check Internal Files Dir
        context.filesDir?.let { dir ->
            totalSize += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        // 3. Check Cache Dir (OSM tiles, temporary reports)
        context.cacheDir?.let { dir ->
            totalSize += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        // 4. Check Shared Prefs (where the pothole database actually lives)
        try {
            val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
                totalSize += sharedPrefsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        } catch (e: Exception) {
            // Silently ignore if shared_prefs access fails
        }
        
        return totalSize
    }
}
