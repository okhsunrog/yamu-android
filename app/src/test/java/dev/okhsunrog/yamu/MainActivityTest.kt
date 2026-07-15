package dev.okhsunrog.yamu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityTest {
    @Test
    fun extractsCurrentUuidPlaylistLink() {
        val link =
            "https://music.yandex.ru/playlists/" +
                "fa1b8d08-71c7-3ed8-9c58-8eebbdccdf7f" +
                "?utm_source=web&utm_medium=copy_link"

        assertEquals(link, MainActivity.extractResourceLink("Послушай: $link"))
    }

    @Test
    fun retainsOwnerKindPlaylistLinks() {
        val link = "https://music.yandex.ru/users/example/playlists/42?utm_source=web"

        assertEquals(link, MainActivity.extractResourceLink(link))
    }

    @Test
    fun rejectsMalformedPlaylistUuid() {
        assertNull(
            MainActivity.extractResourceLink(
                "https://music.yandex.ru/playlists/not-a-uuid?utm_source=web",
            ),
        )
    }
}
