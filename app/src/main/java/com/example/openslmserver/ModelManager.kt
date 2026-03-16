package com.example.openslmserver

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "OpenSLM-Model"
    }

    /**
     * Finds the first available .gguf file in common locations
     * 1. App's private data folder (filesDir)
     * 2. External Downloads folder
     */
    fun getModelPath(): String? {
        // Check app internal storage
        val internalFile = context.filesDir.listFiles()?.find { it.name.endsWith(".gguf") }
        if (internalFile != null) {
            Log.d(TAG, "Found model in internal storage: ${internalFile.absolutePath}")
            return internalFile.absolutePath
        }

        // Check Downloads folder
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val downloadFile = downloadsDir.listFiles()?.find { it.name.endsWith(".gguf") }
        if (downloadFile != null) {
            Log.d(TAG, "Found model in Downloads: ${downloadFile.absolutePath}")
            return downloadFile.absolutePath
        }

        Log.e(TAG, "No .gguf model file found!")
        return null
    }

    /**
     * List all available GGUF models
     */
    fun listModels(): List<File> {
        val models = mutableListOf<File>()
        context.filesDir.listFiles()?.filter { it.name.endsWith(".gguf") }?.let { models.addAll(it) }
        
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.listFiles()?.filter { it.name.endsWith(".gguf") }?.let { models.addAll(it) }
        
        return models
    }
}
