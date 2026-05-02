package io.legado.app.ui.book

import android.content.Context
import android.content.Intent
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.video.VideoPlayerActivity

object SearchBookOpenHelper {

    fun open(context: Context, book: SearchBook, isVideo: Boolean) {
        val target = if (isVideo) VideoPlayerActivity::class.java else BookInfoActivity::class.java
        context.startActivity(Intent(context, target).apply {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
            putExtra("origin", book.origin)
            putExtra("originName", book.originName)
            if (isVideo) {
                putExtra(VideoPlayerActivity.EXTRA_PREPARE_BOOK_INFO, true)
            }
        })
    }

    fun isVideoResult(book: SearchBook, sourceTypeHint: Int? = null): Boolean {
        return book.type and BookType.video > 0 ||
                sourceTypeHint == BookSourceType.video ||
                appDb.bookSourceDao.getBookSource(book.origin)?.bookSourceType == BookSourceType.video
    }
}
