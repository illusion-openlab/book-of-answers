# 图标源文件与生成流程

图标由两张提供的素材合成，脚本不做任何色彩加工：

| 源文件 | 用途 |
| --- | --- |
| `icon-source-book.png` | 书本抠图（1254² RGBA，透明底），用作**主体层** |
| `icon-source-bg.png` | 星盘底图（1536² 不透明），用作**背景层** |

`build-icons.py` 从这两张生成 `app/src/main/res/` 下全部六个图标资源。改图标时改源图 + 跑脚本，
不要手改生成物。

**构图：一层背景底图 + 一层书本抠图，仅此而已。** 不加渐变、不加光效、不调色。
（历史上曾自作主张加过径向渐变和暖光，并把背景换成自己调的中蓝色，均已按要求移除。
往里加任何装饰前，先确认那是需求。）

底图整幅等比缩放到目标画布，不裁剪。金环落在归一化半径 0.70–0.92 的外环带；书本半对角约占
内切圆半径 96.5%，于是金环在书本四边露出、被书的四角压住。

底图亮度中位约 23 —— 偏暗但不是纯黑，系统自动生成的分层悬停高光/阴影仍有可依附的亮度
（规范只是建议避免纯黑/深黑；过审红线是「背景层不能透明/半透明」和「主体层不能全透明」，
两条都满足）。

```bash
python3 art/build-icons.py     # 从仓库根目录运行
```

## 生成物与依据

| 文件 | 角色 | 规范依据 |
| --- | --- | --- |
| `drawable/icon_3d_layer_0.png` | 3D 图标**背景层** | 1024²，纯色，内切圆内**不得有透明/半透明像素**。规范建议避免纯黑/深黑（见上方"已知代价"） |
| `drawable/icon_3d_layer_1.png` | 3D 图标**主体层** | 1024²，不得全透明；边缘锐利，**不得自带柔化过渡/阴影/反光/高光**（系统会自动加，手动加会打架） |
| `drawable/icon_3d_sdf_0/1.png` | 两层各自的 SDF | 见下 |
| `mipmap-xxxhdpi/ic_spatial_launcher.png` | 平面 launcher 图标 | 内切圆构图 |
| `drawable/ic_launcher_foreground.png` | Android adaptive icon 前景 | 1424² 满幅。`mipmap-anydpi/ic_launcher.xml` **只有 foreground/monochrome、没有 background 层**，所以底色必须烤进这张图；内容收在中央 66% 安全区 |

分层规范出处：`$PICO_HOME/6.0/agent-vault/spatial/documentation/spatial-design_foundation_icon_app-icon-and-layered-design.md`

## SDF 的规则是逆推出来的，文档没写

`icon.sdf.list` 不在上面那份分层规范里。从脚手架自带的素材实测得出：

- RGB 恒为纯白，形状信息全在 **alpha**
- alpha = **图层内部到边缘的欧氏距离场**，线性归一化后在 **31 px** 处截断（1024² 画布下）

实测佐证（背景层是半径 511 的圆，`icon_3d_sdf_0` 的 alpha 沿半径）：

| 距边缘 | 实测 alpha | 线性模型 d/31 |
| --- | --- | --- |
| 31 px (r=480) | 253/255 | 1.00 |
| 21 px (r=490) | 177/255 = 0.70 | 0.68 |
| 11 px (r=500) | 98/255 = 0.39 | 0.35 |
| 1 px (r=510) | 21/255 | 0.03 |

所以脚本用 `cv2.distanceTransform` + `clip(d/31, 0, 1)` 复现，而不是靠腐蚀加模糊去凑。
