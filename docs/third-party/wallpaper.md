# 壁纸渲染器与素材来源

## WebWallGL

壁纸场景使用 [WebWallGL](https://github.com/oneincase/webwallgl)，固定发行版本 `webwallgl@1.4.2`，许可证为 MIT。完整许可随应用保存于 [LICENSE-WebWallGL.txt](../../app/src/main/assets/wallpaperengine/LICENSE-WebWallGL.txt)。

发行包 SHA-512：

```text
gXYrv9HATvBsnYdNYunfQ8EeJ3bHDWYskvEkUTZlzHSGXBr5Fhw9TYtj//zJvQ+7fEMnjVC+RN4i38wHuR8iFg==
```

修改记录见 [基础补丁](webwallgl-1.4.2.patch) 和 [音频补丁](webwallgl-audio-controls.patch)。本地适配主要涉及：

- 旧版 Cutout Vignette 的 HLSL 类型转换和混合模式着色器。
- 离线网页沙箱、时间注入、场景视频生命周期。
- 壁纸独立音轨、渲染暂停与资源释放。

渲染入口为 [PracticeBackdrop](../../app/src/main/java/app/luoxianlv/wallpaper/PracticeBackdrop.kt)，脚本位于 [assets/wallpaperengine](../../app/src/main/assets/wallpaperengine)。

## 项目格式

导入支持 `scene`、视频、图片／GIF 和离线网页项目。ZIP 应包含 `project.json` 及其引用资源；项目不会被转码或改写。

网页项目运行于受限 iframe，不开放原生桥、网络或应用文件访问。依赖桌面插件、网络服务、加密包或 Windows 应用的项目不在支持范围内。复杂场景的效果取决于上游渲染器和设备能力。

壁纸音轨默认关闭，口琴使用独立音频轨。使用与排查见[操作指南](../operations.md)。

## 静态背景

应用的夕阳背景来自 jensenartofficial：

- [Pixabay 原作品](https://pixabay.com/illustrations/sunset-anime-minimal-nature-sky-7628294/)
- [原图片](https://cdn.pixabay.com/photo/2022/12/01/04/35/sunset-7628294_1280.jpg)

作品保留原作者权利，素材使用遵循其来源平台许可。默认动态壁纸通过独立对象下载，个人导入项目由用户提供；这些资源不随客户端源码的 AGPL 许可重新授权。
