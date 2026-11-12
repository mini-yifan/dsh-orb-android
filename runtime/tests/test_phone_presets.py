#!/usr/bin/env python3
"""Unit tests for the phone agent presets in the app overlay.

    python3 -I runtime/tests/test_phone_presets.py
"""
import importlib.util
import json
import os
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True   # runtime/lib/__pycache__ is tracked; do not rewrite it
HERE = os.path.dirname(os.path.abspath(__file__))
RTBUILD = os.path.join(HERE, "..", "lib", "rtbuild.py")
spec = importlib.util.spec_from_file_location("rtbuild", RTBUILD)
rtbuild = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rtbuild)

RETURN_FILES = "/opt/dsh-go/plugins/@dsh-mesh/plugin/lib/return-files.mjs"


def mesh_plugin(root, exports=True):
    os.makedirs(os.path.join(root, "lib"), exist_ok=True)
    for name in ("index.mjs", "return-files.mjs"):
        open(os.path.join(root, "lib", name), "w").close()
    manifest = {
        "name": "@dsh-mesh/plugin",
        "version": "0.1.0",
        "type": "module",
        "main": "./lib/index.mjs",
    }
    if exports:
        manifest["exports"] = {
            ".": "./lib/index.mjs",
            "./return-files": {"import": "./lib/return-files.mjs"},
        }
    with open(os.path.join(root, "package.json"), "w") as f:
        json.dump(manifest, f)
    return {
        "name": "@dsh-mesh/plugin",
        "version": "0.1.0",
        "dir": root,
        "entry": "lib/index.mjs",
        "rootfsPath": "/opt/dsh-go/plugins/@dsh-mesh/plugin",
        "sha256": "",
        "config": {"platform": "android", "incomingPreset": "phone-background"},
    }


def overlay(plugins):
    return rtbuild.overlay_entries({}, with_bridge=True, plugins=plugins)["etc/dsh-app/dsh-web.patch.yml"].data.decode()


class PhonePresets(unittest.TestCase):
    def test_orb_preset_is_shared_and_background_preset_is_not_added_without_mesh(self):
        text = overlay([])
        self.assertIn("id: preset-orb-phone", text)
        self.assertIn("id: orb-phone", text)
        self.assertIn("mcp__phone__screen", text)
        self.assertIn("id: tool-device-agent", text)
        self.assertIn("device-agent.mjs", text)
        self.assertIn("device_agent", text)
        self.assertIn("id: tool-background-agent", text)
        self.assertIn("background-agent.js", text)
        self.assertNotIn("preset-phone-background", text)
        self.assertNotIn("phone-background", text)

    def test_background_preset_names_the_bundled_return_files_entry(self):
        with tempfile.TemporaryDirectory() as root:
            text = overlay([mesh_plugin(root)])
        self.assertIn("id: preset-phone-background", text)
        self.assertIn("id: phone-background", text)
        self.assertIn("id: tool-mesh-return-files", text)
        self.assertIn("name: '%s'" % RETURN_FILES, text)
        self.assertNotIn(rtbuild.PHONE_BACKGROUND_RETURN_FILES, text)
        # The standard-only tools that need a person at the phone are deliberately absent.
        block = text.split("id: preset-phone-background", 1)[1]
        for dropped in ("tool-ask-user", "plan-mode", "tool-subagent", "present",
                        "tool-plugin-manager", "tool-plugin-market"):
            self.assertNotIn(dropped, block)

    def test_export_path_is_resolved_from_the_package_manifest(self):
        with tempfile.TemporaryDirectory() as root:
            plugin = mesh_plugin(root)
            self.assertEqual(rtbuild.plugin_export_path(plugin, "./return-files"), RETURN_FILES)

    def test_a_mesh_plugin_without_the_export_fails_the_build(self):
        with tempfile.TemporaryDirectory() as root:
            plugin = mesh_plugin(root, exports=False)
            with self.assertRaises(SystemExit):
                rtbuild.bridge_phone_background_rows([plugin])

    def test_no_mesh_plugin_means_no_rows(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(rtbuild.bridge_phone_background_rows([]), "")
            probe = mesh_plugin(root)
            probe["name"] = "@dsh-orb-android/plugin-probe"
            self.assertEqual(rtbuild.bridge_phone_background_rows([probe]), "")


if __name__ == "__main__":
    unittest.main()