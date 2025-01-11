# PhotoDrop

Android app that transfers images to Python server using mDNS service discovery.

## Requirements

### Server
```bash
pip install flask zeroconf
python server.py
```

### Android
Add to app/build.gradle:
```gradle
dependencies {
    implementation 'org.jmdns:jmdns:3.5.7'
    implementation 'com.squareup.okhttp3:okhttp:4.9.0'
}
```

## Usage

1. Start Server:
```bash
python server.py
# Server starts on port 8000
# Creates 'uploaded_images' directory
```

2. Run Android App:
- Grant storage permissions
- App discovers server automatically via mDNS
- Click "Transfer Images"
- Images save to server's 'uploaded_images/{timestamp}/' directory

## API Endpoints

```
POST /upload_image - Upload image file
POST /update_count - Update total image count
GET /status - Get server stats
```

## Network
- Uses mDNS for automatic server discovery
- Works on local network
- Default port: 8000
- Service name: _imagecount._tcp.local.
