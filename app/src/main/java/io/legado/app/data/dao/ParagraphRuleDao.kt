package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import kotlinx.coroutines.flow.Flow

@Dao
interface ParagraphRuleDao {

    @Query("SELECT * FROM paragraph_rules ORDER BY sortOrder ASC, id ASC")
    fun flowAll(): Flow<List<ParagraphRule>>

    @Query("SELECT * FROM paragraph_rules ORDER BY sortOrder ASC, id ASC")
    fun all(): List<ParagraphRule>

    @Query("SELECT * FROM paragraph_rules WHERE id = :id")
    fun get(id: Long): ParagraphRule?

    @Query("SELECT MAX(sortOrder) FROM paragraph_rules")
    fun maxOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rule: ParagraphRule): Long

    @Update
    fun update(rule: ParagraphRule)

    @Delete
    fun delete(rule: ParagraphRule)

    @Query("""
        SELECT r.* FROM paragraph_rules r
        INNER JOIN book_paragraph_rules b ON b.ruleId = r.id
        WHERE b.bookUrl = :bookUrl AND b.enabled = 1
        ORDER BY b.sortOrder ASC, r.sortOrder ASC, r.id ASC
    """)
    fun enabledRulesForBook(bookUrl: String): List<ParagraphRule>

    @Query("SELECT * FROM book_paragraph_rules WHERE bookUrl = :bookUrl ORDER BY sortOrder ASC, ruleId ASC")
    fun bookRules(bookUrl: String): List<BookParagraphRule>

    @Query("SELECT ruleId FROM book_paragraph_rules WHERE bookUrl = :bookUrl AND enabled = 1 ORDER BY sortOrder ASC, ruleId ASC")
    fun enabledRuleIdsForBook(bookUrl: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBookRule(rule: BookParagraphRule)

    @Update
    fun updateBookRule(rule: BookParagraphRule)

    @Query("DELETE FROM book_paragraph_rules WHERE bookUrl = :bookUrl AND ruleId = :ruleId")
    fun deleteBookRule(bookUrl: String, ruleId: Long)

    @Query("DELETE FROM book_paragraph_rules WHERE ruleId = :ruleId")
    fun deleteBookRulesByRule(ruleId: Long)

    @Query("SELECT * FROM paragraph_rule_vars WHERE ruleId = :ruleId ORDER BY name ASC")
    fun vars(ruleId: Long): List<ParagraphRuleVar>

    @Query("SELECT value FROM paragraph_rule_vars WHERE ruleId = :ruleId AND name = :name")
    fun varValue(ruleId: Long, name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putVar(value: ParagraphRuleVar)

    @Query("DELETE FROM paragraph_rule_vars WHERE ruleId = :ruleId")
    fun deleteVars(ruleId: Long)

    fun deleteWithRelations(rule: ParagraphRule) {
        deleteBookRulesByRule(rule.id)
        deleteVars(rule.id)
        delete(rule)
    }
}
