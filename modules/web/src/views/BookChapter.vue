<template>
  <div
    class="chapter-wrapper"
    :style="bodyTheme"
    :class="{ night: isNight, day: !isNight }"
    @click="showToolBar = !showToolBar"
  >
    <div class="tool-bar" :style="leftBarTheme" @click.stop>
      <div class="tools">
        <el-popover
          :placement="miniInterface ? 'bottom' : 'right'"
          :width="popupWidth"
          trigger="click"
          :show-arrow="false"
          v-model:visible="popCataVisible"
          popper-class="pop-cata"
        >
          <PopCatalog @getContent="getContent" class="popup" />
          <template #reference>
            <div class="tool-icon" :class="{ 'no-point': false }">
              <div class="iconfont">&#58905;</div>
              <div class="icon-text">目录</div>
            </div>
          </template>
        </el-popover>
        <el-popover
          :placement="miniInterface ? 'bottom' : 'right'"
          :width="popupWidth"
          trigger="click"
          :show-arrow="false"
          v-model:visible="readSettingsVisible"
          popper-class="pop-setting"
        >
          <read-settings class="popup" />
          <template #reference>
            <div class="tool-icon" :class="{ 'no-point': noPoint }">
              <div class="iconfont">&#58971;</div>
              <div class="icon-text">设置</div>
            </div>
          </template>
        </el-popover>
        <div class="tool-icon" @click="toShelf">
          <div class="iconfont">&#58892;</div>
          <div class="icon-text">书架</div>
        </div>
        <div class="tool-icon" :class="{ 'no-point': noPoint }" @click="toTop">
          <div class="iconfont">&#58914;</div>
          <div class="icon-text">顶部</div>
        </div>
        <div
          class="tool-icon"
          :class="{ 'no-point': noPoint }"
          @click="toBottom"
        >
          <div class="iconfont">&#58915;</div>
          <div class="icon-text">底部</div>
        </div>
      </div>
    </div>
    <div class="read-bar" :style="rightBarTheme" @click.stop>
      <div class="tools">
        <div
          class="tool-icon"
          :class="{ 'no-point': noPoint, 'comic-nav': isComic }"
          @click="toPreChapter"
        >
          <template v-if="isComic">
            <span class="nav-arrow">&lt;</span>
          </template>
          <template v-else>
            <div class="iconfont">&#58920;</div>
            <span v-if="miniInterface">上一章</span>
          </template>
        </div>
        <div
          class="tool-icon"
          :class="{ 'no-point': noPoint, 'comic-nav': isComic }"
          @click="toNextChapter"
        >
          <template v-if="isComic">
            <span class="nav-arrow">&gt;</span>
          </template>
          <template v-else>
            <span v-if="miniInterface">下一章</span>
            <div class="iconfont">&#58913;</div>
          </template>
        </div>
      </div>
    </div>
    <div class="slide-nav" v-if="!isComic && isHorizontalMode && miniInterface" @click.stop>
      <div class="tool-icon slide-nav-btn" @click="toPreChapter">
        <span class="nav-arrow">&lt;</span>
      </div>
      <div class="tool-icon slide-nav-btn" @click="toNextChapter">
        <span class="nav-arrow">&gt;</span>
      </div>
    </div>
    <div class="chapter-bar"></div>
    <div class="chapter" ref="content" :style="chapterTheme">
      <div class="content" ref="contentInner">
        <div class="top-bar" ref="top"></div>
        <div
          v-for="data in chapterData"
          :key="data.index"
          :chapterIndex="data.index"
          ref="chapter"
          class="chapter"
        >
          <chapter-content
            ref="chapterRef"
            :chapterIndex="data.index"
            :contents="data.content"
            :title="data.title"
            :spacing="store.config.spacing"
            :fontSize="fontSize"
            :fontFamily="fontFamily"
            @readedLengthChange="onReadedLengthChange"
            v-if="showContent"
          />
        </div>
        <div class="loading" ref="loading"></div>
        <div class="bottom-bar" ref="bottom"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import jump from '@/plugins/jump'
import settings from '@/config/themeConfig'
import API from '@api'
import { useLoading } from '@/hooks/loading'
import { useThrottleFn } from '@vueuse/shared'
import { isNullOrBlank } from '@/utils/utils'

const content = ref<HTMLElement>()
const contentInner = ref<HTMLElement>()
// loading spinner
const { isLoading, loadingWrapper } = useLoading(content, '正在获取信息')
const store = useBookStore()

const {
  catalog,
  popCataVisible,
  readSettingsVisible,
  miniInterface,
  showContent,
  bookProgress,
  theme,
  isNight,
} = storeToRefs(store)

const IMAGE_BOOK_TYPE = 64
const isComic = computed(() => (store.bookType & IMAGE_BOOK_TYPE) !== 0)

watchEffect(() => {
  document.body.classList.toggle('manga-page', isComic.value)
  document.body.classList.toggle('web-slide-mode', store.config.readMode === 1)
})

const chapterPos = computed({
  get: () => store.readingBook.chapterPos,
  set: value => (store.readingBook.chapterPos = value),
})
const chapterIndex = computed({
  get: () => store.readingBook.chapterIndex,
  set: value => (store.readingBook.chapterIndex = value),
})
const isSeachBook = computed({
  get: () => store.readingBook.isSeachBook,
  set: value => (store.readingBook.isSeachBook = value),
})

// 当前阅读书籍readingBook持久化
watch(
  () => store.readingBook,
  book => {
    // 保存localStorage
    // localStorage.setItem(book.bookUrl, JSON.stringify(book));
    // 最近阅读
    localStorage.setItem('readingRecent', JSON.stringify(book))
    //保存 sessionStorage
    sessionStorage.setItem('chapterIndex', book.chapterIndex.toString())
    sessionStorage.setItem('chapterPos', book.chapterPos.toString())
  },
  { deep: 1 },
)

// 无限滚动
const infiniteLoading = computed(() => store.config.infiniteLoading)
let scrollObserver: IntersectionObserver | null
const loading = ref()
watchEffect(() => {
  if (!infiniteLoading.value) {
    scrollObserver?.disconnect()
  } else {
    scrollObserver?.observe(loading.value)
  }
})
const loadMore = () => {
  const index = chapterData.value.slice(-1)[0].index
  if (catalog.value.length - 1 > index) {
    getContent(index + 1, false)
    store.saveBookProgress() // 保存的是上一章的进度，不是预载的本章进度
  }
}
// IntersectionObserver回调 底部加载
const onReachBottom = (entries: IntersectionObserverEntry[]) => {
  if (isLoading.value) return
  for (const { isIntersecting } of entries) {
    if (!isIntersecting) return
    loadMore()
  }
}

// 字体
const fontFamily = computed(() => {
  if (store.config.font >= 0) {
    return settings.fonts[store.config.font]
  }
  return store.config.customFontName
})
const fontSize = computed(() => {
  return store.config.fontSize + 'px'
})

// 主题部分
/** 自动主题(7)时按当前深浅色解析到具体主题色 */
const resolvedTheme = computed(() =>
  theme.value == 7 ? (isNight.value ? 6 : 0) : theme.value,
)
const bodyColor = computed(() => settings.themes[resolvedTheme.value].body)
const chapterColor = computed(() => settings.themes[resolvedTheme.value].content)
const popupColor = computed(() => settings.themes[resolvedTheme.value].popup)

const readWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 120 + 'px'
  } else {
    return window.innerWidth + 'px'
  }
})
const popupWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 33
  } else {
    return Math.max(0, window.innerWidth - 24)
  }
})
const bodyTheme = computed(() => {
  return {
    background: bodyColor.value,
  }
})
const chapterTheme = computed(() => {
  return {
    background: chapterColor.value,
    width: readWidth.value,
  }
})
const showToolBar = ref(false)
const leftBarTheme = computed(() => {
  //漫画左右翻页模式:面板贴最左侧显示
  const stickLeft = isComic.value && isHorizontalMode.value
  return {
    background: popupColor.value,
    left: stickLeft ? '0' : '50%',
    marginLeft: stickLeft
      ? '0'
      : miniInterface.value
        ? 0
        : -(store.config.readWidth / 2 + 68) + 'px',
    display: miniInterface.value && !showToolBar.value ? 'none' : 'block',
  }
})
const rightBarTheme = computed(() => {
  return {
    background: popupColor.value,
    marginRight: miniInterface.value
      ? 0
      : -(store.config.readWidth / 2 + 52) + 'px',
    display: miniInterface.value && !showToolBar.value ? 'none' : 'block',
  }
})

/**
 * pc移动端判断 最大阅读宽度修正
 * 阅读宽度最小为640px 加上工具栏 68px 52px 取较大值 为 776px
 */
const onResize = () => {
  store.setMiniInterface(window.innerWidth < 776)
  const width = store.config.readWidth /**包含padding */
  checkPageWidth(width)
}
/** 判断阅读宽度是否超出页面或者低于默认值640 */
const checkPageWidth = (readWidth: number) => {
  if (store.miniInterface) return
  if (readWidth < 640) store.config.readWidth = 640
  if (readWidth + 2 * 68 > window.innerWidth) store.config.readWidth -= 160
}
watch(
  () => store.config.readWidth,
  width => checkPageWidth(width),
)
// 顶部底部跳转
const top = ref()
const bottom = ref()
const toTop = () => {
  jump(top.value)
}
const toBottom = () => {
  jump(bottom.value)
}

// 书架路由切换
const router = useRouter()
const toShelf = () => {
  router.push('/')
}

// 获取章节内容
const chapterData = ref<{ index: number; content: string[]; title: string }[]>(
  [],
)
const noPoint = ref(true)
const isImageLine = (line: string) =>
  /^\s*<img[^>]*src[^>]+>$/.test(line)

/** 章节加载中标记,防止连点"上一章/下一章"重复请求导致章节重复渲染 */
let chapterLoading = false

const getContent = (index: number, reloadChapter = true, chapterPos = 0) => {
  if (reloadChapter && chapterLoading) return
  if (reloadChapter) chapterLoading = true
  if (reloadChapter) {
    //展示进度条
    store.setShowContent(false)
    //强制滚回顶层
    jump(top.value, { duration: 0 })
    //左右翻页模式回到第一屏
    if (contentInner.value) contentInner.value.scrollLeft = 0
    //从目录，按钮切换章节时保存进度 预加载时不保存
    saveReadingBookProgressToBrowser(index, chapterPos)
    chapterData.value = []
  }
  const bookUrl = store.readingBook.bookUrl
  const { title, index: chapterIndex } = catalog.value[index]

  loadingWrapper(
    API.getBookContent(bookUrl, chapterIndex).then(
      res => {
        if (res.data.isSuccess) {
          const data = res.data.data
          const content = data.split(/\n+/)
          if (
            store.bookType === 0 &&
            content.length > 0 &&
            content.filter(isImageLine).length * 2 >= content.length
          ) {
            store.setBookType(IMAGE_BOOK_TYPE)
          }
          chapterData.value.push({ index, content, title })
          if (reloadChapter) toChapterPos(chapterPos)
        } else {
          ElMessage({ message: res.data.errorMsg, type: 'error' })
          const content = [res.data.errorMsg]
          chapterData.value.push({ index, content, title })
        }
        chapterLoading = false
        store.setContentLoading(true)
        noPoint.value = false
        store.setShowContent(true)
        if (!res.data.isSuccess) {
          throw res.data
        }
      },
      err => {
        const content = ['获取章节内容失败！']
        chapterData.value.push({ index, content, title })
        chapterLoading = false
        store.setShowContent(true)
        throw err
      },
    ),
  )
}

// 章节进度跳转和计算
const chapter = ref()
const chapterRef = ref()
const toChapterPos = (pos: number) => {
  nextTick(() => {
    if (chapterRef.value?.length === 1)
      chapterRef.value[0].scrollToReadedLength(pos)
  })
}

// 60秒保存一次进度
const saveBookProgressThrottle = useThrottleFn(
  () => store.saveBookProgress(),
  60000,
)

const onReadedLengthChange = (index: number, pos: number) => {
  saveReadingBookProgressToBrowser(index, pos)
  saveBookProgressThrottle()
}

// 文档标题
watchEffect(() => {
  document.title = catalog.value[chapterIndex.value]?.title || document.title
})

// 阅读记录保存浏览器
const saveReadingBookProgressToBrowser = (index: number, pos: number) => {
  // 保存pinia
  chapterIndex.value = index
  chapterPos.value = pos
}

// 进度同步
// 返回导航变化 同步请求会在获取书架前完成

/**
 * VisibilityChange https://developer.mozilla.org/zh-CN/docs/Web/API/Document/visibilitychange_event
 * 监听关闭页面 切换tab 返回桌面 等操作
 * 注意不用监听点击链接导航变化 不对Safari<14.5兼容处理
 **/
const onVisibilityChange = () => {
  const _bookProgress = bookProgress.value
  if (document.visibilityState == 'hidden' && _bookProgress) {
    store.saveBookProgress()
  }
}
// 定时同步

// 章节切换
const getChapterImages = () =>
  content.value
    ? Array.from(content.value.querySelectorAll<HTMLImageElement>('img'))
    : []

/** 左右翻页模式:图片横向排列,按钮左右切换 */
const isHorizontalMode = computed(() => store.config.readMode === 1)

const getHorizontalContainer = () =>
  contentInner.value as HTMLElement | undefined

/** 横向翻页动画节流,防止连点导致动画排队/滚动与切章竞态 */
let pageSwitching = false
const lockPageSwitch = (ms = 400) => {
  pageSwitching = true
  setTimeout(() => (pageSwitching = false), ms)
}

const toNextImage = () => {
  if (isHorizontalMode.value) {
    const container = getHorizontalContainer()
    if (container && !pageSwitching) {
      container.scrollBy({
        left: window.innerWidth,
        behavior: 'smooth',
      })
      lockPageSwitch()
      return
    }
  }
  const images = getChapterImages()
  if (images.length === 0) {
    jump(bottom.value)
    return
  }
  const scrollTop = document.documentElement.scrollTop + 4
  let index = images.findIndex(image => image.offsetTop > scrollTop)
  index = index < 0 ? images.length - 1 : Math.max(0, index - 1)
  const target = images[Math.min(images.length - 1, index + 1)]
  if (target) jump(target)
}

const toPreviousImage = () => {
  if (isHorizontalMode.value) {
    const container = getHorizontalContainer()
    if (container && !pageSwitching) {
      container.scrollBy({
        left: -window.innerWidth,
        behavior: 'smooth',
      })
      lockPageSwitch()
      return
    }
  }
  const images = getChapterImages()
  if (images.length === 0) {
    jump(top.value)
    return
  }
  const scrollTop = document.documentElement.scrollTop + 4
  let index = images.findIndex(image => image.offsetTop > scrollTop)
  index = index < 0 ? images.length - 1 : Math.max(0, index - 1)
  const target = images[Math.max(0, index - 1)]
  if (target) jump(target)
}

/** 小说左右翻页:横向分屏翻页,返回 true 表示已翻页,false 表示到边界需切章 */
const toNextNovelPage = (): boolean => {
  const container = getHorizontalContainer()
  if (!container || pageSwitching) return true
  if (
    container.scrollLeft + container.clientWidth < container.scrollWidth - 4
  ) {
    container.scrollBy({
      left: container.clientWidth,
      behavior: 'smooth',
    })
    lockPageSwitch()
    return true
  }
  return false
}

const toPreviousNovelPage = (): boolean => {
  const container = getHorizontalContainer()
  if (!container || pageSwitching) return true
  if (container.scrollLeft > 4) {
    container.scrollBy({
      left: -container.clientWidth,
      behavior: 'smooth',
    })
    lockPageSwitch()
    return true
  }
  return false
}

/**
 * 左右翻页滑动吸附:columns 分栏不产生 scroll snap area,
 * 滚动结束后自动对齐到最近的整屏,体验同漫画模式逐屏切换
 */
const onScrollEndSnap = () => {
  if (!isHorizontalMode.value) return
  const container = getHorizontalContainer()
  if (!container) return
  const target =
    Math.round(container.scrollLeft / container.clientWidth) *
    container.clientWidth
  if (Math.abs(container.scrollLeft - target) > 1) {
    container.scrollTo({ left: target, behavior: 'smooth' })
  }
}

const toNextChapter = () => {
  if (isComic.value) {
    toNextImage()
    return
  }
  if (isHorizontalMode.value) {
    if (toNextNovelPage()) return
  }
  if (chapterLoading) return
  store.setContentLoading(true)
  const index = chapterIndex.value + 1
  if (typeof catalog.value[index] !== 'undefined') {
    ElMessage({
      message: '下一章',
      type: 'info',
    })
    getContent(index)
    store.saveBookProgress()
  } else {
    ElMessage({
      message: '本章是最后一章',
      type: 'error',
    })
  }
}
const toPreChapter = () => {
  if (isComic.value) {
    toPreviousImage()
    return
  }
  if (isHorizontalMode.value) {
    if (toPreviousNovelPage()) return
  }
  if (chapterLoading) return
  store.setContentLoading(true)
  const index = chapterIndex.value - 1
  if (typeof catalog.value[index] !== 'undefined') {
    ElMessage({
      message: '上一章',
      type: 'info',
    })
    getContent(index)
    store.saveBookProgress()
  } else {
    ElMessage({
      message: '本章是第一章',
      type: 'error',
    })
  }
}

let canJump = true
// 监听方向键
const handleKeyPress = (event: KeyboardEvent) => {
  if (!canJump) return
  switch (event.key) {
    case 'ArrowLeft':
      event.stopPropagation()
      event.preventDefault()
      toPreChapter()
      break
    case 'ArrowRight':
      event.stopPropagation()
      event.preventDefault()
      toNextChapter()
      break
    case 'ArrowUp':
      event.stopPropagation()
      event.preventDefault()
      if (document.documentElement.scrollTop === 0) {
        ElMessage.warning('已到达页面顶部')
      } else {
        canJump = false
        jump(0 - document.documentElement.clientHeight + 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
    case 'ArrowDown':
      event.stopPropagation()
      event.preventDefault()
      if (
        document.documentElement.clientHeight +
          document.documentElement.scrollTop ===
        document.documentElement.scrollHeight
      ) {
        ElMessage.warning('已到达页面底部')
      } else {
        canJump = false
        jump(document.documentElement.clientHeight - 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
  }
}

// 阻止默认滚动事件
const ignoreKeyPress = (event: KeyboardEvent) => {
  if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
    event.preventDefault()
    event.stopPropagation()
  }
}

onMounted(async () => {
  store.initSystemTheme()
  await store.loadWebConfig()
  //获取书籍数据
  const bookUrl = sessionStorage.getItem('bookUrl')
  const name = sessionStorage.getItem('bookName')
  const author = sessionStorage.getItem('bookAuthor')
  const chapterIndex = Number(sessionStorage.getItem('chapterIndex') || 0)
  const chapterPos = Number(sessionStorage.getItem('chapterPos') || 0)
  const isSeachBook = sessionStorage.getItem('isSeachBook') === 'true'
  if (
    bookUrl === null ||
    isNullOrBlank(bookUrl) ||
    isNullOrBlank(name) ||
    author === null
  ) {
    ElMessage.warning('书籍信息为空，即将自动返回书架页面...')
    return setTimeout(toShelf, 500)
  }
  store.setBookType(0)
  API.getBookType(bookUrl)
    .then(response => {
      if (response.data.isSuccess) {
        const bookType = Number(response.data.data) || 0
        if (bookType !== 0 || store.bookType === 0) {
          store.setBookType(bookType)
        }
      }
    })
    .catch(() => {})
  const book: typeof store.readingBook = {
    bookUrl: bookUrl as string,
    name: name as string,
    author,
    chapterIndex,
    chapterPos,
    isSeachBook,
  }
      onResize()
      window.addEventListener('resize', onResize)
      contentInner.value?.addEventListener('scrollend', onScrollEndSnap)
      loadingWrapper(
    store.loadWebCatalog(book).then(chapters => {
      store.setReadingBook(book)
      getContent(chapterIndex, true, chapterPos)
      window.addEventListener('keyup', handleKeyPress)
      window.addEventListener('keydown', ignoreKeyPress)
      // 兼容Safari < 14
      document.addEventListener('visibilitychange', onVisibilityChange)
      //监听底部加载
      scrollObserver = new IntersectionObserver(onReachBottom, {
        rootMargin: '-100% 0% 20% 0%',
      })
      if (infiniteLoading.value === true) scrollObserver.observe(loading.value)
      //第二次点击同一本书 页面标题不会变化
      document.title = '...'
      document.title = (name as string) + ' | ' + chapters[chapterIndex].title
    }),
  )
})

onUnmounted(() => {
  window.removeEventListener('keyup', handleKeyPress)
  window.removeEventListener('keydown', ignoreKeyPress)
  window.removeEventListener('resize', onResize)
  contentInner.value?.removeEventListener('scrollend', onScrollEndSnap)
  // 兼容Safari < 14
  document.removeEventListener('visibilitychange', onVisibilityChange)
  readSettingsVisible.value = false
  popCataVisible.value = false
  document.body.classList.remove('manga-page', 'web-slide-mode')
  scrollObserver?.disconnect()
  scrollObserver = null
})

const addToBookShelfConfirm = async () => {
  const book = store.readingBook
  // 阅读的是搜索的书籍 并未在书架
  if (book.isSeachBook === true) {
    await ElMessageBox.confirm(`是否将《${book.name}》放入书架？`, '放入书架', {
      confirmButtonText: '确认',
      cancelButtonText: '否',
      type: 'info',
      /*
        ElMessageBox.confirm默认在触发hashChange事件时自动关闭
        按下物理返回键时触发hashChange事件
        使用router.push("/")则不会触发hashChange事件
        */
      closeOnHashChange: false,
    })
      .then(() => {
        //选择是，无动作
        isSeachBook.value = false
      })
      .catch(async () => {
        //选择否，删除书籍
        await API.deleteBook(book)
      })
      .finally(() => sessionStorage.removeItem('isSeachBook'))
  }
}
onBeforeRouteLeave(async (to, from, next) => {
  console.log('onBeforeRouteLeave')
  // 弹窗时停止响应按键翻页
  window.removeEventListener('keyup', handleKeyPress)
  await addToBookShelfConfirm()
  next()
})
</script>

<style lang="scss" scoped>
:deep(.pop-setting) {
  top: 0;
}

:deep(.pop-cata) {
  margin-left: 10px;
}

.chapter-wrapper {
  padding: 0 4%;

  overflow-x: hidden;

  :deep(.no-point) {
    pointer-events: none;
  }

  .tool-bar {
    position: fixed;
    top: 0;
    left: 50%;
    z-index: 100;

    .tools {
      display: flex;
      flex-direction: column;

      .tool-icon {
        font-size: 18px;
        width: 58px;
        height: 48px;
        text-align: center;
        padding-top: 12px;
        cursor: pointer;
        outline: none;

        .iconfont {
          font-family: iconfont;
          width: 16px;
          height: 16px;
          font-size: 16px;
          margin: 0 auto 6px;
        }

        .icon-text {
          font-size: 12px;
        }
      }
    }
  }

  .read-bar {
    position: fixed;
    bottom: 0;
    right: 50%;
    z-index: 100;

    .tools {
      display: flex;
      flex-direction: column;

      .tool-icon {
        font-size: 18px;
        width: 42px;
        height: 31px;
        padding-top: 12px;
        text-align: center;
        align-items: center;
        cursor: pointer;
        outline: none;
        margin-top: -1px;

        .iconfont {
          font-family: iconfont;
          width: 16px;
          height: 16px;
          font-size: 16px;
          margin: 0 auto 6px;
        }
      }
    }
  }

  .chapter {
    font-family: 'Microsoft YaHei', PingFangSC-Regular, HelveticaNeue-Light,
      'Helvetica Neue Light', sans-serif;
    text-align: left;
    padding: 0 65px;
    min-height: 100vh;
    width: 680px;
    margin: 0 auto;

    .content {
      font-size: 18px;
      line-height: 1.8;
      font-family: 'Microsoft YaHei', PingFangSC-Regular, HelveticaNeue-Light,
        'Helvetica Neue Light', sans-serif;

      .bottom-bar,
      .top-bar {
        height: 64px;
      }
    }
  }
}

/* v-for 渲染的章节容器不该再套用阅读框的 padding/width/边框,否则正文整体向右偏移且出现内框 */
.chapter .content .chapter {
  width: auto !important;
  padding: 0 !important;
  margin: 0 !important;
  border: none !important;
}

.day {
  :deep(.popup) {
    box-shadow:
      0 2px 4px rgba(0, 0, 0, 0.12),
      0 0 6px rgba(0, 0, 0, 0.04);
  }

  :deep(.tool-icon) {
    border: 1px solid rgba(0, 0, 0, 0.1);
    margin-top: -1px;
    color: #000;

    .icon-text {
      color: rgba(0, 0, 0, 0.4);
    }
  }

  :deep(.chapter) {
    border: 1px solid #d8d8d8;
    color: #262626;
  }
}

.night {
  :deep(.popup) {
    box-shadow:
      0 2px 4px rgba(0, 0, 0, 0.48),
      0 0 6px rgba(0, 0, 0, 0.16);
  }

  :deep(.tool-icon) {
    border: 1px solid #444;
    margin-top: -1px;
    color: #666;

    .icon-text {
      color: #666;
    }
  }

  :deep(.chapter) {
    border: 1px solid #444;
    color: #666;
  }

  :deep(.popper__arrow) {
    background: #666;
  }
}

@media screen and (max-width: 776px) {
  .chapter-wrapper {
    padding: 0;

    .tool-bar {
      left: 0;
      width: 100vw;
      margin-left: 0 !important;

      .tools {
        flex-direction: row;
        justify-content: space-between;

        .tool-icon {
          border: none;
        }
      }
    }

    .read-bar {
      right: 0;
      width: 100vw;
      margin-right: 0 !important;

      .tools {
        flex-direction: row;
        justify-content: space-between;
        padding: 0 15px;

        .tool-icon {
          border: none;
          width: auto;

          .iconfont {
            display: inline-block;
          }
        }
      }
    }

    .chapter {
      width: 100vw !important;
      padding: 0 20px;
      box-sizing: border-box;
    }
  }
}

@media screen and (min-width: 777px) {
  :deep(.pop-setting) {
    margin-left: 68px;
  }
}

@media screen and (max-width: 776px) {
  :deep(.pop-setting) {
    margin-left: 0;
    max-width: calc(100vw - 24px) !important;
  }
}
</style>

<style lang="scss">
/* 小说左右翻页:阅读框保持与垂直滚动一致的内边距,总宽与垂直滚动外框相同 */
body.web-slide-mode:not(.manga-page) .chapter-wrapper .chapter {
  margin: 0 auto;
}

/* 漫画左右翻页:整屏大图,每屏一张(电脑端也保持全屏,避免容器宽度与屏宽不一致导致SMB图加载异常) */
body.web-slide-mode.manga-page .chapter-wrapper .chapter {
  width: 100vw !important;
  padding: 0;
  margin: 0;
  border: none !important;
}

/* 手机端左右翻页全屏 */
@media (max-width: 776px) {
  body.web-slide-mode .chapter-wrapper .chapter {
    width: 100vw !important;
    border: none !important;
  }
}

/* 左右翻页保留顶部抬头框,隐藏底部与加载占位 */
body.web-slide-mode .chapter-wrapper .chapter .content > .bottom-bar,
body.web-slide-mode .chapter-wrapper .chapter .content > .loading {
  display: none;
}

body.web-slide-mode .chapter-wrapper .chapter .content .chapter {
  display: contents;
}

/* 漫画左右翻页:图片横向排列,每屏一张 */
body.web-slide-mode.manga-page .chapter-wrapper .chapter .content {
  display: flex;
  flex-direction: row;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  -webkit-overflow-scrolling: touch;
  min-height: 100vh;
  background: #333;
}

body.web-slide-mode.manga-page .chapter-wrapper .chapter .content .chapter > * {
  flex: 0 0 100vw;
  scroll-snap-align: start;
  margin: 0;
}

body.web-slide-mode.manga-page .chapter-wrapper .chapter .content .title,
body.web-slide-mode.manga-page .chapter-wrapper .chapter .content p {
  display: none;
}

body.web-slide-mode.manga-page .chapter-wrapper .full {
  width: 100vw;
  height: 100vh;
  object-fit: contain;
  display: block;
  background: #333;
}

/* 小说左右翻页:多栏横向分屏,每屏一页,宽度跟随阅读背景框 */
body.web-slide-mode:not(.manga-page) .chapter-wrapper .chapter .content {
  columns: 1;
  column-gap: 0;
  height: 100vh;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  -webkit-overflow-scrolling: touch;
  background: transparent;
}

body.web-slide-mode:not(.manga-page) .chapter-wrapper .chapter .content .title {
  margin: 0 0 24px 0;
  padding: 0 20px;
}

body.web-slide-mode:not(.manga-page) .chapter-wrapper .chapter .content p {
  padding: 0 20px;
}

body.manga-page .chapter-wrapper .read-bar {
  position: fixed;
  top: 50%;
  bottom: auto;
  left: 0;
  right: 0;
  width: 100vw !important;
  transform: translateY(-50%);
  background: transparent !important;
  border: none;
  display: flex !important;
  margin: 0 !important;
  padding: 0 12px;
  box-sizing: border-box;
  pointer-events: none;
}

body.manga-page .chapter-wrapper .read-bar .tools {
  flex-direction: row;
  justify-content: space-between;
  width: 100%;
  background: transparent;
  padding: 0;
  pointer-events: none;
}

/* 优先级需高于 scoped 的 .read-bar .tools .tool-icon[data-v] 椭圆规则 */
body.manga-page .chapter-wrapper .read-bar .tools .tool-icon {
  width: 46px;
  height: 46px;
  margin: 0;
  padding: 0;
  border: none;
  background: rgba(0, 0, 0, 0.35);
  border-radius: 50%;
  justify-content: center;
  align-items: center;
  display: flex;
  pointer-events: auto;
}

body.manga-page .chapter-wrapper .read-bar .nav-arrow {
  color: #fff;
  font-size: 22px;
  line-height: 1;
}

/* 小说左右翻页:手机端两侧圆形 < > 翻页按钮(同漫画模式样式) */
body.web-slide-mode:not(.manga-page) .chapter-wrapper .slide-nav {
  position: fixed;
  top: 50%;
  left: 0;
  right: 0;
  width: 100vw !important;
  transform: translateY(-50%);
  display: flex;
  justify-content: space-between;
  padding: 0 12px;
  box-sizing: border-box;
  pointer-events: none;
  z-index: 100;
}

body.web-slide-mode:not(.manga-page)
  .chapter-wrapper
  .slide-nav
  .slide-nav-btn {
  width: 46px;
  height: 46px;
  margin: 0;
  padding: 0;
  border: none;
  background: rgba(0, 0, 0, 0.35);
  border-radius: 50%;
  justify-content: center;
  align-items: center;
  display: flex;
  pointer-events: auto;
}

body.web-slide-mode:not(.manga-page)
  .chapter-wrapper
  .slide-nav
  .slide-nav-btn
  .nav-arrow {
  color: #fff;
  font-size: 22px;
  line-height: 1;
}
</style>
