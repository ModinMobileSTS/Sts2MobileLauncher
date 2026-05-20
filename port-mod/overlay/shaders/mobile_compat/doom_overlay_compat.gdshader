shader_type canvas_item;

uniform float s: hint_range(0,5) = 1.0;
uniform float v = 1.0;
uniform vec4 gradient_color : source_color;

void fragment(){
	float pulse = pow(UV.y, 6.0) * 0.7 * COLOR.a * smoothstep(-10.0, 1.0, sin(TIME * 20.0) + sin(TIME * 10.0));
	vec3 base = gradient_color.rgb * mix(0.12, 0.4, clamp(v, 0.0, 1.0));
	COLOR = vec4(base + pulse * gradient_color.rgb, clamp(COLOR.a * 0.28 + pulse * gradient_color.a * 0.35, 0.0, 1.0));
}
