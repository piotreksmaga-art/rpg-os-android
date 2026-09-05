package com.rpgos.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SemanticSidecarConstructorLockContractTest {
    private fun source():String{
        val direct=File("src/main/java/com/rpgos/app/SemanticSidecarIndex.kt")
        val fromRoot=File("app/src/main/java/com/rpgos/app/SemanticSidecarIndex.kt")
        return (if(direct.isFile)direct else fromRoot).readText()
    }

    @Test fun vectorHandleIsOpenedAndRegisteredInsideTheGlobalLifecycleLock(){
        val source=source()
        assertTrue(source.contains("private lateinit var vectors:RandomAccessFile"))
        val constructor=source.substringAfter("init{synchronized(PROCESS_VECTOR_IO_LOCK){")
            .substringBefore("@Synchronized override fun upsertBatch")
        val open=constructor.indexOf("vectors=RandomAccessFile(vectorsFile,\"rw\")")
        val register=constructor.indexOf("OPEN_INSTANCE_COUNTS[directoryKey]=")
        assertTrue(open>=0)
        assertTrue(register>open)
        assertTrue(constructor.contains("if(::vectors.isInitialized)runCatching{vectors.close()}"))
        assertTrue(constructor.contains("if(registered){"))
    }
}
