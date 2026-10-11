package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaAccountValidationContextTest {
    private val valid = "{\"guestId\":\"fixture-guest\",\"guestBearer\":\"PRIVATE_BEARER\"}"
    @Test fun onlyTwoPrivateFieldsAreProjectedWithTheCapturedOriginalDeadline() {
        val context = decodeAbemaAccountValidationContext(valid, 46_000, 1_001)
        assertEquals("fixture-guest", context.guestId); assertEquals("PRIVATE_BEARER", context.guestBearer)
        assertEquals(46_000L, context.deadlineElapsedMs)
        assertFalse(context.toString().contains("fixture-guest")); assertFalse(context.toString().contains("PRIVATE"))
    }
    @Test fun expiredNegativeOrExtendedDeadlineCannotAdoptTheContext() {
        for ((deadline, now) in listOf(46_000L to 46_000L, 46_001L to 1_000L, 46_000L to -1L,
            Long.MAX_VALUE to 1_000L, -1L to 1_000L)) {
            try { decodeAbemaAccountValidationContext(valid, deadline, now); fail() } catch (error: AbemaAccountException) {
                assertEquals(AbemaAccountFailure.EXPIRED, error.category); assertNull(error.cause)
            }
        }
    }
    @Test fun duplicateUnknownNestedTrailingWrongTypeAndPrivateValueBoundsRefuseClosed() {
        for (body in listOf(valid + "{}", "{\"guestId\":\"guest\",\"guestId\":\"other\",\"guestBearer\":\"PRIVATE\"}",
            "{\"guestId\":\"guest\",\"guest\\u0049d\":\"other\",\"guestBearer\":\"PRIVATE\"}",
            valid.dropLast(1) + ",\"deadlineElapsedMs\":46000}", valid.dropLast(1) + ",\"deviceCode\":\"PRIVATE_CODE\"}",
            "{\"guestId\":\"guest\",\"guestBearer\":{\"secret\":\"PRIVATE\"}}", "{\"guestId\":7,\"guestBearer\":\"PRIVATE\"}",
            "{\"guestId\":\"guest\",\"guestBearer\":\"\"}", "{\"guestId\":\"guest\",\"guestBearer\":\"${"A".repeat(8193)}\"}",
            "{\"guestId\":\"${"A".repeat(257)}\",\"guestBearer\":\"PRIVATE\"}",
            "{\"guestId\":\"guest\",\"guestBearer\":\"PRIVATE\\nSECRET\"}")) {
            try { decodeAbemaAccountValidationContext(body, 46_000, 1_001); fail() } catch (error: AbemaAccountException) {
                assertNull(error.cause); assertFalse(error.toString().contains("PRIVATE"))
            }
        }
    }
    @Test fun maximumEscapedBearerStillFitsThePrivateProjectionBound() {
        val body = "{\"guestId\":\"guest\",\"guestBearer\":\"${"\\\\".repeat(8192)}\"}"
        val context = decodeAbemaAccountValidationContext(body, 46_000, 1_001)
        assertEquals(8192, context.guestBearer.length); assertTrue(context.guestBearer.all { it == '\\' })
    }
}
