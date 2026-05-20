shader_type canvas_item;

uniform sampler2D noise : repeat_enable;
uniform sampler2D mask;
uniform float speed : hint_range(0.002,0.5) = 0.1;
uniform float strength : hint_range(0.002,0.5) = 0.1;

void fragment() {
	float noise_value = texture(noise, UV + vec2(TIME * speed, -TIME * speed)).r - 0.5;
	vec2 offset = vec2(noise_value) * strength * 0.25;
	COLOR = texture(TEXTURE, UV + offset);
	COLOR.a *= texture(mask, UV).a * 0.35;
}
