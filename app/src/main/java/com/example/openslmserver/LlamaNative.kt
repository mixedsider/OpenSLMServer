package com.example.openslmserver

class LlamaNative {
    companion object {
        init {
            System.loadLibrary("openslmserver")
        }
    }

    // Native methods to be implemented in C++
    external fun initContext(modelPath: String, useGpu: Boolean): Long
    external fun completion(contextPtr: Long, prompt: String): String
    external fun releaseContext(contextPtr: Long)
}
