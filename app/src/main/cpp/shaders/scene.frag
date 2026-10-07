#version 450
layout(location=0) in vec3 worldNormal;
layout(location=1) in vec3 vertexColor;
layout(location=2) in vec3 texcoord;
layout(set=0, binding=0) uniform sampler2D diffuseTexture;
layout(location=0) out vec4 color;
layout(push_constant) uniform Scene { mat4 mvp; vec4 options; } scene;
void main() {
    if (scene.options.x > 0.5) {
        color = vec4(vertexColor, 1.0);
    } else {
        vec3 n = normalize(worldNormal);
        if (!gl_FrontFacing) n = -n;
        float key = max(dot(n, normalize(vec3(-0.4, -0.6, 1.0))), 0.0);
        float fill = max(dot(n, normalize(vec3(0.8, 0.2, 0.4))), 0.0);
        float hemi = n.z * 0.12 + 0.36;
        vec4 base = vec4(vertexColor, 1.0);
        if (scene.options.y > 0.5 && texcoord.z > 0.5) {
            base = texture(diffuseTexture, texcoord.xy);
            if (base.a < 0.1) discard;
        }
        color = vec4(base.rgb * (hemi + key * 0.48 + fill * 0.16), 1.0);
    }
}
