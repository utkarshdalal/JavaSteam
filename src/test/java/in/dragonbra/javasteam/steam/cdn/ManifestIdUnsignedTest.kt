package `in`.dragonbra.javasteam.steam.cdn

import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.util.stream.MemoryStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies that manifest IDs exceeding Long.MAX_VALUE (i.e. large unsigned 64-bit values)
 * are handled correctly throughout the pipeline: binary KeyValue parsing, CDN URL construction,
 * and file path generation.
 *
 * Background: Steam manifest GIDs are uint64. Java/Kotlin represent them as signed Long,
 * which goes negative for values > 2^63. These must be converted via .toULong() before
 * rendering in URLs, file paths, or user-facing strings.
 */
class ManifestIdUnsignedTest {

    companion object {
        // Real manifest GID from Half-Life 2 depot 3701 that triggers the bug
        const val LARGE_MANIFEST_SIGNED: Long = -7274049128972107398L
        const val LARGE_MANIFEST_UNSIGNED_STR = "11172694944737444218"

        /**
         * Create a [Client] backed by an OkHttp interceptor that captures the request URL
         * and returns a 404. Returns (client, capturedUrl ref).
         */
        private fun clientCapturingUrl(): Pair<Client, AtomicReference<String>> {
            val capturedUrl = AtomicReference<String>()
            val httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedUrl.set(chain.request().url.toString())
                    Response.Builder()
                        .code(404)
                        .protocol(Protocol.HTTP_1_1)
                        .message("Not Found")
                        .request(chain.request())
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                }
                .build()
            val configuration = SteamConfiguration.create { it.withHttpClient(httpClient) }
            val steam = SteamClient(configuration)
            return Client(steam) to capturedUrl
        }

        /**
         * Build binary KeyValue: NONE("root") containing UINT64(name=value), then END, END.
         */
        private fun buildBinaryKvUint64(name: String, value: Long): ByteArray {
            val baos = ByteArrayOutputStream()
            // outer NONE node
            baos.write(0) // NONE type
            baos.write("root".toByteArray(Charsets.UTF_8))
            baos.write(0) // null terminator
            // child UINT64 node
            baos.write(7) // UINT64 type
            baos.write(name.toByteArray(Charsets.UTF_8))
            baos.write(0) // null terminator
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(value)
            baos.write(buf.array())
            baos.write(8) // END (close root's children)
            baos.write(8) // END (close outer)
            return baos.toByteArray()
        }
    }

    // --- KeyValue binary parsing: UINT64 → string → asUnsignedLong() ---

    @ParameterizedTest
    @CsvSource(
        "-7274049128972107398, 11172694944737444218",
        "-9223372036854775808, 9223372036854775808",
        "-1, 18446744073709551615",
        "4567890123456789, 4567890123456789",
        "0, 0",
    )
    fun `KeyValue binary UINT64 roundtrips through asUnsignedLong`(
        signedBits: Long,
        expectedUnsigned: String,
    ) {
        val bytes = buildBinaryKvUint64("gid", signedBits)
        val ms = MemoryStream(bytes)

        val kv = KeyValue()
        val success = kv.tryReadAsBinary(ms)
        assertTrue(success, "KeyValue binary parse should succeed")
        assertEquals("root", kv.name, "root node name should be 'root'")

        val gidNode = kv["gid"]
        assertNotNull(gidNode.value, "gid value should not be null")

        val parsed = gidNode.asUnsignedLong()
        assertEquals(
            expectedUnsigned.toULong(),
            parsed,
            "UINT64 $signedBits should parse as unsigned $expectedUnsigned, " +
                "but stored string was '${gidNode.value}'"
        )
    }

    // --- CDN URL construction: manifestId renders as unsigned in the path ---

    @ParameterizedTest
    @CsvSource(
        "-7274049128972107398, 11172694944737444218",
        "-9223372036854775808, 9223372036854775808",
        "-1, 18446744073709551615",
        "4567890123456789, 4567890123456789",
    )
    fun `CDN manifest URL uses unsigned representation`(
        manifestIdSigned: Long,
        expectedUnsigned: String,
    ) {
        val (client, capturedUrl) = clientCapturingUrl()

        client.use {
            try {
                it.downloadManifestFuture(
                    /* depotId = */ 3701,
                    /* manifestId = */ manifestIdSigned,
                    /* manifestRequestCode = */ 12345L,
                    /* server = */ Server.fromHostAndPort("localhost", 80),
                ).get()
            } catch (_: Exception) {
                // expected 404
            }
        }

        val url = capturedUrl.get()
        assertNotNull(url, "URL should have been captured")

        val expectedPath = "/depot/3701/manifest/$expectedUnsigned/5/12345"
        assertTrue(
            url!!.contains(expectedPath),
            "URL should contain '$expectedPath' but was: $url"
        )

        if (manifestIdSigned < 0) {
            assertFalse(
                url.contains("/-"),
                "URL must not contain negative path segment but was: $url"
            )
        }
    }

    @Test
    fun `CDN manifest URL without request code uses unsigned representation`() {
        val (client, capturedUrl) = clientCapturingUrl()

        client.use {
            try {
                it.downloadManifestFuture(
                    /* depotId = */ 3701,
                    /* manifestId = */ LARGE_MANIFEST_SIGNED,
                    /* manifestRequestCode = */ 0L,
                    /* server = */ Server.fromHostAndPort("localhost", 80),
                ).get()
            } catch (_: Exception) {
                // expected 404
            }
        }

        val url = capturedUrl.get()
        assertNotNull(url)
        assertTrue(
            url!!.contains("/depot/3701/manifest/$LARGE_MANIFEST_UNSIGNED_STR/5"),
            "URL without request code should use unsigned manifest ID but was: $url"
        )
        assertFalse(url.contains("/-"), "URL must not contain negative path segment")
    }
}
