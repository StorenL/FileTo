package com.storen.fileto.transfer

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform
import java.io.File
import java.io.OutputStream
import java.util.UUID

internal const val TRANSFER_DATA_FRAME_SIZE = 1024
internal const val TRANSFER_FRAME_HEADER_SIZE = 2

interface DataTransfer<T> {
    suspend fun T.send(byteArray: ByteArray): Boolean
    suspend fun T.receive(): Flow<ByteArray>
}

interface DataFrameProcess {
    suspend fun ByteArray.packet(isLast: Boolean): ByteArray
    suspend fun ByteArray.unpack(): Pair<ByteArray, Boolean>
}

object DataFrameUtil : DataFrameProcess {

    private const val TAG: String = "DataFrameUtil"

    override suspend fun ByteArray.packet(isLast: Boolean): ByteArray {
        // 构建头部
        val payloadSize = this.size
        val header = payloadSize shl 1 or (if (isLast) 0b1 else 0b0)
        val headerBytes = ByteArray(TRANSFER_FRAME_HEADER_SIZE)
        Log.d(TAG, "buildHeader: header = ${header.toString(2)}")
        headerBytes[0] = (header shr TRANSFER_FRAME_HEADER_SIZE).toByte()
        headerBytes[1] = header.toByte()
        Log.d(TAG, "buildHeader: ${this[0].toString(2)} ${this[1].toString(2)}")
        // 组装
        return ByteArray(TRANSFER_FRAME_HEADER_SIZE + payloadSize).apply {
            headerBytes.copyInto(this, 0, TRANSFER_FRAME_HEADER_SIZE)
            this@packet.copyInto(this, TRANSFER_FRAME_HEADER_SIZE)
        }
    }

    override suspend fun ByteArray.unpack(): Pair<ByteArray, Boolean> {
        // 解析头部
        val header = this[0].toInt() shl 8 or this[1].toInt()
        Log.d(TAG, "unpackData: header = ${header.toString(2)}")
        val payloadSize = header shr 1
        val isLast = header and 0x01 == 1
        Log.d(TAG, "unpackData: payloadSize=$payloadSize isLast=$isLast")
        // 截取原始数据
        val bytes = sliceArray(
            TRANSFER_FRAME_HEADER_SIZE until TRANSFER_FRAME_HEADER_SIZE + payloadSize
        )
        return Pair(bytes, isLast)
    }
}

interface DataSliceProcess {
    suspend fun <T> slice(source: T): Flow<Pair<ByteArray, Boolean>>
    suspend fun Flow<Pair<ByteArray, Boolean>>.collectToFile(): Flow<File>
}

object DataSliceUtil : DataSliceProcess {

    private const val TAG: String = "DataSliceUtil"

    private var fileOutStream: OutputStream? = null

    private val tempFile: File get() =
        File(Environment.getDownloadCacheDirectory().absolutePath + "file_transfer.temp")

    private fun createNewFile(): File =
        File(Environment.getDownloadCacheDirectory().absolutePath + "${UUID.randomUUID()}.file")

    override suspend fun <T> slice(source: T): Flow<Pair<ByteArray, Boolean>> {
        when (source) {
            is ByteArray ->
                return source.slice(TRANSFER_DATA_FRAME_SIZE - TRANSFER_FRAME_HEADER_SIZE)

            is File ->
                return source.slice(TRANSFER_DATA_FRAME_SIZE - TRANSFER_FRAME_HEADER_SIZE)
        }
        return emptyFlow()
    }

    override suspend fun Flow<Pair<ByteArray, Boolean>>.collectToFile(): Flow<File> {
        return transform { it ->
            if (fileOutStream == null) {
                fileOutStream = tempFile.outputStream()
            }
            fileOutStream?.write(it.first)
            val isLast = it.second
            if (isLast) {
                fileOutStream?.close()
                val file: File = createNewFile()
                tempFile.renameTo(file)
                fileOutStream = null
                Log.d(TAG, "collectToFile: $file")
                emit(file)
            }
        }
    }

    private fun ByteArray.slice(windowSize: Int): Flow<Pair<ByteArray, Boolean>> {
        return callbackFlow {
            indices.windowed(windowSize, windowSize).let {
                it.forEachIndexed { index, ints ->
                    val bytes = sliceArray(ints)
                    if (!trySend(Pair(bytes, index == it.size - 1)).isSuccess) {
                        Log.w(TAG, "sliceBytes: trySend failed.")
                        return@forEachIndexed
                    }
                }
            }
            awaitClose {
                Log.w(TAG, "sliceBytes: awaitClose.")
            }
        }
    }

    private fun File.slice(bufferSize: Int): Flow<Pair<ByteArray, Boolean>> {
        return callbackFlow {
            var buffer = ByteArray(bufferSize)
            val inputStream = inputStream()
            val fileLength = length()
            var readCount: Long = 0
            inputStream.use {
                while (inputStream.buffered(bufferSize).read(buffer)
                        .also { readCount += it } != -1
                ) {
                    if (!trySend(Pair(buffer, readCount >= fileLength)).isSuccess) {
                        Log.w(TAG, "sliceFile: trySend failed.")
                        break
                    }
                    buffer = ByteArray(bufferSize)
                }
            }
            awaitClose {
                try {
                    Log.w(TAG, "slice: sliceFile awaitClose.")
                    inputStream.close()
                } catch (e: Exception) {
                    Log.w(TAG, "slice: sliceFile awaitClose failed.", e)
                }
            }

        }
    }

}

interface TransferManager {
    suspend fun <T> sendData(data: T)
    suspend fun receiveDataFlow(): Flow<File>
}


