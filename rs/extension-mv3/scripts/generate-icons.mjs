import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let b = 0; b < 8; b++) {
      const mask = -(crc & 1);
      crc = (crc >>> 1) ^ (0xedb88320 & mask);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(typeStr, data) {
  const typeBytes = Buffer.from(typeStr, "ascii");
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  typeBytes.copy(out, 4);
  data.copy(out, 8);
  const crcInput = Buffer.concat([typeBytes, data]);
  out.writeUInt32BE(crc32(crcInput), 8 + data.length);
  return out;
}

function insideRoundedRect(x, y, size, margin, radius) {
  const left = margin;
  const right = size - 1 - margin;
  const top = margin;
  const bottom = size - 1 - margin;
  if (x < left || x > right || y < top || y > bottom) return false;

  const cx =
    x < left + radius
      ? left + radius
      : x > right - radius
      ? right - radius
      : x;
  const cy =
    y < top + radius
      ? top + radius
      : y > bottom - radius
      ? bottom - radius
      : y;
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= radius * radius;
}

function insideZGlyph(nx, ny) {
  // Normalized [0, 1] coordinates for a crisp geometric "Z" with Turkish diacritic dot accent
  const topBar = ny >= 0.24 && ny <= 0.35 && nx >= 0.24 && nx <= 0.76;
  const botBar = ny >= 0.67 && ny <= 0.78 && nx >= 0.24 && nx <= 0.76;
  const diagCenter = 0.76 - ((ny - 0.30) / 0.42) * 0.52;
  const diag =
    ny >= 0.30 && ny <= 0.72 && Math.abs(nx - diagCenter) <= 0.085;
  const accentDx = nx - 0.50;
  const accentDy = ny - 0.14;
  const accentDot = accentDx * accentDx + accentDy * accentDy <= 0.045 * 0.045;
  return topBar || botBar || diag || accentDot;
}

export function renderIconPng(size) {
  const rawScanlines = Buffer.alloc(size * (1 + size * 4));
  const margin = Math.max(1, Math.floor(size * 0.06));
  const radius = Math.max(2, Math.floor(size * 0.22));

  for (let y = 0; y < size; y++) {
    const rowOff = y * (1 + size * 4);
    rawScanlines[rowOff] = 0; // Filter type 0 (None)
    for (let x = 0; x < size; x++) {
      const pxOff = rowOff + 1 + x * 4;
      if (!insideRoundedRect(x, y, size, margin, radius)) {
        rawScanlines[pxOff] = 0;
        rawScanlines[pxOff + 1] = 0;
        rawScanlines[pxOff + 2] = 0;
        rawScanlines[pxOff + 3] = 0;
        continue;
      }
      const nx = (x + 0.5) / size;
      const ny = (y + 0.5) / size;
      if (insideZGlyph(nx, ny)) {
        rawScanlines[pxOff] = 255;
        rawScanlines[pxOff + 1] = 255;
        rawScanlines[pxOff + 2] = 255;
        rawScanlines[pxOff + 3] = 255;
      } else {
        // Rich Anatolian teal-emerald gradient (#0D766E -> #0F9D8A)
        const t = (nx + ny) * 0.5;
        rawScanlines[pxOff] = Math.round(13 + t * 8);
        rawScanlines[pxOff + 1] = Math.round(118 + t * 39);
        rawScanlines[pxOff + 2] = Math.round(110 + t * 28);
        rawScanlines[pxOff + 3] = 255;
      }
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // 8-bit depth
  ihdr[9] = 6; // RGBA color type
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const idat = zlib.deflateSync(rawScanlines, { level: 9 });
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  return Buffer.concat([
    signature,
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", idat),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

export function writeExtensionIcons(outIconsDir) {
  fs.mkdirSync(outIconsDir, { recursive: true });
  for (const size of [16, 48, 128]) {
    const png = renderIconPng(size);
    fs.writeFileSync(path.join(outIconsDir, `icon-${size}.png`), png);
  }
}
