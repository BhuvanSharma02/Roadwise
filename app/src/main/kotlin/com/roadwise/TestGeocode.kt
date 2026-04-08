package com.roadwise

import org.json.JSONObject
import java.net.URL

fun main() {
    try {
        val url = "https://nominatim.openstreetmap.org/reverse?lat=20.5937&lon=78.9629&format=json"
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("User-Agent", "RoadWise/1.0 (Android)")
        val text = connection.inputStream.bufferedReader().readText()
        println("Raw json: $text")
        val json = JSONObject(text)
        val address = json.optString("display_name", "").split(",").take(2).joinToString(", ")
        println("Address parsed: $address")
    } catch(e: Exception) {
        println("Error: ${e.message}")
    }
}
