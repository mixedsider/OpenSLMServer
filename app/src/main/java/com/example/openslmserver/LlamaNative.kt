package com.example.openslmserver

class LlamaNative {
    companion object {
        init {
            System.loadLibrary("openslmserver")
        }
    }

    external fun initContext(modelPath: ByteArray, contextTokens: Int): Long
    external fun streamCompletion(
        contextPtr: Long,
        roles: Array<String>,
        messages: Array<ByteArray>,
        maxTokens: Int,
        output: TokenOutput
    )
    external fun releaseContext(contextPtr: Long)

    fun interface TokenOutput {
        fun onBytes(bytes: ByteArray): Boolean
    }
}
