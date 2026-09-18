#!/usr/bin/env python3
"""Prepare the separate VDA 2.1 distribution, without changing the running installation."""

import argparse
import os
from pathlib import Path
import re
import shutil

import yaml


def property_value(value):
    """Escape a value for Java .properties, including passwords with special characters."""
    result = []
    for char in str(value):
        if char in "\\\n\r\t\f":
            result.append({"\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t", "\f": "\\f"}[char])
        elif char == " ":
            result.append("\\ ")
        elif ord(char) < 32 or ord(char) > 126:
            encoded = char.encode("utf-16-be")
            result.extend(f"\\u{int.from_bytes(encoded[i:i+2], 'big'):04x}" for i in range(0, len(encoded), 2))
        else:
            result.append(char)
    return "".join(result)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--controller-config", type=Path, default=root.parent / "mag_agv_controller/config/local.yaml")
    args = parser.parse_args()
    source = root / "build/install/opentcs"
    target = root / "build/install/opentcs-vda21"
    if not (target / "opentcs-kernel/startKernel.sh").is_file():
        parser.error("Build with -I livingRoom/build-vda21.init.gradle installDist first")
    # Do not overwrite a distribution once it has been started.
    for process in Path("/proc").iterdir():
        if process.name.isdigit():
            try:
                cwd = (process / "cwd").resolve(strict=True)
                command = (process / "comm").read_text().strip()
            except (OSError, RuntimeError):
                continue
            if command == "java" and cwd.is_relative_to(target):
                parser.error("Stop the VDA 2.1 applications before preparing their configuration")
    config = yaml.safe_load(args.controller_config.read_text())
    mqtt = config["mqtt"]
    os.umask(0o077)
    for name in ("opentcs-kernel", "opentcs-kernelcontrolcenter", "opentcs-modeleditor", "opentcs-operationsdesk"):
        current = source / name / "config"
        new = target / name / "config"
        for path in current.glob("*"):
            if path.is_file() and "defaults-baseline" not in path.name:
                shutil.copy2(path, new / path.name)
    path = target / "opentcs-kernel/config/opentcs-kernel.properties"
    prefix = "commadapter.vehicle.vda5050."
    values = {
        prefix + "enabledVersions": "1.1, 2.0, 2.1",
        prefix + "mqtt.brokerHost": mqtt["host"],
        prefix + "mqtt.brokerPort": mqtt.get("port", 1883),
        prefix + "mqtt.connectionEncrypted": str(mqtt.get("tls", False)).lower(),
        prefix + "mqtt.username": mqtt.get("username") or "",
        prefix + "mqtt.password": mqtt.get("password") if mqtt.get("password") is not None else "",
    }
    lines = path.read_text().splitlines() if path.exists() else []
    lines = [line for line in lines if re.split(r"[=:\s]", line.strip(), maxsplit=1)[0] not in values]
    lines.extend(f"{key}={property_value(value)}" for key, value in values.items())
    path.write_text("\n".join(lines) + "\n")
    path.chmod(0o600)
    data = target / "opentcs-kernel/data"
    data.mkdir(exist_ok=True)
    shutil.copy2(root / "livingRoom/living_room.xml", data / "model.xml")
    print(f"Prepared {target}; MQTT credentials copied without displaying them.")
    print("Stop the old applications, then start all four applications from the new directory.")


if __name__ == "__main__":
    main()
