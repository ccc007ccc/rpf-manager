import pathlib
import struct
import sys
root = pathlib.Path(sys.argv[1])
output = ['#pragma once', '#include <cstdint>']
for name in ('vert', 'frag'):
    data = (root / f'scene.{name}.spv').read_bytes()
    words = struct.unpack(f'<{len(data) // 4}I', data)
    output.append(f'const uint32_t scene_{name}[] = {{' + ','.join(hex(v) for v in words) + '};')
(root / 'shaders.h').write_text('\n'.join(output))
