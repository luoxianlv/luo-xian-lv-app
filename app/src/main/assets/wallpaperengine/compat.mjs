// 旧 Cutout Vignette 着色器依赖 HLSL 将 vec3−vec2 截断为 vec2；仅修正该表达式，不修改原始项目文件。
export function fixLegacyCutout(source) {
  if (!/varying\s+vec3\s+v_TexCoord\s*;/.test(source) || !/uniform\s+float\s+u_offset\s*;/.test(source)) return source;
  return source.replace(/\bv_TexCoord\s*-\s*CAST2\(\s*u_offset\s*\)/g, 'v_TexCoord.xy - CAST2(u_offset)');
}
