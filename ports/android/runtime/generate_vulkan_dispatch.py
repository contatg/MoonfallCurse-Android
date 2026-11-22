"""Generate forwarding definitions for the Vulkan entry points in the Android NDK."""
import pathlib
import re
import subprocess
import sys

nm, library, include, output = sys.argv[1:]
exports = subprocess.check_output([nm, "-D", "--defined-only", "--format=posix", library], text=True)
names = set(re.findall(r"^(vk\w+)\s", exports, re.MULTILINE))
headers = "\n".join(
    (pathlib.Path(include) / name).read_text()
    for name in ("vulkan_core.h", "vulkan_android.h")
)
definitions = []
for result, name, parameters in re.findall(
    r"VKAPI_ATTR\s+(\w+)\s+VKAPI_CALL\s+(vk\w+)\(([^)]*)\);", headers
):
    if name not in names:
        continue
    names.remove(name)
    arguments = ", ".join(
        re.search(r"(\w+)\s*(?:\[[^\]]*\])?\s*$", parameter)[1]
        for parameter in parameters.split(",") if parameter.strip() != "void"
    )
    definitions.append(
        f'extern "C" VKAPI_ATTR {result} VKAPI_CALL __wrap_{name}({parameters}) {{\n'
        f'    static auto call = VulkanEntry<PFN_{name}>("{name}");\n'
        f'    return call({arguments});\n'
        f'}}\n'
    )
if names:
    raise SystemExit(f"No Vulkan prototypes for: {', '.join(sorted(names))}")
pathlib.Path(output).write_text("\n".join(definitions))
