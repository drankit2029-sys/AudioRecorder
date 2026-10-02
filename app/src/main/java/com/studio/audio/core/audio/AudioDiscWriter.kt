package com.studio.audio.core.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AudioDiskWriter {

    private var outputStream: BufferedOutputStream? = null
    private var activeFile: File? = null

    @Synchronized
    fun start(destinationFile: File) {
        stop()
        activeFile = destinationFile
        outputStream = BufferedOutputStream(FileOutputStream(destinationFile), 64 * 1024)
    }

    @Synchronized
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        try {
            outputStream?.write(buffer, offset, length)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun stop(): File? {
        val file = activeFile
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            outputStream = null
            activeFile = null
        }
        return file
    }
}
