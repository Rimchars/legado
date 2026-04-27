package io.legado.app.ui.book.info.edit

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook

class BookInfoEditViewModel(application: Application) : BaseViewModel(application) {
    var book: Book? = null
    val bookData = MutableLiveData<Book>()

    fun loadBook(bookUrl: String) {
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                bookData.postValue(it)
            }
        }
    }

    fun saveBook(book: Book, success: (() -> Unit)?) {
        execute {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.book = book
            }
            appDb.bookDao.update(book)
        }.onSuccess {
            success?.invoke()
        }.onError {
            if (it is SQLiteConstraintException) {
                AppLog.put("书籍信息保存失败，存在相同书名作者书籍\n$it", it, true)
            } else {
                AppLog.put("书籍信息保存失败\n$it", it, true)
            }
        }
    }

    fun loadTagCandidates(book: Book, success: (List<String>) -> Unit) {
        execute {
            val configured = AppConfig.bookshelfGroupTags
                .filterKeys { groupId ->
                    groupId == BookGroup.IdAll || book.group and groupId != 0L
                }
                .values
                .flatten()
            val existing = appDb.bookDao.all
                .filter { it.group and book.group != 0L || it.bookUrl == book.bookUrl }
                .flatMap { BookTagHelper.parse(it.customTag) }
            (configured + existing)
                .distinct()
                .sorted()
        }.onSuccess {
            success(it)
        }
    }
}
