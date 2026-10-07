#version 450
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec3 color;
layout(location=3) in vec3 uv;
layout(location=0) out vec3 worldNormal;
layout(location=1) out vec3 vertexColor;
layout(location=2) out vec3 texcoord;
layout(push_constant) uniform Scene { mat4 mvp; vec4 options; } scene;
void main() {
    gl_Position = scene.mvp * vec4(position, 1.0);
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    worldNormal = normal;
    vertexColor = color;
    texcoord = uv;
}
