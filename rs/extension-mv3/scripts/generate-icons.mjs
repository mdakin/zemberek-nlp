import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const assetsDir = path.resolve(__dirname, "../assets");

export function writeExtensionIcons(outIconsDir) {
  fs.mkdirSync(outIconsDir, { recursive: true });
  for (const name of [
    "icon-16.png",
    "icon-32.png",
    "icon-48.png",
    "icon-128.png",
    "zemberek-icon.svg",
  ]) {
    fs.copyFileSync(path.join(assetsDir, name), path.join(outIconsDir, name));
  }
}
