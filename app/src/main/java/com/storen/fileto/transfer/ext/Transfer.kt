package com.storen.fileto.transfer.ext

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

object Transfer {

    private const val TAG: String = "Transfer"

    data class TransferInfo(
        val type: Int, // 0 file, 1 bytes
        val size: Long,
        val index: Int,
        val name: String,
        val md5: String
    ) {
        companion object {
            fun toJson(): String = Gson().toJson(this)
            fun fromJson(json: String) = Gson().fromJson(json, TransferInfo::class.java)
        }
    }

    private val Dispatchers.transfer by lazy {
        Executors.newFixedThreadPool(16).asCoroutineDispatcher()
    }

    private val transferScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.transfer + CoroutineExceptionHandler { ctx, e ->
            Log.w(TAG, "transferScope exception.", e)
        })
    }

    private var server: ServerSocket? = null
    private val clientsQueue: BlockingQueue<Socket> = LinkedBlockingQueue(10)
    private var targetAddress: InetAddress? = null

    fun setup(address: InetAddress?, port: Int) {
        this.targetAddress = address
        transferScope.launch {
            server = ServerSocket(port)
            server?.let {
                val socket = it.accept()
                if (targetAddress == null) {
                    targetAddress = socket.inetAddress
                }
                socket.read()
            }
        }
        transferScope.launch {
            while (isActive) {
                try {
                    val socket = Socket(targetAddress, port)
                    if (socket.isConnected) {
                        clientsQueue.put(socket)
                    }
                } catch (e: IOException) {
                    throw e
                }
                delay(500)
            }
        }
    }

    fun Socket.read(): Flow<Pair<TransferInfo, Any>> {
        return callbackFlow {
            inputStream.use {
                val json = it.bufferedReader().readLine()
                val info = TransferInfo.fromJson(json)
                val buffer = ByteArray(1024)
                if (info.type == 0) {
                    val outFile: File = File("")
                    val fileOut = outFile.outputStream()
                    while (it.read(buffer) != -1) {
                        // 写入文件
                        fileOut.write(buffer)
                    }
                    send(Pair(info, outFile))
                } else {
                    val bytes = mutableListOf<Byte>()
                    while (it.read(buffer) != -1) {
                        // 写入缓存
                        bytes.addAll(buffer.toList())
                    }
                    send(Pair(info, bytes))
                }
            }
        }
    }

    fun exit() {
        transferScope.cancel("EXIT!")
    }
}

fun Any.transfer() {
    when (this) {
        is File -> {

        }

        is Bitmap -> {

        }

        is View -> {

        }

        is String -> {
            this.toByteArray()
        }

        is Serializable -> {
            // TODO ObjectOutputStream or JSON?
        }
    }
}