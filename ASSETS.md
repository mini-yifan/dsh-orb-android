# 品牌与吉祥物素材

本仓库是 DSH Go 的衍生版本，App 以 **DSH Orb** 的名称发布。源码以 AGPL-3.0 发布（见 [LICENSE](LICENSE)），但下面这些品牌和美术素材**不在 AGPL-3.0 授权范围内**，版权归 ccch1mneyyy（DSH-TUI）所有，保留所有权利。

> 上游的说明是：以修改版身份发布时请换成自己的名称和素材。本仓库已经换了名称；**启动图标和桌宠仍在沿用上游素材**，替换它们属于尚未完成的品牌工作。

你可以为了构建、测试和运行本仓库而使用这些素材。如果你再发布修改版或衍生作品，请换成自己的名称和素材，不要把它们当作 DSH Orb 的自有品牌。

| 素材 | 位置 | 来源 |
|---|---|---|
| DSH Go 品牌图形（鱼尾与气泡，当前用作 App 启动图标） | `app/assets/brand/` 下的 SVG 母版（`tile.svg`、`glyph-light.svg`、`glyph-dark.svg`、`glyph-mono.svg`）；由 `app/tool/make_brand_mark.py` 生成的 `app/lib/ui/shell/brand_glyph.g.dart`；启动图标 `app/android/app/src/main/res/mipmap-*/ic_launcher*` 与 `drawable/ic_launcher_*` | DSH Go / DSH-TUI 的品牌图形 |
| Deepy 小鲸鱼（像素鲸鱼桌宠） | 源文件 `app/tool/pet_src/deepy-whale/`；宠物包 `app/assets/pets/deepy-whale/` | DSH-TUI 的吉祥物。源文件按 [Clawd on Desk](https://github.com/rullerzhou-afk/clawd-on-desk) 的主题包格式制作，只沿用了它的格式 |
| 鲸娘（像素鲸鱼娘表情） | 源文件 `app/tool/pet_src/whale-girl/`（`index.html` 是 https://dshtui.com/pets/whale-girl-emoji/ 原页面，里面有事件对应关系和逐帧时长）；宠物包 `app/assets/pets/whale-girl/`；求 Star 弹窗的贴纸 `app/assets/star/star_whale.webp`；官网与 `site/share/` 当前的图标由鲸娘头像裁出 | DSH-TUI 的吉祥物，最初发布在 dshtui.com |

鲸鱼尾巴图形（`app/assets/whale/`，源文件 `app/tool/whale_src/`）：其中一个是 DeepSeek 的官方图标，版权和商标归 DeepSeek 所有，仅用于表明本应用连接的是 DeepSeek 服务；其余几张是基于它的二创。它们同样不在 AGPL-3.0 授权范围内。如果权利人提出要求，我们会替换。

这些鲸鱼形象是 DSH-TUI 自己的非官方形象，和 DeepSeek 没有关系，也不代表 DeepSeek。

展示素材：仓库首页 README 已改为文字标题，不再内嵌图片；`site/share/` 里的站点图标同样适用本页的条件，可以随本仓库一起查看和转载介绍，不要用在你自己发布的产品上。

宠物包由 `app/tool/make_pet_pack.py` 从 `app/tool/pet_src/` 生成，品牌图形的代码由 `app/tool/make_brand_mark.py` 从 `app/assets/brand/` 生成。工具脚本本身和其他源码一样，以 AGPL-3.0 发布。

伙伴小屋的 Live2D 素材不在这个仓库和 APK 里：Cubism Core（Live2D 专有许可）、Cubism Web Framework（Live2D Open Software License）和角色模型都打成单独的素材包，用时才放进手机，见 [docs/design/companion.md](docs/design/companion.md)。模型的作者和授权写在素材包的 `character.json` 里，App 菜单底部原样显示。

第三方的代码、字体、图标和运行时组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
