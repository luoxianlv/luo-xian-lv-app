// The legacy Cutout Vignette shader relies on HLSL truncating vec3 - vec2 to vec2.
// Restrict this correction to its exact expression, leaving source project bytes untouched.
export function fixLegacyCutout(source) {
  if (!/varying\s+vec3\s+v_TexCoord\s*;/.test(source) || !/uniform\s+float\s+u_offset\s*;/.test(source)) return source;
  return source.replace(/\bv_TexCoord\s*-\s*CAST2\(\s*u_offset\s*\)/g, 'v_TexCoord.xy - CAST2(u_offset)');
}
