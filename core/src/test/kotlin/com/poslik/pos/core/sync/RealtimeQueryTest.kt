package com.poslik.pos.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * La construction de la chaine de requete est partagee par les deux passerelles
 * (JVM et Android). Une erreur d'encodage ici produit un 400 Firebase
 * `orderBy must be a valid JSON encoded path` - et, cote Android, faisait planter
 * l'application quand la synchronisation etait lancee a la main.
 */
class RealtimeQueryTest {

    @Test
    fun `a pull query json encodes orderBy and percent encodes the quotes`() {
        val query = RealtimeQuery(orderBy = "updatedAt", startAt = 10, limitToFirst = 500)
        assertEquals("orderBy=%22updatedAt%22&startAt=10&limitToFirst=500", query.toQueryString())
    }

    @Test
    fun `no stray character leaks into the query string`() {
        val encoded = RealtimeQuery(orderBy = "updatedAt", startAt = 42).toQueryString()
        assertFalse(encoded.contains('$'), encoded)
        assertFalse(encoded.contains('"'), encoded)
        assertTrue(encoded.startsWith("orderBy=%22updatedAt%22"), encoded)
    }

    @Test
    fun `every parameter is encoded`() {
        val encoded = RealtimeQuery(
            orderBy = "createdAt",
            startAt = 1,
            endAt = 2,
            limitToFirst = 3,
            limitToLast = 4,
            shallow = true,
        ).toQueryString()
        assertEquals(
            "orderBy=%22createdAt%22&startAt=1&endAt=2&limitToFirst=3&limitToLast=4&shallow=true",
            encoded,
        )
    }

    @Test
    fun `an empty query produces an empty string`() {
        assertEquals("", RealtimeQuery().toQueryString())
        assertEquals("shallow=true", RealtimeQuery(shallow = true).toQueryString())
    }
}
