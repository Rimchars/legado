# 气泡与高级标题资源包

应用同时支持旧版内嵌资源和新版外置资源包。旧版高级标题 JSON、旧版气泡 ZIP、Data URL 图片均可继续导入和渲染。

## 资源别名

新版包在配置清单的 `resources` 中声明资源：

```json
{
  "formatVersion": 2,
  "resources": [
    { "alias": "background", "path": "assets/background.webp", "type": "image" },
    { "alias": "titleFont", "path": "assets/title.ttf", "type": "font" }
  ]
}
```

别名大小写不敏感，必须以英文字母开头，只能包含英文字母、数字、点、短横线和下划线。资源文件必须位于包内的 `assets/`；兼容标准 Lottie 时也允许 `images/`。

图片可以使用 `asset://background`，字体可以使用 `asset://titleFont`。修改实际文件名时只需更新清单，不必修改 Lottie 或 SVG 中的调用位置。

## 高级标题包

```text
advanced-title.zip
├─ package.json
├─ title.json
└─ assets/
   ├─ background.webp
   └─ title.ttf
```

Lottie 图片引用：

```json
{
  "assets": [
    {
      "id": "image_0",
      "w": 1920,
      "h": 1080,
      "u": "assets/",
      "p": "background.webp",
      "e": 0
    },
    {
      "id": "image_1",
      "w": 512,
      "h": 512,
      "p": "asset://background",
      "e": 0
    }
  ]
}
```

Lottie 字体引用：

```json
{
  "fonts": {
    "list": [
      {
        "fName": "asset://titleFont",
        "fFamily": "asset://titleFont",
        "fStyle": "Regular"
      }
    ]
  }
}
```

包内字体只对当前高级标题生效，不会安装到系统，也不会复制到应用的全局字体目录。未指定包内字体时，高级标题继续使用阅读页统一选择的字体。

没有外置资源的高级标题仍导出为旧版 JSON；带外置资源时导出为完整 ZIP。

## 气泡包

```text
bubble.zip
├─ bubble.json
└─ assets/
   ├─ background.webp
   └─ bubble.ttf
```

SVG 图片和字体引用：

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <image href="asset://background" x="0" y="0" width="64" height="64"/>
  <text x="32" y="32" text-anchor="middle"
        font-family="asset://bubbleFont">${num}</text>
</svg>
```

气泡包内字体优先；没有包内字体时，正文气泡使用阅读页当前字体。包内字体不会导入全局字体库。

## 安全与大小限制

- 压缩包最大 256 MiB，解压总量最大 512 MiB。
- 图片单文件最大 64 MiB，字体单文件最大 32 MiB，SVG 最大 4 MiB。
- 最多声明 512 个资源，声明资源总量最大 256 MiB。
- 禁止绝对路径、`..`、`file://`、`content://` 和网络地址。
- 文件扩展名必须与实际图片或字体文件头一致。
- 图片按实际显示尺寸采样解码，不会一次加载包内所有图片。
- 包导入使用 staging/backup 原子安装，失败不会覆盖现有配置。

完整应用备份会包含高级标题和气泡资源目录，恢复后仍能保留图片、字体和别名。
