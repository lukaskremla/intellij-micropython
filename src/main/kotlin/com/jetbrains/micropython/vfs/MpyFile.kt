package com.jetbrains.micropython.vfs

import com.intellij.openapi.vfs.VirtualFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class MpyFile(fileSystem: MpyFileSystem, parent: MpyDir?, name: String) : MpyFileNode(fileSystem, parent, name) {
    override fun isDirectory(): Boolean = false
    override fun getChildren(): Array<out VirtualFile?>? = null

    internal var content: ByteArray? = null

    override fun contentsToByteArray(): ByteArray = content?.copyOf() ?: byteArrayOf()
    override fun getLength(): Long = content?.size?.toLong() ?: -1//todo load
    override fun getOutputStream(
        requestor: Any?,
        newModificationStamp: Long,
        newTimeStamp: Long
    ): OutputStream {

        return object :ByteArrayOutputStream(){
            override fun close() {
                super.close()
//                fileSystem.fire
                content = this.toByteArray()
                //fileSystem.fire
            }
        }
    }

    override fun getInputStream(): InputStream = ByteArrayInputStream(content)
}