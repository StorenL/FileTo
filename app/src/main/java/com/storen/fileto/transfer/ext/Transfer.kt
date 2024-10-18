package com.storen.fileto.transfer.ext

import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class Transfer {
    companion object {
        private const val TAG: String = "Transfer"

        private const val THREADS_COUNT: Int = 16
        private const val CLIENT_COUNT: Int = 10
        private const val FRAME_SIZE: Int = 1024
        private const val WAITING_DELAY: Long = 500L

        private val DOWNLOADS_DIR: File
            get() =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        private val sTransferMap: MutableMap<Int, Transfer> = mutableMapOf()

        @Synchronized
        internal fun getTransferInstance(id: Int): Transfer {
            return sTransferMap.computeIfAbsent(id) {
                Transfer()
            }
        }
    }

    private var mRemoteServerAddress: InetAddress? = null
    private var mRemoteServerPort: Int = -1

    private val _receiveFlow: MutableStateFlow<Pair<Description, Any>?> = MutableStateFlow(null)
    val receiveFlow: StateFlow<Pair<Description, Any>?> = _receiveFlow.asStateFlow()

    private var mServerSocket: ServerSocket? = null
    private val mClientsQueue: BlockingQueue<Socket> = LinkedBlockingQueue(CLIENT_COUNT)

    private val Dispatchers.transfer by lazy {
        Executors.newFixedThreadPool(THREADS_COUNT).asCoroutineDispatcher()
    }

    private val transferScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.transfer + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "transferScope exception.", e)
        })
    }

    fun getStateInfo(): String {
        return "$mRemoteServerAddress == $mRemoteServerPort == ${mClientsQueue.size}"
    }

    fun setup(
        selfServerPort: Int,
        remoteServerPort: Int,
        remoteServerAddress: InetAddress? = null
    ) {
        Log.d(TAG, "setupTransfer: ${selfServerPort} == ${remoteServerPort} ${remoteServerAddress}")
        clear()
        mRemoteServerAddress = remoteServerAddress
        mRemoteServerPort = remoteServerPort
        transferScope.launch {
            mServerSocket = ServerSocket(selfServerPort)
            mServerSocket?.let {
                while (isActive) {
                    val socket = it.accept()
                    if (mRemoteServerAddress == null) {
                        mRemoteServerAddress = socket.inetAddress
                    }
                    Log.d(TAG, "setup: listenerFlow start")
                    listenerFlow(socket.read())
                    Log.d(TAG, "setup: listenerFlow end")
                }
            }
        }
        transferScope.launch {
            while (isActive) {
                if (mRemoteServerAddress != null && mRemoteServerPort > 0) {
                    try {
                        val socket = Socket(mRemoteServerAddress, mRemoteServerPort)
                        if (socket.isConnected) {
                            mClientsQueue.put(socket)
                            Log.d(TAG, "setup: putQueueSize " + mClientsQueue.size)
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "setup: connect remote Exception.")
                        delay(WAITING_DELAY)
                    }
                } else {
                    Log.d(TAG, "setup: waiting for client")
                    delay(WAITING_DELAY)
                }
            }
        }
    }


    private fun listenerFlow(flow: Flow<Pair<Description, Any>>) {
        transferScope.launch {
            flow.collect {
                _receiveFlow.emit(it)
            }
        }
    }

    private fun Socket.read(): Flow<Pair<Description, Any>> {
        return callbackFlow {
            inputStream.use {
                val json = it.bufferedReader().readLine()
                Log.d(TAG, "read_check: descJson: $json")
                val desc = Description.fromJson(json)
                Log.d(TAG, "read_check: $desc")
                val buffer = ByteArray(FRAME_SIZE)
                var count = 0
                if (desc.type == 0) {
                    val outFile: File = getNewFile("REC_${UUID.randomUUID()}_${desc.name}")
                    val fileOut = outFile.outputStream()
                    while (it.read(buffer).also { count = it } != -1) {
                        // 写入文件
                        fileOut.write(buffer.sliceArray(0 until count))
                    }
                    send(Pair(desc, outFile))
                } else {
                    val bytes = mutableListOf<Byte>()
                    while (it.read(buffer).also { count = it } != -1) {
                        // 写入缓存
                        bytes.addAll(buffer.sliceArray(0 until count).toList())
                    }
                    val data = bytes.toByteArray()
                    Log.d(TAG, "read: read_check_result: ${String(data)}")
                    send(Pair(desc, data))
                }
            }
            close()

            awaitClose {
                try {
                    shutdownInput()
                } catch (_: Exception) {
                    Log.w(TAG, "shutdownInput exception.")
                }
            }
        }
    }

    fun exit() {
        Log.d(TAG, "exit start: !!!")
        transferScope.cancel("EXIT!")
        clear()
        Log.d(TAG, "exit end: !!!")
    }

    private fun clear() {
        mServerSocket?.close()
        sTransferMap.clear()
        mClientsQueue.forEach { it.close() }
        mClientsQueue.clear()
    }

    private fun getNewFile(name: String): File {
        return File(DOWNLOADS_DIR.absolutePath + File.separator + name)
    }

    private fun send(data: ByteArray, desc: Description): StateFlow<TransferInfo> {
        val infoFlow: MutableStateFlow<TransferInfo> = MutableStateFlow(
            TransferInfo(
                desc = desc,
                sTime = System.currentTimeMillis(),
                transferSize = 0,
                totalSize = data.size.toLong()
            )
        )
        transferScope.launch {
            var socket = mClientsQueue.take()
            while (!socket.isConnected) {
                socket = mClientsQueue.take()
            }
            val json = desc.toJson()
            Log.d(TAG, "send: $json")
            socket.getOutputStream().use {
                it.write((json + "\n").toByteArray())
                it.flush()
                // 字节数组字节数组字节数组字节数组字节数组字节数组字节数组字节数组字节数组字节数组字节数组字节数组
                data.indices.windowed(FRAME_SIZE, FRAME_SIZE, true).forEach { ints ->
                    val bytes = data.sliceArray(ints)
                    it.write(bytes)
                    infoFlow.emit(
                        infoFlow.value.copy(transferSize = infoFlow.value.transferSize + bytes.size)
                    )
                }
                it.flush()
            }
            socket.close()
            infoFlow.emit(
                infoFlow.value.copy(eTime = System.currentTimeMillis(), isSuccess = true)
            )
        }
        return infoFlow.asStateFlow()
    }

    private fun send(file: File, desc: Description): StateFlow<TransferInfo> {
        val infoFlow: MutableStateFlow<TransferInfo> = MutableStateFlow(
            TransferInfo(
                desc = desc,
                sTime = System.currentTimeMillis(),
                transferSize = 0,
                totalSize = file.length()
            )
        )
        transferScope.launch {
            var socket = mClientsQueue.take()
            while (!socket.isConnected) {
                socket = mClientsQueue.take()
            }
            val json = desc.toJson()
            Log.d(TAG, "send: $json")
            socket.getOutputStream().use {
                it.write((json + "\n").toByteArray())
                it.flush()
                // 文件文件文件文件文件文件文件文件文件文件文件文件文件文件文件
                var buffer = ByteArray(FRAME_SIZE)
                val inputStream = file.inputStream()
                var readCount: Long = 0
                inputStream.use { fileIn ->
                    while (fileIn.read(buffer).also { readCount += it } != -1) {
                        it.write(buffer)
                        infoFlow.emit(
                            infoFlow.value.copy(transferSize = readCount)
                        )
                    }
                    it.flush()
                }
            }
            socket.close()
            infoFlow.emit(
                infoFlow.value.copy(eTime = System.currentTimeMillis(), isSuccess = true)
            )
        }
        return infoFlow.asStateFlow()
    }

    fun launch(block: suspend Transfer.() -> Unit) {
        transferScope.launch {
            this@Transfer.block()
        }
    }

    fun Any.transfer(desc: Description): Flow<TransferInfo> {
        return when (this) {
            is ByteArray -> {
                send(this, desc)
            }

            is File -> {
                send(this, desc)
            }

            else -> {
                emptyFlow()
            }
        }
    }

    /**
     * 传输过程中的信息
     */
    data class TransferInfo(
        val desc: Description,
        val sTime: Long = -1,
        val eTime: Long = -1,
        val isSuccess: Boolean = false,
        val isFailed: Boolean = false,
        val transferSize: Long = 0,
        val totalSize: Long = 0,
    )

    /**
     * 描述发送数据信息的DataBean
     */
    data class Description(
        val id: Int,
        val type: Int, // 0 file, 1 bytes
        val size: Long,
        val index: Int,
        val name: String,
        val md5: String
    ) {
        companion object {
            fun fromJson(json: String): Description = Gson().fromJson(json, Description::class.java)
        }

        fun toJson(): String = Gson().toJson(this)
    }
}
