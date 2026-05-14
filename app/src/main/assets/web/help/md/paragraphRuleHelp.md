# 段落处理规则

段落处理规则用于在正文解析完成后，对整章正文进行二次处理。规则独立于书源存在，不会默认对所有书生效，需要在阅读页的“段落规则管理”中为当前书启用。

段落处理规则仅对 TXT 以及书源正文生效，EPUB 不生效。

## 执行入口

当前版本固定调用 `process(ctx)`，规则需要定义同名函数，并在函数中返回处理结果。

```js
function process(ctx) {
  return ctx.result;
}
```

段落规则是一次性处理整章正文。也就是说，App 会先把当前章节的所有段落整理成一个表，再调用一次 `process(ctx)`，不会逐段调用。

## ctx 对象

`ctx` 是当前章节的上下文对象。

```js
{
  result: "整章正文字符串",
  paragraphs: [
    {
      index: 1,
      text: "第一段正文",
      start: 0,
      end: 5,
      separator: "\n"
    }
  ],
  book: {
    name: "书名",
    author: "作者",
    bookUrl: "书籍地址",
    origin: "书源地址",
    latestChapterTitle: "最新章节",
    durChapterTitle: "当前阅读章节",
    durChapterIndex: 0
  },
  chapter: {
    title: "章节标题",
    url: "章节地址",
    index: 0,
    isVolume: false
  },
  rule: {
    id: 1,
    name: "规则名称"
  },
  vars: {
    key: "value"
  }
}
```

字段说明：

- `ctx.result`：整章正文字符串。
- `ctx.paragraphs`：正文段落表，段落序号从 1 开始。
- `ctx.book`：当前书籍信息。
- `ctx.chapter`：当前章节信息。
- `ctx.rule`：当前段落规则信息。
- `ctx.vars`：当前段落规则变量快照。

## 返回字符串

返回字符串时，返回值会直接作为整章正文。

```js
function process(ctx) {
  return ctx.result.replace(/旧文本/g, "新文本");
}
```

## 返回段落数组

返回数组时，数组中的每一项会作为一段正文重新拼接。

```js
function process(ctx) {
  return ctx.paragraphs.map(function(p) {
    return {
      index: p.index,
      text: p.text + "test"
    };
  });
}
```

数组项常用字段：

```js
{
  index: 1,
  text: "处理后的段落正文"
}
```

`index` 用于标明对应的原段落序号，`text` 是输出的段落文本。返回数组时可以新增段落，段落数量可以超过原文段落数量。

```js
function process(ctx) {
  var out = [];
  ctx.paragraphs.forEach(function(p) {
    out.push({ index: p.index, text: p.text });
    if (p.index === 1) {
      out.push({ index: 10001, text: "插入的新段落" });
    }
  });
  return out;
}
```

`text` 为空或空白时，该项不会输出。

## 返回段落替换表

返回对象时，会按段落序号替换对应段落，未出现的段落保持原文。

```js
function process(ctx) {
  return {
    1: "替换第一段",
    3: "替换第三段"
  };
}
```

## 变量读写

段落规则变量独立保存，不等同于书源变量。

```js
var token = source.get("token");
source.put("token", "value");

var value = java.get("key");
java.put("key", "value");
```

`ctx.vars` 是执行开始时的变量快照。如果需要修改变量，使用 `source.put` 或 `java.put`。

## 键值 pclick

段落规则新增键值 `pclick`，作用等同于书源中的 `click`，用于绑定点击后执行的 JS。

```js
function process(ctx) {
  return ctx.paragraphs.map(function(p) {
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="220" height="80">' +
      '<text x="20" y="50" font-size="30">打开</text></svg>';

    var option = JSON.stringify({
      type: "paragraph",
      style: "FULL",
      pclick: "openPanel(" + p.index + ")"
    });

    var img = '<img src="data:image/svg+xml;base64,' +
      java.base64Encode(svg) + ',' + option + '">';

    return {
      index: p.index,
      text: p.text + img
    };
  });
}

function openPanel(index) {
  java.showBrowser("https://example.com/?index=" + index);
}
```

`pclick` 的值是点击时执行的 JS 字符串。点击时会在当前段落规则环境中执行，因此可以调用规则中定义的函数。