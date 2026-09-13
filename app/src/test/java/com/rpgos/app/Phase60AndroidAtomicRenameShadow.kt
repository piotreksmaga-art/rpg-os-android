package com.rpgos.app

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Android's AtomicFile.rename uses File.renameTo with POSIX replacement semantics. On a
 * Windows Robolectric host renameTo instead fails when the destination exists, leaving .new
 * and the old base. Model only that platform primitive with an actual atomic replacement;
 * startWrite/finishWrite/read/rollback and the application store still execute unchanged.
 */
@Implements(AtomicFile::class)
class Phase60AndroidAtomicRenameShadow {
    companion object {
        @JvmStatic @Implementation(minSdk=29)
        fun rename(source:File,target:File) {
            if(target.isDirectory)Files.delete(target.toPath())
            Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
