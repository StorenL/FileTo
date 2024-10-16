package com.storen.fileto.transfer.socket

import android.util.Log
import com.storen.fileto.transfer.DataFrameProcess
import com.storen.fileto.transfer.DataFrameUtil
import com.storen.fileto.transfer.DataSliceProcess
import com.storen.fileto.transfer.DataSliceUtil
import com.storen.fileto.transfer.DataTransfer
import com.storen.fileto.transfer.TransferManager
import com.storen.fileto.transfer.TRANSFER_DATA_FRAME_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Socket 获取/控制 接口
 */
sealed interface SocketInterface {
    suspend fun run()
    suspend fun stop()

    // 获取对端的Socket，然后进行数据传输等操作
    suspend fun <T> takeRemoteSocket(block: suspend Socket.() -> T): T?
}

object SocketDataTransfer : DataTransfer<Socket> {
    private const val TAG: String = "SocketDataTransfer"

    override suspend fun Socket.send(byteArray: ByteArray): Boolean {
        if (!this.isConnected) {
            Log.i(TAG, "sendBytes: failed by disconnect.")
            return false
        }
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "sendBytes: io start.")
            getOutputStream().let {
                it.write(byteArray)
                it.flush()
            }
            Log.d(TAG, "sendBytes: io end.")
            true
        }
    }

    @Volatile
    private var receiveCount: Int = 0

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun Socket.receive(): Flow<ByteArray> {
        if (!this.isConnected) {
            Log.i(TAG, "receiveBytes: failed by disconnect.")
            return emptyFlow()
        }
        return callbackFlow {
            Log.d(TAG, "receiveBytes: io start.")
            var isActive = true
            getInputStream().let {
                var buffer = ByteArray(TRANSFER_DATA_FRAME_SIZE)
                var count: Int
                while (it.read(buffer).also { count = it } != -1 && isActive) {
                    send(buffer)
                    buffer = ByteArray(TRANSFER_DATA_FRAME_SIZE)
                    Log.d(TAG, "receiveBytes: $count")
                }
            }
            Log.d(TAG, "receiveBytes: io end.")
            awaitClose {
                isActive = false
                Log.d(TAG, "receive: await close.")
            }
        }.flowOn(Dispatchers.IO)
    }
}

internal class SocketServer(
    private val port: Int
) : SocketInterface, TransferManager,
    DataSliceProcess by DataSliceUtil,
    DataFrameProcess by DataFrameUtil,
    DataTransfer<Socket> by SocketDataTransfer {

    companion object {
        private const val TAG: String = "SocketServer"
    }

    private var mRemoteSocket: Socket? = null
    private var mServerSocket: ServerSocket? = null

    override suspend fun run() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "run: io start.")
            val serverSocket = ServerSocket(port)
            mServerSocket = serverSocket
            Log.d(TAG, "run: server ${serverSocket.localSocketAddress}")
            try {
                while (!serverSocket.isClosed) {
                    mRemoteSocket = serverSocket.accept()
                    Log.d(TAG, "accept remote socket: $mRemoteSocket")
                }
            } catch (e: Exception) {
                Log.w(TAG, "run: Error.", e)
            }
            Log.d(TAG, "run: io end.")
        }
    }

    override suspend fun stop() {
        try {
            mServerSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "stop: Error", e)
        }
    }

    override suspend fun <T> takeRemoteSocket(block: suspend Socket.() -> T): T? {
        return mRemoteSocket?.block()
    }

    override suspend fun <T> sendData(data: T) {
        slice(data, type = if (data is File) 0 else 1)
            .map { it.packet() }
            .collect {
                takeRemoteSocket {
                    send(it)
                }
            }
    }

    override suspend fun receiveDataFlow(): Flow<Any> {
        return takeRemoteSocket {
            receive()
                .map { it.unpack() }
                .merge()
        } ?: emptyFlow()
    }

}

internal class SocketClient(
    private val address: InetAddress,
    private val post: Int
) : SocketInterface, TransferManager,
    DataSliceProcess by DataSliceUtil,
    DataFrameProcess by DataFrameUtil,
    DataTransfer<Socket> by SocketDataTransfer {

    companion object {
        private const val TAG: String = "SocketClient"
    }

    private var mRemoteSocket: Socket? = null

    override suspend fun run() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "run: io start.")
            try {
                while (mRemoteSocket == null) {
                    Socket(address, post).let {
                        Log.d(TAG, "run: $it")
                        if (it.isConnected) {
                            mRemoteSocket = it
                            Log.d(TAG, "connect remote socket: $mRemoteSocket")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "run: Error.", e)
            }
            Log.d(TAG, "run: io end.")
        }
    }

    override suspend fun stop() {
        try {
            mRemoteSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "stop: Error", e)
        }
    }

    override suspend fun <T> takeRemoteSocket(block: suspend Socket.() -> T): T? {
        return mRemoteSocket?.block()
    }

    @Volatile
    private var sendCount: Int = 0

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun <T> sendData(data: T) {
        slice(data, type = if (data is File) 0 else 1)
            .map { it.packet() }
            .collect {
                takeRemoteSocket {
                    send(it)
                }
            }
    }

    override suspend fun receiveDataFlow(): Flow<Any> {
        return takeRemoteSocket {
            receive()
                .map { it.unpack() }
                .merge()
        } ?: emptyFlow()
    }
}