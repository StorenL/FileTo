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
import kotlin.math.min

internal const val TRANSFER_DATA_FRAME_SIZE = 1024
internal const val TRANSFER_FRAME_HEADER_SIZE = 3

internal val TRANSFER_DOWNLOADS_DIR: File get() =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

interface DataTransfer<T> {
    suspend fun T.send(byteArray: ByteArray): Boolean
    suspend fun T.receive(): Flow<ByteArray>
}

interface DataFrameProcess {
    suspend fun DataFrame.packet(): ByteArray
    suspend fun ByteArray.unpack(): DataFrame
}

data class DataFrame(
    val data: ByteArray,
    val type: Int,
    val isLast: Boolean
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DataFrame

        if (!data.contentEquals(other.data)) return false
        if (type != other.type) return false
        if (isLast != other.isLast) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + type
        result = 31 * result + isLast.hashCode()
        return result
    }
}

object DataFrameUtil : DataFrameProcess {

    private const val TAG: String = "DataFrameUtil"

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun DataFrame.packet(): ByteArray {
        // 构建头部
        val payloadSize = this.data.size
        val headerBytes = ByteArray(TRANSFER_FRAME_HEADER_SIZE)
        headerBytes[0] = (type and 0xFF).toByte()
        headerBytes[1] = ((payloadSize shr 7) and 0xFF).toByte()
        headerBytes[2] = ((payloadSize shl 1 or (if (isLast) 0b1 else 0b0)) and 0xFF).toByte()
        // 组装
        val apply = ByteArray(TRANSFER_FRAME_HEADER_SIZE + payloadSize).apply {
            this[0] = headerBytes[0]
            this[1] = headerBytes[1]
            this[2] = headerBytes[2]
            this@packet.data.copyInto(this, TRANSFER_FRAME_HEADER_SIZE)
        }
        return apply
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun ByteArray.unpack(): DataFrame {
        if (size <= TRANSFER_FRAME_HEADER_SIZE) {
            throw RuntimeException("DATA ERROR.")
        }
        // 解析头部
        val type: Int = this[0].toUByte().toInt()
        val payloadSize: Int = (((this[1].toUByte().toInt() shl 8) or this[2].toUByte().toInt()) shr 1)
        val isLast: Boolean = (this[2].toUByte().toInt() and 0x1) == 1

        Log.d(TAG, "unpackData: type=$type payloadSize=$payloadSize isLast=$isLast ${this[2].toUByte().toHexString()}")
        // 截取原始数据
        val bytes = sliceArray(
            TRANSFER_FRAME_HEADER_SIZE until TRANSFER_FRAME_HEADER_SIZE + payloadSize
        )
        return DataFrame(bytes, type, isLast)
    }
}

interface DataSliceProcess {
    suspend fun <T> slice(source: T, type: Int = 1): Flow<DataFrame>
    suspend fun Flow<DataFrame>.merge(): Flow<Any>
}

object DataSliceUtil : DataSliceProcess {

    private const val TAG: String = "DataSliceUtil"

    private var cachedBytes: MutableList<Byte> = mutableListOf()

    private var fileOutStream: OutputStream? = null

    private val tempFile: File get() =
        File(TRANSFER_DOWNLOADS_DIR.absolutePath  + File.separator + "file_transfer.temp")

    private fun createNewFile(): File =
        File(TRANSFER_DOWNLOADS_DIR.absolutePath + File.separator + "${UUID.randomUUID()}.file")

    override suspend fun <T> slice(source: T, type: Int): Flow<DataFrame> {
        when (source) {
            is ByteArray ->
                return source.slice(TRANSFER_DATA_FRAME_SIZE - TRANSFER_FRAME_HEADER_SIZE, type)

            is File ->
                return source.slice(TRANSFER_DATA_FRAME_SIZE - TRANSFER_FRAME_HEADER_SIZE, type)
        }
        return emptyFlow()
    }

    override suspend fun Flow<DataFrame>.merge(): Flow<Any> {
        return transform {
            if (it.type == 0) {
                if (fileOutStream == null) {
                    Log.d(TAG, "merge: ${tempFile.absolutePath}")
                    fileOutStream = tempFile.outputStream()
                }
                fileOutStream?.write(it.data)
                if (it.isLast) {
                    fileOutStream?.close()
                    val file: File = createNewFile()
                    tempFile.renameTo(file)
                    fileOutStream = null
                    Log.d(TAG, "collectToFile: $file")
                    emit(file)
                }
            } else {
                cachedBytes.addAll(it.data.toList())
                if (it.isLast) {
                    emit(cachedBytes.toByteArray())
                    cachedBytes.clear()
                }
            }
        }
    }

    private fun ByteArray.slice(windowSize: Int, type: Int): Flow<DataFrame> {
        return callbackFlow {
            val wSize = min(windowSize, size)
            indices.windowed(wSize, wSize).let {
                it.forEachIndexed { index, ints ->
                    val bytes = sliceArray(ints)
                    send(DataFrame(bytes, type,index == it.size - 1))
                }
            }
            awaitClose {
                Log.w(TAG, "sliceBytes: awaitClose.")
            }
        }
    }

    private fun File.slice(bufferSize: Int, type: Int): Flow<DataFrame> {
        return callbackFlow {
            var buffer = ByteArray(bufferSize)
            val inputStream = inputStream()
            val fileLength = length()
            var readCount: Long = 0
            inputStream.use {
                while (inputStream.buffered(bufferSize).read(buffer)
                        .also { readCount += it } != -1
                ) {
                    send(DataFrame(buffer, type, readCount >= fileLength))
                    Log.d(TAG, "slice: success $readCount $fileLength")
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
    suspend fun receiveDataFlow(): Flow<Any>
}


