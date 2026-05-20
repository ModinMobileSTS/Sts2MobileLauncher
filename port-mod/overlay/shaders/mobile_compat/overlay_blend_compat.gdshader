shader_type canvas_item;

uniform float intensity : hint_range(0,1) = 1.0;

void fragment()
{
	COLOR = texture(TEXTURE, UV) * COLOR;
}
