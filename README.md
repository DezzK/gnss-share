# GNSS Sharing System

A client-server Android application system that shares GNSS location data from a smartphone to a car multimedia system over WiFi hotspot connection.

## System Overview

**Server (Smartphone):**
- Collects high-precision GNSS data
- Runs as background service with foreground notification
- Streams location updates to connected clients via TCP
- Shows debugging information in notification panel
- Automatically manages power consumption
- Handles multiple client connections with individual heartbeat monitoring

**Client (Car Multimedia System):**
- Implements robust connection management with auto-reconnection
- Uses WiFi-aware reconnection
- Receives location data and provides system-wide mock GPS
- Shows detailed debugging information in main activity
- Displays connection status and reconnection attempts
- Handles WiFi state changes gracefully

## Architecture

```
Smartphone (Server)          Car System (Client)
┌─────────────────────┐      ┌─────────────────────┐
│ GNSS Chip           │      │                     │
│ ↓                   │      │ ← WiFi Hotspot      │
│ GNSSServerService   │ TCP  │   GNSSClientService │
│ ↓                   │ ───→ │   ↓                 │
│ TCP Server          │ 8887 │   ConnectionManager │
│ (192.168.43.1:8887) │      │   ↓                 │
│ Heartbeat Monitor   │ ←──→ │   Mock Location     │
└─────────────────────┘      └─────────────────────┘
```

## Protocol

- **Transport:** TCP over WiFi hotspot connection
- **Serialization:** Protocol Buffers (protobuf)
- **Connection:** Server IP is always `192.168.43.1:8887` (hotspot gateway)
- **Streaming:** Real-time location updates (1Hz)
- **Heartbeat:** 1-byte packet (0x01) every 1 second, 2s timeout

## Setup Instructions

### Building the Applications

1. **Clone and Setup:**
```bash
git clone 
cd gnss-share
```

2. **Protocol Buffers Setup:**
   - The build will automatically process `proto/location.proto`
   - Generated code will be available in `build/generated/source/proto/`

3. **Build Server App (Smartphone):**
```bash
./gradlew :server-app:assembleDebug
```

4. **Build Client App (Car System):**
```bash
./gradlew :client-app:assembleDebug
```

### Deployment Setup

#### Server Setup (Smartphone)

1. **Install the Server App** on the smartphone

2. **Grant Required Permissions:**
   - `ACCESS_FINE_LOCATION`
   - `ACCESS_COARSE_LOCATION`
   - `FOREGROUND_SERVICE`
   - `ACCESS_NETWORK_STATE`
   - `ACCESS_WIFI_STATE`
   - `CHANGE_WIFI_STATE`
   - `WAKE_LOCK`
   - Add to battery optimization whitelist (extremely important for Xiaomi devices)

3. **Enable WiFi Hotspot:**
   - Go to Settings → Network & Internet → Hotspot & tethering
   - Enable "WiFi hotspot"
   - Note the hotspot name and password

#### Client Setup (Car Multimedia System)

1. **Install the Client App** on the car system

2. **Enable Developer Options:**
   - Go to Settings → About → Tap "Build number" 7 times
   - Go to Settings → Developer Options
   - Enable "Allow mock locations"
   - Add client app to mock location apps

3. **Grant Required Permissions:**
   - `ACCESS_FINE_LOCATION`
   - `ACCESS_COARSE_LOCATION`
   - `ACCESS_MOCK_LOCATION`
   - `ACCESS_NETWORK_STATE`
   - `ACCESS_WIFI_STATE`
   - `CHANGE_WIFI_STATE`
   - `ACCESS_LOCATION_EXTRA_COMMANDS`

4. **Connect to WiFi Hotspot:**
   - Connect car system to smartphone's WiFi hotspot
   - Verify connection and internet access

## Usage Instructions

### Starting the System

1. **On Smartphone (Server):**
   - Launch "GNSS Server" app
   - Grant all requested permissions
   - Tap "Start Server"
   - Check notification shows "Server Running"
   - The service will continue running in background

2. **On Car System (Client):**
   - Ensure WiFi is connected to smartphone hotspot
   - Launch "GNSS Client" app
   - Grant all requested permissions
   - App will automatically connect to server (watch for connection toast)
   - Verify mock location provider is active

### Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

### License

This project is provided as-is for educational and development purposes.
