# 液态玻璃＋粒子重组小样

Three.js 0.160.1（MIT），本地依赖，真实透视网格、MeshPhysicalMaterial 透射/厚度/IOR/虹彩，RoomEnvironment + PMREM 环境反射。32 个实例化立体液滴，384 个轻量实例微粒，沿预计算规则重组为八个环。背景使用现有项目静态壁纸。浏览器视觉原型，不是 Filament 原生实现；FPS 是当前浏览器测量，不代表手机帧率。

python -m http.server 18461 --bind 127.0.0.1 --directory docs/previews/liquid-stage
