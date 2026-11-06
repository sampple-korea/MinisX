package com.minisx.app.control

import com.minisx.app.control.ContactMatch.Contact
import com.minisx.app.control.ContactMatch.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchTest {

    private val book = listOf(
        Contact("엄마", "010-1111-2222"),
        Contact("엄마친구 김이모", "010-3333-4444"),
        Contact("김철수", "010-5555-6666"),
        Contact("김철수", "+82 10-5555-6666"),
        Contact("김영희", "010-7777-8888"),
        Contact("Kim Jin", "010-9999-0000"),
    )

    @Test fun `an exact name wins over longer names containing it`() {
        val p = ContactMatch.choose("엄마", book)
        assertEquals(Pick.One(Contact("엄마", "010-1111-2222")), p)
    }

    @Test fun `the same number listed twice is one person`() {
        val p = ContactMatch.choose("김철수", book)
        assertTrue(p is Pick.One)
        assertEquals(1, ContactMatch.choose("김철수", book).let { if (it is Pick.One) 1 else 0 })
    }

    @Test fun `two different people are never guessed between`() {
        val p = ContactMatch.choose("김", book)
        assertTrue(p is Pick.Many)
        assertEquals(setOf("김철수", "김영희"), (p as Pick.Many).candidates.map { it.name }.toSet())
    }

    @Test fun `pick chooses among several`() {
        val p = ContactMatch.choose("김", book, pick = 2)
        assertTrue(p is Pick.One)
        assertTrue(ContactMatch.choose("김", book, pick = 9) is Pick.Many)
    }

    @Test fun `case and spaces do not matter, no match is none`() {
        assertTrue(ContactMatch.choose("kimjin", book) is Pick.One)
        assertTrue(ContactMatch.choose("KIM JIN", book) is Pick.One)
        assertEquals(Pick.None, ContactMatch.choose("홍길동", book))
        assertEquals(Pick.None, ContactMatch.choose("", book))
    }

    @Test fun `numbers are compared by their digits`() {
        assertEquals("01012345678", ContactMatch.normalizeNumber("010-1234-5678"))
        assertEquals("+821012345678", ContactMatch.normalizeNumber("+82 10 1234 5678"))
    }
}
