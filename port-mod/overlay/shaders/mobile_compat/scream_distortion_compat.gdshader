shader_type canvas_item;
render_mode blend_mix;

uniform float distortion_base_intensity = 0.05;
uniform vec2 tiling = vec2(1.0, 1.0);
uniform vec2 offset_speed;

varying vec4 vertex_color;

vec2 get_polar_uv(vec2 base_uv) {
	base_uv -= 0.5;
	base_uv *= 2.0;

	float angle = atan(base_uv.y, base_uv.x) / (2.0 * PI);
	float dist = length(base_uv);
	return vec2(angle, dist);
}

void vertex() {
	vertex_color = COLOR;
}

void fragment() {
	vec2 polar_uv = get_polar_uv(UV);
	polar_uv *= tiling;
	polar_uv += TIME * offset_speed;

	float distortion_mask = texture(TEXTURE, UV).a;
	float distortion_polar_mask = texture(TEXTURE, polar_uv).r;
	float alpha = clamp(distortion_mask * distortion_polar_mask * vertex_color.a * distortion_base_intensity * 6.0, 0.0, 1.0);
	COLOR = vec4(vec3(1.0), alpha * 0.25);
}
