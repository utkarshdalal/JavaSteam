package `in`.dragonbra.javasteam.depotdownloader

import `in`.dragonbra.javasteam.types.DepotManifest
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Verifies that [Util] save/load manifest file paths use the unsigned
 * representation of manifestGID, not the signed Java Long.
 */
class UtilManifestFilePathTest {

    companion object {
        const val LARGE_MANIFEST_SIGNED: Long = -7274049128972107398L
        const val LARGE_MANIFEST_UNSIGNED_STR = "11172694944737444218"
        const val DEPOT_ID = 3701
    }

    @Test
    fun `saveManifestToFile uses unsigned manifest ID in filename`(@TempDir tempDir: File) {
        val dir = tempDir.toOkioPath()

        val manifest = DepotManifest()
        manifest.depotID = DEPOT_ID
        manifest.manifestGID = LARGE_MANIFEST_SIGNED

        Util.saveManifestToFile(dir, manifest)

        val expectedFilename = "${DEPOT_ID}_$LARGE_MANIFEST_UNSIGNED_STR.manifest"
        val savedFile = tempDir.resolve(expectedFilename)
        assertTrue(
            savedFile.exists(),
            "Manifest should be saved as '$expectedFilename' but files were: ${tempDir.listFiles()?.map { it.name }}"
        )

        val negativeFilename = "${DEPOT_ID}_$LARGE_MANIFEST_SIGNED.manifest"
        assertFalse(
            tempDir.resolve(negativeFilename).exists(),
            "Manifest filename must not use negative signed representation"
        )
    }

    @Test
    fun `loadManifestFromFile finds file by unsigned manifest ID`(@TempDir tempDir: File) {
        val dir = tempDir.toOkioPath()

        val manifest = DepotManifest()
        manifest.depotID = DEPOT_ID
        manifest.manifestGID = LARGE_MANIFEST_SIGNED

        Util.saveManifestToFile(dir, manifest)

        val loaded = Util.loadManifestFromFile(dir, DEPOT_ID, LARGE_MANIFEST_SIGNED, true)
        assertNotNull(loaded, "Should load manifest saved with unsigned filename using signed manifestId")
        assertEquals(DEPOT_ID, loaded!!.depotID)
        assertEquals(LARGE_MANIFEST_SIGNED, loaded.manifestGID)
    }
}
