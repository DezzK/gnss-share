# GNSS Sharing System

A client-server Android application system that shares GNSS location data from a smartphone to a car multimedia system over WiFi hotspot connection.

## System Overview

**Server (Smartphone):**
- Collects high-precision GNSS data
- Runs as background service with foreground notification
- Streams location updates to connected clients via TCP
- Implements heartbeat mechanism for connection monitoring
- Shows debugging information in notification panel
- Automatically manages power consumption
- Handles multiple client connections with individual heartbeat monitoring

**Client (Car Multimedia System):**
- Implements robust connection management with auto-reconnection
- Uses WiFi-aware reconnection with exponential backoff (500ms to 5s)
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
- **Heartbeat:** 1-byte packet (0x01) every 2 seconds, 10s timeout

### Message Types

1. **LocationUpdate**
```protobuf
message LocationUpdate {
  int64 timestamp = 1;           // Unix timestamp in milliseconds
  double latitude = 2;
  double longitude = 3;
  double altitude = 4;
  float accuracy = 5;            // Horizontal accuracy in meters
  float bearing = 6;             // Degrees from north
  float speed = 7;               // m/s
  int32 satellites = 8;          // Number of satellites used
  string provider = 9;           // "gps", "network", "fused", etc.
  float location_age = 10;       // Seconds since fix
  float vertical_accuracy = 11;  // Vertical accuracy in meters
  float bearing_accuracy = 12;   // Bearing accuracy in degrees
  float speed_accuracy = 13;     // Speed accuracy in m/s
}
```

2. **Heartbeat**
- Single byte `0x01` sent every 2 seconds from client to server
- Server expects heartbeat within 10 seconds or disconnects client

## Setup Instructions

### Prerequisites

1. **Development Environment:**
   - Android Studio Flamingo or later
   - Android SDK 33+ (Android 13+)
   - Protocol Buffers compiler
   - Java 11+

2. **Hardware Requirements:**
   - Android smartphone with GNSS capability (Android 13+)
   - Android car multimedia system (Android 9+)
   - WiFi hotspot capability on smartphone

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
   - Add to battery optimization whitelist

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

### Monitoring Status

**Server Notification Panel Shows:**
- Number of connected clients
- Satellite count and GNSS status
- Location age (seconds since last fix)
- Heartbeat status for each client

**Client Activity Shows:**
- Connection status to server (connected/reconnecting/disconnected)
- Current location coordinates with accuracy
- Satellite count and provider information
- Speed, bearing, and other movement data
- Data age and last update timestamp
- Reconnection attempt count and next retry time

### Connection Management

The system implements robust connection handling:

1. **Automatic Reconnection:**
   - Client detects WiFi state changes
   - Implements exponential backoff (500ms to 5s)
   - Maximum of 10 reconnection attempts before giving up

2. **Heartbeat Monitoring:**
   - Client sends heartbeat every 2 seconds
   - Server disconnects clients after 10 seconds of inactivity
   - Automatic reconnection on disconnection

3. **State Management:**
   - Clear state machine (DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING)
   - UI reflects current connection state
   - Graceful handling of network changes

## Troubleshooting

### Connection Issues

**"Failed to connect to GNSS server"**
- Verify car system is connected to smartphone's hotspot
- Check hotspot gateway IP is `192.168.43.1`
- Ensure server is running and accessible
- Check for firewall/security software blocking connections

**"Connection lost, reconnecting..."**
- Check WiFi signal strength
- Verify server is still running
- Wait for automatic reconnection
- Restart client app if needed

**"Mock location setup failed"**
- Enable "Allow mock locations" in Developer Options
- Grant location permissions to client app
- Ensure app is selected as mock location provider
- Restart device if issues persist

### Location Issues

**"No satellite lock"**
- Move to open area with clear sky view
- Wait 1-2 minutes for GPS cold start
- Check smartphone GPS is working in other apps
- Verify location services are enabled

**"Old location data"**
- Check server notification shows active GNSS
- Verify network connection stability
- Check for app battery optimization settings
- Restart server service if needed

## Development Notes

### Key Implementation Details

1. **Connection Management:**
   - `ConnectionManager` class handles all connection logic
   - State machine for clear state transitions
   - WiFi-aware reconnection with backoff
   - Heartbeat mechanism for connection health

2. **Power Management:**
   - Server only collects GNSS data when clients connected
   - Uses foreground service with notification
   - Efficient protobuf serialization
   - Wake locks used appropriately

3. **Error Handling:**
   - Comprehensive error handling and recovery
   - Automatic reconnection on failures
   - User feedback for critical errors
   - Detailed logging for debugging

4. **Security:**
   - No authentication (local WiFi network only)
   - Input validation on all messages
   - Secure permission handling

### Building and Testing

To build and test the applications:

```bash
# Build both apps
./gradlew assembleDebug

# Run tests
./gradlew test

# Install debug builds
./gradlew :server-app:installDebug
./gradlew :client-app:installDebug
```

### Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

### License

This project is provided as-is for educational and development purposes.
