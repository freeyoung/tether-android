package io.github.freeyoung.tether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The events as go/bridge.go writes them.
class ModelTest {
    private fun decode(line: String) = Tether.json.decodeFromString(Event.serializer(), line)

    @Test
    fun state() {
        val e = decode(
            """{"event":"state","snapshot":{"fingerprint":"ab","listening":true,
            "peers":[{"fingerprint":"cd","name":"Mac","kind":"computer","connected":true},
                     {"fingerprint":"ef","name":"iPhone","kind":"phone","connected":false}],
            "pending":[],"unpaired":[{"name":"linux-pc","fingerprint":"12","hosts":["192.0.2.9"],"port":5134,"isComputer":true}],
            "outgoing":[],"sending":[{"filename":"a.txt","peer":"Mac","fraction":0.5,"queued":2}]}}""",
        )
        val s = e.snapshot!!
        assertEquals(listOf("Mac"), s.connected.map { it.name })
        assertTrue(s.peers[1].isPhone)
        assertEquals(5134, s.unpaired[0].port)
        assertEquals(2, s.sending[0].queued)
    }

    @Test
    fun clipboard() {
        assertEquals("héllo 👋", decode("""{"event":"clipboard","text":"héllo 👋"}""").text)
        val image = decode("""{"event":"clipboard","png":"/cache/clipboard/clipboard-3.png"}""")
        assertNull(image.text)
        assertEquals("/cache/clipboard/clipboard-3.png", image.png)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val e = decode("""{"event":"file_sent","name":"a","peer":"Mac","ok":true,"reason":"","later":1}""")
        assertTrue(e.ok)
    }
}
