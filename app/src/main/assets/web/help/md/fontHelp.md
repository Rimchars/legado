# 统一字体 `@font:` 帮助

高级标题（Lottie）、气泡（SVG）、usehtml 等都可以**按次自选字体**，不绑定某个全局固定字体。

## 写法

```text
@font:字体名字
```

- `字体名字` 建议用字体库里的**文件名**
- 完整示例：`@font:SourceHan.ttf`
- 也可省略扩展名：`@font:SourceHan`（会按文件名匹配）

## 怎么选用字体

1. **手填**：在 Lottie 字体字段、SVG `font-family`、HTML `font-family` 里写 `@font:xxx`
2. **弹窗选择**：界面可调用 `AppFont.pickOrType(...)`（手填 + 字体库）或 `AppFont.pick(...)`（仅字体库），选中后写入 `@font:文件名`
3. 每次引用各自保存自己的 ref，**互不影响**

## 支持的引用

| 写法 | 含义 |
|---|---|
| `@font:名字` | 从应用字体库 / 用户字体文件夹匹配 |
| 裸名称 `SourceHan.ttf` | 等同 `@font:SourceHan.ttf` |
| `reader` | 跟随正文阅读字体 |
| `reader:title` | 跟随阅读标题字体 |
| `system` | 系统默认无衬线 |
| `system:serif` / `system:mono` | 系统衬线 / 等宽 |
| 绝对路径 / `content://` | 兼容历史写法 |
| `package:...` | 高级标题/气泡包内字体 |

## 找不到字体时

1. `@font:不存在的字体`：**不崩溃**，回退到正文/标题字体，再回退系统默认
2. 想严格探测是否存在：用 `AppFont.tryTypeface(...)`，找不到返回 `null`

## 示例

### 高级标题 Lottie

在字体字段（`f` / font name）写：

```text
@font:思源黑体.ttf
```

或只写文件名：

```text
思源黑体.ttf
```

或弹窗选择后自动写入 `@font:…`。

### 气泡 SVG

```svg
font-family="@font:思源黑体.ttf"
```

或

```svg
font-family="reader"
```

### usehtml（正文）

```html
<usehtml>
<p style="font-family: @font:仓耳今楷.ttf; font-size: 18px;">
  这是 usehtml 里的仓耳今楷
</p>
</usehtml>
```

也支持：

```html
<usehtml>
<font face="@font:仓耳今楷.ttf">仓耳今楷</font>
<span style="font-family: reader">跟随正文字体</span>
</usehtml>
```

说明：阅读器正文 `usehtml` 已支持 `font-family` / `<font face>` → `@font:`（经 AppFont）。字体需在 App 字体库；找不到则回退正文/系统字体。

## 代码

```kotlin
// 解析（找不到则回退）
AppFont.typeface("@font:SourceHan.ttf")
AppFont.tryTypeface("@font:没有") // null，调用方再 fallback

// 手填 + 字体库弹窗（推荐给 UI）
AppFont.pickOrType(activity, currentRef) { ref -> /* 写入 ref */ }

// 仅字体库弹窗
AppFont.pick(fragment, currentRef) { ref -> /* 写入 ref */ }

// 阅读字体变更后清缓存
AppFont.onReaderFontChanged()
```

## 注意

- 推荐统一写成 `@font:名字`，便于模板迁移与排查
- 字体需已出现在「字体」设置的字体文件夹或应用 `font` 目录
- 目前匹配扩展名：`.ttf` / `.otf`
- 帮助入口：`showHelp("fontHelp")`

## 书源 / JS

```js
// JSON 数组：[{ref, displayName, pathOrUri}, ...]
var fonts = JSON.parse(java.getFontList());
// ref 默认带 @font: 前缀，可直接用于 font-family / Lottie / 气泡
// java.getFontList(false) 时 ref 仅为文件名

for (var i = 0; i < fonts.length; i++) {
  // fonts[i].ref          例 "@font:仓耳今楷.ttf"
  // fonts[i].displayName  例 "仓耳今楷.ttf"
  // fonts[i].pathOrUri    本地路径或 content Uri
}
```

