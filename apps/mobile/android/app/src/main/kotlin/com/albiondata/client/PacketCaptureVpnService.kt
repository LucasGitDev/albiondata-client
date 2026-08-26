package com.albiondata.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PacketCaptureVpnService : VpnService() {

    companion object {
        private const val TAG = "PacketCaptureVpn"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_capture_channel"
        private const val BUFFER_SIZE = 32768
        private const val TUN_MTU = 1500

        const val ACTION_START = "com.albiondata.client.VPN_START"
        const val ACTION_STOP = "com.albiondata.client.VPN_STOP"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundWithNotification()
                startCapture()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val stopIntent = Intent(this, PacketCaptureVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.vpn_stop_action),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.vpn_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startCapture() {
        if (running.getAndSet(true)) return

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .setMtu(TUN_MTU)

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            running.set(false)
            return
        }

        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface is null after establish()")
            running.set(false)
            return
        }

        Log.i(TAG, "VPN interface established, starting packet capture")

        captureThread = Thread({
            readPackets()
        }, "packet-capture").also { it.start() }
    }

    private fun readPackets() {
        val iface = vpnInterface ?: return
        val stream = FileInputStream(iface.fileDescriptor)
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        var packetCount = 0L

        Log.i(TAG, "Packet capture loop started")

        while (running.get()) {
            buffer.clear()
            val length = try {
                stream.read(buffer.array())
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Error reading from TUN fd", e)
                break
            }

            if (length > 0) {
                packetCount++
                Log.d(TAG, "Packet #$packetCount: $length bytes")
            }
        }

        Log.i(TAG, "Packet capture loop stopped. Total packets: $packetCount")
    }

    private fun stopCapture() {
        running.set(false)
        captureThread?.interrupt()
        captureThread = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        Log.i(TAG, "VPN capture stopped")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
