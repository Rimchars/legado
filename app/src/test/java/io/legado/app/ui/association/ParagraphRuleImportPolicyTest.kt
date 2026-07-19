package io.legado.app.ui.association

import io.legado.app.data.entities.ParagraphRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphRuleImportPolicyTest {

    @Test
    fun smallRuleRemainsEditable() {
        assertTrue(ParagraphRuleImportPolicy.isEditable(ParagraphRule(name = "small", script = "return true")))
    }

    @Test
    fun oversizedRuleUsesReadOnlyMode() {
        val rule = ParagraphRule(script = "x".repeat(ParagraphRuleImportPolicy.MAX_EDITABLE_BYTES.toInt() + 1))

        assertFalse(ParagraphRuleImportPolicy.isEditable(rule))
    }

    @Test
    fun utf8BudgetCountsMultibyteCharacters() {
        assertTrue(ParagraphRuleImportPolicy.utf8ByteCount("书") == 3L)
    }
}
