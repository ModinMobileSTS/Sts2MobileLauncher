shader_type canvas_item;

uniform sampler2D noise : repeat_enable;
uniform sampler2D mask;
uniform float speed : hint_range(0.002,1) = 0.1;
uniform float strength : hint_range(0.002,0.5) = 0.1;
uniform float angle_degrees : hint_range(0, 360) = 45.0;

void fragment() {
	float angle_rad = radians(angle_degrees);
	vec2 direction = vec2(cos(angle_rad), sin(angle_rad));
	float noise_value = texture(noise, UV - TIME * speed * direction).r - 0.5;
	vec2 offset = vec2(noise_value) * strength * 0.25;
	COLOR = texture(TEXTURE, UV + offset);
	COLOR.a *= texture(mask, UV).a * 0.35;
}
