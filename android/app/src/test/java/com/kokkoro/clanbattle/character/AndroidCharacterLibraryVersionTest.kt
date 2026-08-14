package com.kokkoro.clanbattle.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidCharacterLibraryVersionTest {
    @Test fun databaseCnSourcesAreOrderedNumerically() {
        assertEquals(
            1,
            AndroidCharacterLibrary.compareDatabaseSource(
                "database-cn-202608140101",
                "database-cn-202607312107"
            )
        )
        assertEquals(
            -1,
            AndroidCharacterLibrary.compareDatabaseSource(
                "database-cn-202607312107",
                "database-cn-202608140101"
            )
        )
    }

    @Test fun identicalSourcesAreEqual() {
        assertEquals(
            0,
            AndroidCharacterLibrary.compareDatabaseSource(
                "database-cn-202607312107",
                "database-cn-202607312107"
            )
        )
    }

    @Test fun unknownDifferentSourcesAreNotArtificiallyOrdered() {
        assertNull(AndroidCharacterLibrary.compareDatabaseSource("local:a.db", "local:b.db"))
    }
}
