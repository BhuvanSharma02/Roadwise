package com.roadwise.routing

import org.osmdroid.util.GeoPoint
import kotlin.math.cos

object BoundingBoxUtils {
    // Generates a square polygon around a point.
    // Length of 1 degree of latitude is ~111.32km = 111320m
    // Length of 1 degree of longitude is ~111.32km * cos(lat)
    fun getPotholePolygon(point: GeoPoint, sizeMeters: Double = 15.0): List<List<Double>> {
        val halfSize = sizeMeters / 2.0
        
        val latOffset = halfSize / 111320.0
        val lonOffset = halfSize / (111320.0 * cos(Math.toRadians(point.latitude)))
        
        val topLeft = listOf(point.longitude - lonOffset, point.latitude + latOffset)
        val topRight = listOf(point.longitude + lonOffset, point.latitude + latOffset)
        val bottomRight = listOf(point.longitude + lonOffset, point.latitude - latOffset)
        val bottomLeft = listOf(point.longitude - lonOffset, point.latitude - latOffset)
        
        // Return a LinearRing (must close on itself)
        return listOf(topLeft, topRight, bottomRight, bottomLeft, topLeft)
    }
}
