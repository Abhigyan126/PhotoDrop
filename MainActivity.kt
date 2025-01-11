package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.jmdns.JmDNS
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceEvent
import java.net.InetAddress
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.INTERNET
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET
        )
    }
    private var hasSentCount = false
    private var jmdns: JmDNS? = null
    private var serverUrl: String? = null
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    // Status updates management
    private val _statusUpdates = MutableStateFlow<List<StatusUpdate>>(emptyList())
    private val statusUpdates = _statusUpdates.asStateFlow()

    // Transfer state management
    private val _isTransferring = MutableStateFlow(false)
    private val isTransferring = _isTransferring.asStateFlow()

    data class StatusUpdate(
        val message: String,
        val type: StatusType,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class StatusType {
        INFO, SUCCESS, ERROR
    }

    private fun addStatusUpdate(message: String, type: StatusType) {
        CoroutineScope(Dispatchers.Main).launch {
            val currentList = _statusUpdates.value.toMutableList()
            currentList.add(0, StatusUpdate(message, type))
            _statusUpdates.value = currentList.take(10)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            addStatusUpdate("Permissions granted", StatusType.SUCCESS)
            startServiceDiscovery()
            updateUI(true)
        } else {
            addStatusUpdate("Some permissions were denied", StatusType.ERROR)
            updateUI(false)
        }
    }

    private fun updateUI(permissionGranted: Boolean) {
        setContent {
            MyApplicationTheme {
                MainContent(
                    imageCount = if (permissionGranted) countImages() else -1,
                    permissionGranted = permissionGranted,
                    onRequestPermission = { requestPermissions() },
                    onTransferImages = { transferImages() },
                    isTransferring = isTransferring.collectAsState().value,
                    statusUpdates = statusUpdates.collectAsState().value
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateUI(checkPermissionsGranted())
    }

    private fun checkPermissionsGranted(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }

    private fun startServiceDiscovery() {
        addStatusUpdate("Starting service discovery...", StatusType.INFO)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val localInetAddress = getLocalIpAddress()
                addStatusUpdate("Local IP: ${localInetAddress.hostAddress}", StatusType.INFO)
                jmdns = JmDNS.create(localInetAddress)

                jmdns?.addServiceListener("_imagecount._tcp.local.", object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        addStatusUpdate("Service found: ${event.name}", StatusType.INFO)
                        jmdns?.requestServiceInfo(event.type, event.name)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        addStatusUpdate("Service removed: ${event.name}", StatusType.INFO)
                        if (serverUrl?.contains(event.info.name) == true) {
                            serverUrl = null
                        }
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val host = event.info.getHostAddresses()[0]
                        val port = event.info.port
                        serverUrl = "http://$host:$port"
                        addStatusUpdate("Server connected at $serverUrl", StatusType.SUCCESS)
                    }
                })
            } catch (e: Exception) {
                addStatusUpdate("Service discovery error: ${e.message}", StatusType.ERROR)
            }
        }
    }

    private fun getLocalIpAddress(): InetAddress {
        NetworkInterface.getNetworkInterfaces().iterator().forEach { networkInterface ->
            networkInterface.inetAddresses.iterator().forEach { address ->
                if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') == -1) {
                    return address
                }
            }
        }
        return InetAddress.getLocalHost()
    }

    private fun countImages(): Int {
        if (hasSentCount) {
            // If the count has already been sent, don't send it again
            return 0
        }

        var count = 0
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        try {
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.IS_PENDING} = 0"
            } else {
                null
            }

            contentResolver.query(
                queryUri,
                projection,
                selection,
                null,
                null
            )?.use { cursor ->
                count = cursor.count
                sendImageCount(count)
            }
        } catch (e: Exception) {
            addStatusUpdate("Error counting images: ${e.message}", StatusType.ERROR)
        }

        // After counting and sending the count, set the flag to true
        hasSentCount = true
        return count
    }


    private fun sendImageCount(count: Int) {
        if (serverUrl == null) {
            addStatusUpdate("Cannot send count: Server not found", StatusType.ERROR)
            return
        }

        val json = JSONObject().apply {
            put("count", count)
        }.toString()

        val request = Request.Builder()
            .url("$serverUrl/update_count")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                addStatusUpdate("Failed to send count: ${e.message}", StatusType.ERROR)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    addStatusUpdate("Count sent successfully", StatusType.SUCCESS)
                } else {
                    addStatusUpdate("Failed to send count: ${response.message}", StatusType.ERROR)
                }
                response.close()
            }
        })
    }

    private fun transferImages() {
        if (serverUrl == null) {
            addStatusUpdate("Cannot transfer images: Server not found", StatusType.ERROR)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                _isTransferring.value = true
                val images = getImagesFromStorage()
                addStatusUpdate("Found ${images.size} images to transfer", StatusType.INFO)

                images.forEachIndexed { index, imageUri ->
                    try {
                        sendImage(imageUri, index + 1, images.size)
                    } catch (e: Exception) {
                        addStatusUpdate("Failed to send image ${index + 1}: ${e.message}", StatusType.ERROR)
                    }
                }
            } catch (e: Exception) {
                addStatusUpdate("Error accessing images: ${e.message}", StatusType.ERROR)
            } finally {
                _isTransferring.value = false
            }
        }
    }

    private fun getImagesFromStorage(): List<Uri> {
        val images = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        } else {
            null
        }

        contentResolver.query(
            queryUri,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(queryUri, id.toString())
                images.add(contentUri)
            }
        }

        return images
    }

    private suspend fun sendImage(imageUri: Uri, index: Int, total: Int) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val byteArrayOutputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "image",
                        "image_$index.jpg",
                        imageBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("$serverUrl/upload_image")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        addStatusUpdate("Transferred image $index of $total", StatusType.SUCCESS)
                    } else {
                        addStatusUpdate("Failed to transfer image $index: ${response.message}", StatusType.ERROR)
                    }
                }
            } catch (e: Exception) {
                addStatusUpdate("Error processing image $index: ${e.message}", StatusType.ERROR)
            }
        }
    }

    @Composable
    fun MainContent(
        imageCount: Int,
        permissionGranted: Boolean,
        onRequestPermission: () -> Unit,
        onTransferImages: () -> Unit,
        isTransferring: Boolean,
        statusUpdates: List<StatusUpdate>
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top
            ) {
                if (permissionGranted) {
                    if (imageCount == -1) {
                        CircularProgressIndicator()
                        Text("Counting images...", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        Text(
                            "Number of images: $imageCount",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Button(
                            onClick = { onTransferImages() },
                            modifier = Modifier.padding(bottom = 16.dp),
                            enabled = !isTransferring
                        ) {
                            Text(if (isTransferring) "Transferring..." else "Transfer Images")
                        }

                        if (isTransferring) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Storage permission is required to access images",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(onClick = onRequestPermission) {
                            Text("Grant Storage Permission")
                        }
                    }
                }

                Text(
                    "Status Updates",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(statusUpdates) { status ->
                        val color = when (status.type) {
                            StatusType.SUCCESS -> MaterialTheme.colorScheme.primary
                            StatusType.ERROR -> MaterialTheme.colorScheme.error
                            StatusType.INFO -> MaterialTheme.colorScheme.secondary
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
                        ) {
                            Text(
                                text = status.message,
                                modifier = Modifier.padding(8.dp),
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}