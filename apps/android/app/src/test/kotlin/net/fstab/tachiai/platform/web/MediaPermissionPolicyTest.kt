package net.fstab.tachiai.platform.web

import java.net.URI
import net.fstab.tachiai.provider.abema.AbemaAdapter
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MediaPermissionPolicyTest {
    private val protectedMedia = "protected-media"

    @Test
    fun `grants only a lone protected-media request from an exact provider origin`() {
        assertArrayEquals(
            arrayOf(protectedMedia),
            MediaPermissionPolicy.grantProtectedMediaOnly(
                adapter = AbemaAdapter,
                origin = URI("https://abema.tv"),
                requestedResources = arrayOf(protectedMedia),
                protectedMediaResource = protectedMedia,
            ),
        )
    }

    @Test
    fun `denies unknown origins and combined resource requests`() {
        assertArrayEquals(
            emptyArray<String>(),
            MediaPermissionPolicy.grantProtectedMediaOnly(
                adapter = AbemaAdapter,
                origin = URI("https://abema.tv.evil.example"),
                requestedResources = arrayOf(protectedMedia),
                protectedMediaResource = protectedMedia,
            ),
        )
        assertArrayEquals(
            emptyArray<String>(),
            MediaPermissionPolicy.grantProtectedMediaOnly(
                adapter = AbemaAdapter,
                origin = URI("https://abema.tv"),
                requestedResources = arrayOf(protectedMedia, "camera"),
                protectedMediaResource = protectedMedia,
            ),
        )
    }
}
