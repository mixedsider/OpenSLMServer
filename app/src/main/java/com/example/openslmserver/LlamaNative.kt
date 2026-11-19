package com.example.openslmserver

class LlamaNative {
    companion object {
        init {
            System.loadLibrary("openslmserver")
        }
    }

    external fun initContext(modelPath: ByteArray, contextTokens: Int): Long
    external fun completion(
        contextPtr: Long,
        roles: Array<String>,
        messages: Array<ByteArray>,
        maxTokens: Int
    ): ByteArray
    external fun releaseContext(contextPtr: Long)
}
