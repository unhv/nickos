package com.nbaltas.nickos.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPolicyTest {
    @Test fun `needs admin only when neither owner nor admin`() {
        assertTrue(AdminPolicy.needsAdmin(isOwner = false, isAdminActive = false))
        assertFalse(AdminPolicy.needsAdmin(isOwner = true, isAdminActive = false))
        assertFalse(AdminPolicy.needsAdmin(isOwner = false, isAdminActive = true))
        assertFalse(AdminPolicy.needsAdmin(isOwner = true, isAdminActive = true))
    }

    @Test fun `can lock screen mirrors owner or admin`() {
        assertTrue(AdminPolicy.canLockScreen(true, false))
        assertTrue(AdminPolicy.canLockScreen(false, true))
        assertFalse(AdminPolicy.canLockScreen(false, false))
    }

    @Test fun `prompts in the parent menu on a node that needs admin`() {
        assertTrue(AdminPolicy.shouldPromptHere(true, true, false, false, false))
    }

    @Test fun `never prompts outside the parent menu or off a node`() {
        assertFalse(AdminPolicy.shouldPromptHere(true, false, false, false, false))
        assertFalse(AdminPolicy.shouldPromptHere(false, true, false, false, false))
    }

    @Test fun `skips when owner, admin, or already asked`() {
        assertFalse(AdminPolicy.shouldPromptHere(true, true, true, false, false))
        assertFalse(AdminPolicy.shouldPromptHere(true, true, false, true, false))
        assertFalse(AdminPolicy.shouldPromptHere(true, true, false, false, true))
    }

    @Test fun `explanation has no em or en dash`() {
        assertTrue(AdminPolicy.EXPLANATION.isNotBlank())
        assertFalse(0x2014.toChar() in AdminPolicy.EXPLANATION)
        assertFalse(0x2013.toChar() in AdminPolicy.EXPLANATION)
    }

    @Test fun `new source and test files have no em or en dash`() {
        for (f in listOf("src/main/java/com/nbaltas/nickos/node/AdminPolicy.kt", "src/test/java/com/nbaltas/nickos/node/AdminPolicyTest.kt")) {
            val t = java.io.File(f).readText()
            assertFalse(f, 0x2014.toChar() in t || 0x2013.toChar() in t)
        }
    }
}
