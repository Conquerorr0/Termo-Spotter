package com.fatihaltuntas.termogozcu

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TemperatureMeasurement(
    val temperature: Double,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedTemperature(): String {
        return String.format("%.1f°C", temperature)
    }
    
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    fun getFormattedDateTime(): String {
        return "${getFormattedTime()} ${getFormattedDate()}"
    }
} 