# Thermo Monitor (Termo Spotter)

A Bluetooth-enabled temperature monitoring application that connects to HC-05 modules to read and display temperature data.

## Features

- **Bluetooth Device Management**: Easily scan for and connect to HC-05 Bluetooth modules
- **Real-time Temperature Monitoring**: Display current temperature readings with visual indicators
- **Temperature History**: Track the last 5 temperature measurements with timestamps
- **Temperature Status Indicators**: 
  - Normal Temperature (below 37.0°C): Green
  - Borderline Temperature (37.0-37.5°C): Orange
  - Mild Fever (37.5-38.0°C): Light Red
  - High Fever (above 38.0°C): Dark Red with alarm
- **Detailed Measurement View**: Click on any history item to see detailed information
- **Audio Alerts**: Alarm sounds for high temperature readings

## Technical Details

- **Bluetooth Connectivity**: Implements robust error handling for HC-05 modules
- **Temperature Data Format**: Supports multiple data formats from various temperature sensors:
  - Simple numbers (e.g., "36.5")
  - Prefixed format (e.g., "T:36.5", "TEMP:36.5")
  - JSON format with temp key
- **Permissions Handling**: Properly handles required Bluetooth and location permissions
- **Background Monitoring**: Can run temperature checks in the background at regular intervals

## Requirements

- Android 8.0 (API level 26) or higher
- Bluetooth-enabled device
- HC-05 Bluetooth module connected to temperature sensor

## Setup Instructions

1. Clone the repository
2. Open the project in Android Studio
3. Connect an Android device with developer options and USB debugging enabled
4. Build and run the application on your device
5. Ensure your HC-05 module is powered and properly connected to a temperature sensor
6. Use the app to scan for and connect to your HC-05 module
7. Start monitoring temperature

## Hardware Configuration

This application is designed to work with a temperature sensor connected to an Arduino with an HC-05 Bluetooth module. The Arduino should be programmed to:

1. Read temperature data from the sensor
2. Format the data in one of the supported formats (e.g., "T:36.5")
3. Send the data via the HC-05 module when requested

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contact

For more information, you can contact the developer at [GitHub](https://github.com/Conquerorr0). 