shader_type canvas_item;
render_mode blend_mix;

uniform vec2 blur_center = vec2(0.25, 0.5);
uniform float blur_power = 0.005;
uniform int sampling_count = 12;

void fragment(){
	float dist = distance(UV, blur_center);
	float pulse = smoothstep(0.8, 0.0, dist) * clamp(blur_power * 120.0, 0.0, 1.0);
	COLOR = vec4(vec3(1.0), pulse * 0.18);
}
