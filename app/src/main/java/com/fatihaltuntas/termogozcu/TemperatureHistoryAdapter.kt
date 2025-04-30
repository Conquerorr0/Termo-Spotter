package com.fatihaltuntas.termogozcu

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fatihaltuntas.termogozcu.R

class TemperatureHistoryAdapter(private var measurements: List<TemperatureMeasurement>) : 
    RecyclerView.Adapter<TemperatureHistoryAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val temperatureValueText: TextView = view.findViewById(R.id.temperatureValueText)
        val timeText: TextView = view.findViewById(R.id.timeText)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_temperature_history, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val measurement = measurements[position]
        holder.temperatureValueText.text = measurement.getFormattedTemperature()
        holder.timeText.text = measurement.getFormattedDateTime()
        
        // Set text color based on temperature value
        val context = holder.temperatureValueText.context
        val statusText: String
        val textColor: Int
        
        when {
            measurement.temperature >= 38.0 -> {
                textColor = context.getColor(android.R.color.holo_red_dark)
                statusText = "WARNING: High Fever!"
            }
            measurement.temperature > 37.5 -> {
                textColor = context.getColor(android.R.color.holo_red_light)
                statusText = "Mild Fever"
            }
            measurement.temperature >= 37.0 -> {
                textColor = context.getColor(android.R.color.holo_orange_dark)
                statusText = "Borderline Temperature"
            }
            else -> {
                textColor = context.getColor(android.R.color.holo_green_dark)
                statusText = "Normal Temperature"
            }
        }
        
        holder.temperatureValueText.setTextColor(textColor)
        
        // Click event to show details dialog
        holder.itemView.setOnClickListener {
            showDetailsDialog(context, measurement, statusText, textColor)
        }
    }
    
    private fun showDetailsDialog(context: android.content.Context, measurement: TemperatureMeasurement, 
                                 statusText: String, textColor: Int) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.temperature_details_dialog, null)
        
        // Set up dialog components
        val temperatureDetailValue: TextView = dialogView.findViewById(R.id.temperatureDetailValue)
        val dateDetailValue: TextView = dialogView.findViewById(R.id.dateDetailValue)
        val timeDetailValue: TextView = dialogView.findViewById(R.id.timeDetailValue)
        val statusDetailValue: TextView = dialogView.findViewById(R.id.statusDetailValue)
        val closeButton: Button = dialogView.findViewById(R.id.closeButton)
        
        // Set data
        temperatureDetailValue.text = measurement.getFormattedTemperature()
        temperatureDetailValue.setTextColor(textColor)
        dateDetailValue.text = measurement.getFormattedDate()
        timeDetailValue.text = measurement.getFormattedTime()
        statusDetailValue.text = statusText
        statusDetailValue.setTextColor(textColor)
        
        // Create and show dialog
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Close dialog when close button is clicked
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    override fun getItemCount() = measurements.size
    
    fun updateMeasurements(newMeasurements: List<TemperatureMeasurement>) {
        measurements = newMeasurements
        notifyDataSetChanged()
    }
} 