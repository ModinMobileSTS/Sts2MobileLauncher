shader_type canvas_item;

const vec2 size = vec2(598, 842);

uniform sampler2D mask_texture : repeat_disable;
uniform vec4 mask_region;
uniform vec2 mask_offset;
uniform vec2 step;
uniform float radius = 32.0;
uniform bool mask;
uniform bool blur;

varying float rotation;

void vertex() {
	rotation = 0.0;
}

void fragment() {
	vec4 col = texture(TEXTURE, UV);
	if (blur) {
		float luma = dot(col.rgb, vec3(0.299, 0.587, 0.114));
		col.rgb = mix(col.rgb, vec3(luma), 0.35);
	}
	COLOR = col;
	if (mask) {
		vec2 mask_size = vec2(textureSize(mask_texture, 0));
		vec2 mask_offset_uv = mask_offset / mask_size;
		vec2 mask_region_size_uv = mask_region.zw / mask_size + mask_offset_uv;
		vec2 mask_region_uv = mask_region.xy / mask_size;
		vec2 mask_uv = clamp(UV, vec2(0.0), vec2(1.0)) * mask_region_size_uv + mask_region_uv - mask_offset_uv;
		mask_uv = clamp(mask_uv, mask_region_uv, mask_region_uv + mask_region_size_uv);
		COLOR.a *= texture(mask_texture, mask_uv).a;
	}
}
